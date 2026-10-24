package link.thesymbolic.jetcommand.protocol

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import link.thesymbolic.jetcommand.protocol.SimTransport.Fault
import link.thesymbolic.jetcommand.protocol.SimTransport.Op

@OptIn(ExperimentalCoroutinesApi::class)
class BedJetLinkTest {
    private class Rig(
        val sim: BedJetSim,
        val transport: SimTransport,
        val link: BedJetLink,
        val device: BedJetDevice,
    ) {
        val updates = mutableListOf<StatusUpdate>()

        fun reads(ch: Characteristic) = transport.ops.count { it == Op.Read(ch) }
    }

    private fun TestScope.rig(
        sim: BedJetSim = BedJetSim(),
        mtu: Int = 23,
        hasReplyAlt: Boolean = true,
        autoAck: Boolean = true,
        time: LocalTime = LocalTime.of(22, 0),
        config: BedJetLink.Config = BedJetLink.Config(),
    ): Rig {
        val transport = SimTransport(sim, backgroundScope, mtu, hasReplyAlt)
        val link = BedJetLink(transport, backgroundScope, config, now = { currentTime })
        val device =
            BedJetDevice(link, backgroundScope, autoAck, clock = { time }, now = { currentTime })
        return Rig(sim, transport, link, device).also { rig ->
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                link.statuses.toList(rig.updates)
            }
        }
    }

    private suspend fun TestScope.connected(rig: Rig) = rig.also { it.link.connect() }

    private fun step(mode: Mode, fan: Int, temp: Int, hours: Int, minutes: Int) =
        Step(mode, fan, temp, Timing.For(hours, minutes))

    private suspend fun code(code: ErrorCode, block: suspend () -> Unit) =
        assertEquals(code, assertFailsWith<BedJetException> { block() }.code)

    // ---- connecting ----

    @Test
    fun `commands need a connection`() = runTest {
        val r = rig()
        code(ErrorCode.NOT_CONNECTED) { r.link.send(Command.ack()) }
        code(ErrorCode.NOT_CONNECTED) { r.link.query(Query.FIRMWARE) }
    }

    @Test
    fun `GATT 133 on connect, then success`() = runTest {
        val r = rig()
        r.transport.inject(Fault.Fail(133), times = 2) { it == Op.Connect }
        repeat(2) {
            val e = assertFailsWith<BedJetException> { r.link.connect() }
            assertEquals(ErrorCode.NOT_CONNECTED, e.code)
            assertTrue("133" in e.message!!, e.message)
            assertEquals(LinkState.Disconnected(), r.link.state.value)
        }
        r.link.connect()
        assertEquals(LinkState.Ready, r.link.state.value)
        assertEquals("BEDJET-DEMO", r.link.deviceName)
    }

    @Test
    fun `connect that never completes times out`() = runTest {
        val r = rig()
        r.transport.inject(Fault.Hang) { it == Op.Connect }
        code(ErrorCode.TIMEOUT) { r.link.connect() }
        assertEquals(20_000, currentTime)
        r.link.connect()
        assertEquals(LinkState.Ready, r.link.state.value)
    }

    @Test
    fun `lost link reconnects with backoff, through 133s`() = runTest {
        val r = connected(rig())
        r.transport.inject(Fault.Fail(133), times = 2) { it == Op.Connect }
        val t0 = currentTime
        r.transport.loseLink()
        runCurrent()
        assertEquals(LinkState.Connecting(1), r.link.state.value)
        // 3 s, then 6 s, then 12 s after each failure.
        advanceTimeBy(3_000 + 6_000 + 12_000 - 1)
        assertEquals(LinkState.Connecting(3), r.link.state.value)
        advanceTimeBy(2)
        assertEquals(LinkState.Ready, r.link.state.value)
        assertEquals(t0 + 21_000, currentTime - 1)
        assertEquals(0, r.link.reconnectAttempt)
    }

    @Test
    fun `reconnect gives up after 10 attempts`() = runTest {
        val r = connected(rig())
        r.transport.inject(Fault.Fail(133), times = 100) { it == Op.Connect }
        r.transport.loseLink()
        advanceTimeBy(1.hours)
        assertEquals(LinkState.Disconnected(gaveUp = true), r.link.state.value)
        assertEquals(1 + 10, r.transport.ops.count { it == Op.Connect })
    }

    @Test
    fun `reconnect forever keeps trying all night at most every 60 s`() = runTest {
        val r = connected(rig(config = BedJetLink.Config(maxReconnectAttempts = null)))
        r.transport.inject(Fault.Fail(133), times = Int.MAX_VALUE) { it == Op.Connect }
        r.transport.loseLink()
        advanceTimeBy(10.hours)
        assertTrue(r.link.state.value is LinkState.Connecting, r.link.state.value.toString())
        // 3+6+12+24+48 s, then one attempt a minute.
        val attempts = r.transport.ops.count { it == Op.Connect } - 1
        assertEquals(5 + (10 * 3600 - 93) / 60, attempts)
    }

    @Test
    fun `a failed first connect retries when configured`() = runTest {
        val r =
            rig(config = BedJetLink.Config(maxReconnectAttempts = null, retryFailedConnect = true))
        r.transport.inject(Fault.Fail(133), times = 3) { it == Op.Connect }
        code(ErrorCode.NOT_CONNECTED) { r.link.connect() }
        assertEquals(LinkState.Connecting(1), r.link.state.value)
        advanceTimeBy(3_000 + 6_000 + 12_000 + 1)
        assertEquals(LinkState.Ready, r.link.state.value)
    }

    @Test
    fun `retryNow cuts the backoff short and restarts it`() = runTest {
        val r = connected(rig(config = BedJetLink.Config(maxReconnectAttempts = null)))
        var bluetoothOn = false
        // As on the phone with Bluetooth off: every connect fails at once.
        r.transport.inject(Fault.Fail(0), times = Int.MAX_VALUE) {
            it == Op.Connect && !bluetoothOn
        }
        r.transport.loseLink()
        advanceTimeBy(1.hours)
        val waitingFor = r.link.reconnectAttempt
        assertTrue(waitingFor > 10)
        bluetoothOn = true
        r.link.retryNow()
        runCurrent()
        assertEquals(LinkState.Ready, r.link.state.value)
        assertEquals(0, r.link.reconnectAttempt)
    }

    @Test
    fun `retryNow does nothing for a link nobody wants`() = runTest {
        val r = connected(rig())
        r.link.disconnect()
        r.link.retryNow()
        advanceTimeBy(1.hours)
        assertEquals(LinkState.Disconnected(), r.link.state.value)
        assertEquals(1, r.transport.ops.count { it == Op.Connect })
    }

    @Test
    fun `no reconnect after an explicit disconnect`() = runTest {
        val r = connected(rig())
        r.link.disconnect()
        advanceTimeBy(1.hours)
        assertEquals(LinkState.Disconnected(), r.link.state.value)
        assertEquals(1, r.transport.ops.count { it == Op.Connect })
    }

    // ---- queries ----

    @Test
    fun `stale replies are retried with the same tag until one matches`() = runTest {
        val r = connected(rig())
        r.transport.replyLatency = SimTransport.ReplyLatency(staleReads = 3)
        assertEquals("V1.20 (demo)", r.device.firmware())
        // Attempt 1 reads 2006 and 2005 stale, attempt 2 reads 2006 stale and 2005 fresh.
        assertEquals(listOf("41 20 01", "41 20 01"), r.transport.writes)
        assertEquals(Characteristic.REPLY, r.link.replySource)
        r.transport.replyLatency = SimTransport.ReplyLatency()
        r.device.deviceName()
        assertEquals("41 00 02", r.transport.writes.last(), "a new request gets a new tag")
        assertEquals(Characteristic.REPLY_ALT, r.link.replySource)
    }

    @Test
    fun `a reply that comes late in virtual time is retried every 400 ms`() = runTest {
        val r = connected(rig())
        r.transport.replyLatency = SimTransport.ReplyLatency(delayMs = 1_000)
        val t0 = currentTime
        assertEquals("BEDJET-DEMO", r.device.deviceName())
        assertEquals(4, r.transport.writes.size)
        assertEquals(1_200, currentTime - t0)
    }

    @Test
    fun `gives up after 8 attempts`() = runTest {
        val r = connected(rig())
        code(ErrorCode.BAD_RESPONSE) { r.link.query(0x30) }
        assertEquals(List(8) { "41 30 01" }, r.transport.writes)
    }

    @Test
    fun `2006 is read before 2005 on every query`() = runTest {
        val r = connected(rig())
        r.link.query(Query.DEVICE_NAME)
        r.link.query(Query.FIRMWARE)
        val reads =
            r.transport.ops
                .filterIsInstance<Op.Read>()
                .map { it.characteristic }
                .filter { it == Characteristic.REPLY || it == Characteristic.REPLY_ALT }
        assertEquals(listOf(Characteristic.REPLY_ALT, Characteristic.REPLY_ALT), reads)
        assertEquals(Characteristic.REPLY_ALT, r.link.replySource)
    }

    @Test
    fun `without 2006 replies come from 2005, cut at the MTU`() = runTest {
        val r =
            connected(
                rig(
                    sim =
                        BedJetSim(
                            slots =
                                listOf(
                                    Program("Night", List(5) { step(Mode.COOL, 5, 44, 1, 0) }),
                                    null,
                                    null,
                                )
                        ),
                    hasReplyAlt = false,
                )
            )
        assertEquals("V1.20 (demo)", r.device.firmware())
        assertEquals(Characteristic.REPLY, r.link.replySource)
        assertEquals(2, r.device.programNames().size, "48 bytes of names cut to 20")
        val replies = r.link.readReplies()
        assertEquals(22, replies[Characteristic.REPLY]!!.size)
        assertNull(replies[Characteristic.REPLY_ALT])
        // 5 steps don't fit a 22-byte read.
        code(ErrorCode.BAD_RESPONSE) { r.device.programSteps(slot(0)) }
        assertEquals(
            1,
            r.reads(Characteristic.REPLY_ALT),
            "the missing characteristic is remembered",
        )
    }

    @Test
    fun `without 2006 but with a large MTU, store works through 2005`() = runTest {
        val r = connected(rig(mtu = 247, hasReplyAlt = false))
        val steps = List(11) { step(Mode.COOL, it, 44, 0, 20 + it) }
        assertEquals(Program("Long", steps), r.device.store(Program("Long", steps), slot(2)))
    }

    @Test
    fun `a write that never completes times out, the queue goes on`() = runTest {
        val r = connected(rig())
        r.transport.inject(Fault.Hang) { it is Op.Write }
        val t0 = currentTime
        code(ErrorCode.TIMEOUT) { r.link.send(Command.setFan(3)) }
        assertEquals(5_000, currentTime - t0)
        r.link.send(Command.setFan(4))
        assertEquals(4, r.sim.fan)
    }

    @Test
    fun `a failed operation is a bad response, latency is just slow`() = runTest {
        val r = connected(rig())
        r.transport.inject(Fault.Fail(5)) { it is Op.Write }
        code(ErrorCode.BAD_RESPONSE) { r.link.send(Command.setFan(3)) }
        r.transport.latencyMs = 300
        val t0 = currentTime
        r.link.send(Command.setFan(4))
        assertEquals(300, currentTime - t0)
    }

    // ---- status ----

    @Test
    fun `partial status reads the remainder and merges it, at most every 2 s`() = runTest {
        val r = connected(rig())
        r.sim.notification = Notification.CLEAN_FILTER
        r.transport.notifyStatus()
        runCurrent()
        assertEquals(1, r.reads(Characteristic.STATUS))
        assertEquals(2, r.updates.size)
        assertNull(r.updates[0].status.extra)
        assertFalse(r.updates[0].extraFresh)
        assertEquals(Notification.CLEAN_FILTER, r.updates[1].status.extra!!.notification)
        assertTrue(r.updates[1].extraFresh)
        assertEquals(30, r.updates[1].status.length)

        advanceTimeBy(500)
        r.transport.notifyStatus()
        runCurrent()
        assertEquals(1, r.reads(Characteristic.STATUS), "remainder is read at most every 2 s")
        assertEquals(
            Notification.CLEAN_FILTER,
            r.updates[2].status.extra!!.notification,
            "last known extra is carried over",
        )
        assertFalse(r.updates[2].extraFresh)
    }

    @Test
    fun `even with a large MTU notifications are partial and the remainder keeps extra fresh`() =
        runTest {
            val r = connected(rig(mtu = 500))
            advanceTimeBy(10.seconds)
            assertTrue(
                r.reads(Characteristic.STATUS) in 4..6,
                "remainder every 2 s, not per notification",
            )
            assertTrue(r.updates.count { it.extraFresh } in 4..6)
            assertTrue(r.updates.count { !it.extraFresh } >= 30, "~4 notifications a second")
        }

    @Test
    fun `dropped notifications only thin out the stream`() = runTest {
        val r = connected(rig())
        r.transport.dropNotifications = 12
        advanceTimeBy(3_100)
        assertTrue(r.updates.isEmpty())
        advanceTimeBy(300)
        assertTrue(r.updates.isNotEmpty())
    }

    // ---- device ----

    @Test
    fun `store and read back`() = runTest {
        val r =
            connected(
                rig(
                    sim =
                        BedJetSim(
                            slots =
                                listOf(
                                    Program("old", listOf(step(Mode.DRY, 1, 50, 1, 0))),
                                    null,
                                    null,
                                )
                        )
                )
            )
        val steps =
            List(11) { i -> step(if (i % 2 == 1) Mode.HEAT else Mode.COOL, i, 50, 0, 20 + i) }
        assertEquals("Deep Sleep", r.device.store(Program("Deep Sleep", steps), slot(1)).name)
        assertEquals(
            listOf(
                Program("old", listOf(step(Mode.DRY, 1, 50, 1, 0))),
                Program("Deep Sleep", steps),
                null,
            ),
            r.device.storedPrograms(),
        )
        assertEquals("BEDJET-DEMO", r.device.deviceName())
    }

    @Test
    fun `a step past the heat cut-off is not stored`() = runTest {
        val r = connected(rig())
        val e =
            assertFailsWith<BedJetException> {
                r.device.store(Program("too hot", listOf(step(Mode.HEAT, 19, 80, 3, 0))), slot(0))
            }
        assertEquals(ErrorCode.STEP_TOO_LONG, e.code)
        assertTrue(e.message!!.startsWith("Step 1"), e.message)
        assertNull(r.sim.slots[0])
        assertTrue(r.transport.writes.isEmpty())
    }

    @Test
    fun `link loss mid-store, reconnect, store again`() = runTest {
        val r = connected(rig())
        val steps = List(12) { step(Mode.COOL, 5, 44, 0, 30) }
        r.transport.inject(Fault.LoseLink) {
            it is Op.Write && it.bytes.u(1) == Query.fragment(slot(0), 1)
        }
        code(ErrorCode.NOT_CONNECTED) { r.device.store(Program("Night", steps), slot(0)) }
        assertEquals(LinkState.Connecting(1), r.link.state.value)
        code(ErrorCode.NOT_CONNECTED) { r.device.store(Program("Night", steps), slot(0)) }
        advanceTimeBy(3_001)
        assertEquals(LinkState.Ready, r.link.state.value)
        assertEquals(Program("Night", steps), r.device.store(Program("Night", steps), slot(0)))
    }

    @Test
    fun `start program syncs the clock and runs step 1`() = runTest {
        val r = connected(rig(time = LocalTime.of(22, 15)))
        r.device.store(
            Program("Night", listOf(step(Mode.COOL, 5, 44, 2, 0), step(Mode.WAIT, 19, 80, 0, 30))),
            slot(0),
        )
        assertNull(r.sim.clock)
        val status = r.device.startProgram(slot(0))
        assertEquals(
            22 * 60 + 15,
            r.sim.clock!! / 60,
            "synced, plus the seconds until the unit confirmed",
        )
        assertEquals(Mode.COOL, status.mode)
        assertEquals(5, status.fanStep)
        assertEquals(44, status.targetTemp)
        assertEquals(1, status.extra!!.programStep)
        r.sim.skipAhead(125.0)
        advanceTimeBy(3_000)
        assertEquals(2, r.device.status!!.status.extra!!.programStep)
        assertEquals(Mode.WAIT, r.device.status!!.status.mode)
        r.device.stopProgram()
        advanceTimeBy(3_000)
        assertEquals(0, r.device.status!!.status.extra!!.programStep)
        assertEquals(Mode.STANDBY, r.device.status!!.status.mode)
    }

    @Test
    fun `refused program raises the typed error and the notification is acknowledged`() = runTest {
        val r =
            connected(
                rig(
                    sim =
                        BedJetSim(
                            slots =
                                listOf(
                                    Program("too hot", listOf(step(Mode.HEAT, 19, 80, 3, 0))),
                                    null,
                                    null,
                                )
                        )
                )
            )
        val shown = mutableListOf<Int>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            r.device.notifications.collect { shown += it.code }
        }
        val e = assertFailsWith<BedJetException> { r.device.startProgram(slot(0)) }
        assertEquals(ErrorCode.STEP_TOO_LONG, e.code)
        assertEquals(Notification.TEXT[Notification.PROGRAM_TOO_LONG], e.message)
        assertEquals(listOf(Notification.PROGRAM_TOO_LONG), shown)
        assertTrue(
            "01 80" in r.transport.writes && "01 52" in r.transport.writes,
            r.transport.writes.toString(),
        )
        assertEquals(0, r.sim.notification)
        advanceTimeBy(3_000)
        assertEquals(0, r.device.status!!.status.extra!!.notification)
        assertEquals(0, r.device.status!!.status.extra!!.programStep)
    }

    @Test
    fun `start program times out when the unit never confirms`() = runTest {
        val r = connected(rig())
        r.device.store(Program("Night", listOf(step(Mode.COOL, 5, 44, 2, 0))), slot(0))
        r.transport.dropNotifications = 100
        code(ErrorCode.TIMEOUT) { r.device.startProgram(slot(0)) }
    }

    @Test
    fun `notifications are acknowledged automatically and shown once per code`() = runTest {
        val r = connected(rig())
        val shown = mutableListOf<DeviceNotification>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            r.device.notifications.collect { shown += it }
        }
        r.sim.notification = Notification.CLEAN_FILTER
        advanceTimeBy(1_500)
        assertEquals(0, r.sim.notification)
        assertEquals(listOf(DeviceNotification(1, Notification.TEXT.getValue(1))), shown)
        assertEquals(1, r.transport.writes.count { it == "01 52" })
    }

    @Test
    fun `a whole night in virtual time, phone away for part of it`() = runTest {
        val r = connected(rig(time = LocalTime.of(22, 0)))
        r.transport.chaos = SimTransport.Chaos(seed = 42, dropRate = 0.2)
        val night =
            listOf(
                step(Mode.COOL, 5, 44, 2, 0),
                step(Mode.DRY, 9, 50, 1, 0),
                Step(Mode.WAIT, 19, 80, Timing.Until(6, 30)),
            )
        r.device.store(Program("Night", night), slot(0))
        r.device.startProgram(slot(0))
        advanceTimeBy(3.5.hours)
        r.link.disconnect()
        advanceTimeBy(1.hours)
        assertEquals(3, r.sim.step, "the unit runs on without the phone")
        r.link.connect()
        advanceTimeBy(5.hours)
        val steps =
            r.updates
                .filter { it.extraFresh }
                .map { it.status.extra!!.programStep }
                .fold(listOf<Int>()) { seen, s -> if (seen.lastOrNull() == s) seen else seen + s }
        assertEquals(listOf(1, 2, 3, 0), steps)
        assertEquals(Mode.STANDBY, r.updates.last().status.mode)
    }
}
