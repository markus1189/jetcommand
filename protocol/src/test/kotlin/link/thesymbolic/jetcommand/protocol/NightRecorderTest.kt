package link.thesymbolic.jetcommand.protocol

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import link.thesymbolic.jetcommand.protocol.NightEvent.Kind
import link.thesymbolic.jetcommand.protocol.SimTransport.Fault
import link.thesymbolic.jetcommand.protocol.SimTransport.Op

@OptIn(ExperimentalCoroutinesApi::class)
class NightRecorderTest {
    @Suppress("LongMethod") // one scripted night; splitting it would scatter the timeline
    @Test
    fun `a full night - program, an hour without link, reconnect, no duplicates from 4 notifications per second`() =
        runTest {
            val sim = BedJetSim()
            lateinit var recorder: NightRecorder
            val st = SimTransport(sim, backgroundScope)
            val transport = TappedTransport(st) { recorder.command(it) }
            val link =
                BedJetLink(
                    transport,
                    backgroundScope,
                    BedJetLink.Config(maxReconnectAttempts = null),
                    now = { currentTime },
                )
            val device =
                BedJetDevice(
                    link,
                    backgroundScope,
                    clock = { LocalTime.of(22, 0) },
                    now = { currentTime },
                )
            val store = InMemoryNightLogStore()
            recorder = NightRecorder(link, store, backgroundScope, clock = { currentTime })
            link.connect()
            advanceTimeBy(1_000)
            device.store(
                Program(
                    "Night",
                    listOf(
                        Step(Mode.COOL, 5, 44, 2, 0, false),
                        Step(Mode.HEAT, 9, 60, 3, 0, false),
                    ),
                ),
                0,
            )
            device.startProgram(0)
            advanceTimeBy(1.hours)

            var away = true
            st.inject(Fault.Fail(0), times = Int.MAX_VALUE) { it == Op.Connect && away }
            val lostAt = currentTime
            st.loseLink()
            advanceTimeBy(1.hours) // step 1 ends and step 2 starts while the phone is away
            away = false
            advanceTimeBy(2.minutes)
            assertEquals(LinkState.Ready, link.state.value)
            advanceTimeBy(4.hours) // step 2 ends, program over
            runCurrent()

            val events = store.all
            val kinds = events.map { it.kind }
            assertEquals(1, kinds.count { it == Kind.CONNECTED })
            assertEquals(1, kinds.count { it == Kind.LOST })
            assertEquals(1, kinds.count { it == Kind.RECONNECTED })
            assertEquals(
                listOf("step 1", "step 2", "none"),
                events.filter { it.kind == Kind.PROGRAM_STEP }.map { it.detail },
            )
            assertEquals(
                listOf("Off", "Cool", "Heat", "Off"),
                events.filter { it.kind == Kind.MODE }.map { it.detail },
            )
            // 5 h of 4 notifications/s are ~72 000 statuses; only changes are events.
            assertTrue(events.size < 40, events.joinToString("\n"))
            val lost = events.single { it.kind == Kind.LOST }
            assertEquals(lostAt, lost.at)
            val back = events.single { it.kind == Kind.RECONNECTED }
            assertTrue(back.at - lostAt in 3_600_000L..3_720_000L, "${back.at - lostAt}")
            // The step change during the gap is stamped when the phone learned of it, after the
            // reconnect.
            assertTrue(events.single { it.detail == "step 2" }.at >= back.at)
            assertTrue(
                events.any { it.kind == Kind.COMMAND && it.detail.startsWith("start program 0") }
            )
            assertTrue(
                events.any { it.kind == Kind.COMMAND && it.detail.startsWith("clock 22:00") }
            )
            assertEquals(events.sortedBy { it.at }, events, "in time order")

            val samples = store.samples
            assertTrue(samples.zipWithNext().all { (a, b) -> b.at - a.at >= 60_000 })
            assertTrue(
                samples.none { it.at in lostAt + 1 until back.at },
                "no samples while the link was down",
            )
            // Connected for 1 h before the loss and ~4 h after it.
            assertTrue(samples.size in 295..305, "${samples.size}")
        }

    @Test
    fun `explicit disconnect is not a loss, shutdown reason and notifications recorded once`() =
        runTest {
            val sim = BedJetSim()
            val st = SimTransport(sim, backgroundScope)
            val link = BedJetLink(st, backgroundScope, now = { currentTime })
            BedJetDevice(link, backgroundScope, autoAck = false, now = { currentTime })
            val store = InMemoryNightLogStore()
            val recorder = NightRecorder(link, store, backgroundScope, clock = { currentTime })
            link.connect()
            advanceTimeBy(10_000)
            sim.notification = Notification.CLEAN_FILTER
            sim.shutdown = 3
            advanceTimeBy(10_000)
            recorder.userDisconnecting()
            link.disconnect()
            runCurrent()
            val kinds = store.all.map { it.kind }
            assertEquals(1, kinds.count { it == Kind.NOTIFICATION })
            assertEquals(1, kinds.count { it == Kind.SHUTDOWN })
            assertEquals(Kind.DISCONNECTED, kinds.last())
            assertTrue(Kind.LOST !in kinds)
        }
}
