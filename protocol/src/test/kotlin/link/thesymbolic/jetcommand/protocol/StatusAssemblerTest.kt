package link.thesymbolic.jetcommand.protocol

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class StatusAssemblerTest {
    private val interval = 2000L

    private fun packet(notification: Int = 0) =
        BedJetSim().also { it.notification = notification }.packet()

    /** The first 20 bytes as notified; byte 0 is 1 on the BedJet's partial notifications. */
    private fun head(notification: Int = 0, first: Int = 1) =
        packet(notification).copyOf(20).also { it[0] = first.toByte() }

    private fun rest(notification: Int = 0) = packet(notification).copyOfRange(20, 30)

    @Test
    fun `a whole packet is fresh and drops a waiting head`() {
        val step = StatusAssembler(head = head()).notified(packet(7), now = 0, interval)
        assertEquals(StatusAssembler(extra = step.update!!.status.extra), step.next)
        assertTrue(step.update.extraFresh)
        assertEquals(7, step.update.status.extra!!.notification)
        assertFalse(step.readRemainder)
    }

    @Test
    fun `a head carries the last extra and asks for the remainder, at most every interval`() {
        val known = Reply.parseStatus(packet(3))!!.extra
        val first = StatusAssembler(extra = known).notified(head(), now = 100, interval)
        assertTrue(first.readRemainder)
        assertEquals(
            StatusAssembler(head(), known, lastRemainderRead = 100, remainderPending = true),
            first.next,
        )
        assertEquals(known, first.update!!.status.extra)
        assertFalse(first.update.extraFresh)

        val settled = first.next.remainderSettled()
        assertFalse(settled.notified(head(), now = 2099, interval).readRemainder)
        assertTrue(settled.notified(head(), now = 2100, interval).readRemainder)
        assertFalse(
            first.next.notified(head(), now = 9999, interval).readRemainder,
            "one read at a time",
        )
        assertFalse(
            StatusAssembler().notified(head(first = 0), now = 0, interval).readRemainder,
            "only heads marked 1 lack their remainder",
        )
    }

    @Test
    fun `a pending remainder merges whether notified or read, once`() {
        val waiting = StatusAssembler().notified(head(), now = 0, interval).next
        for (step in
            listOf(waiting.notified(rest(5), now = 1, interval), waiting.remainderRead(rest(5)))) {
            assertTrue(step.update!!.extraFresh)
            assertEquals(5, step.update.status.extra!!.notification)
            assertEquals(30, step.update.status.length)
            assertFalse(step.next.remainderPending)
            assertEquals(step.update.status.extra, step.next.extra)
            assertEquals(Assembled(step.next), step.next.remainderRead(rest(5)))
        }
    }

    @Test
    fun `stray, late or broken remainders`() {
        val idle = StatusAssembler(lastRemainderRead = 0)
        assertEquals(Assembled(idle), idle.notified(rest(), now = 1, interval), "nothing pending")

        val waiting = StatusAssembler().notified(head(), now = 0, interval).next
        assertEquals(
            Assembled(waiting.remainderSettled()),
            waiting.remainderRead(head()),
            "a read that returns a head is not a remainder",
        )
        val short = waiting.notified(byteArrayOf(1, 2), now = 1, interval)
        assertNull(short.update)
        assertFalse(short.next.remainderPending)
        assertEquals("Status remainder didn't complete the packet: 01 02", short.warning)

        val headless = waiting.copy(head = null).notified(rest(), now = 1, interval)
        assertEquals(Assembled(waiting.copy(head = null, remainderPending = false)), headless)
    }

    @Test
    fun `an unparseable status is only a warning`() {
        val bad = head().also { it[9] = 0x7f }
        val step = StatusAssembler().notified(bad, now = 0, interval)
        assertEquals(StatusAssembler(), step.next)
        assertNull(step.update)
        assertEquals("Unparseable status: ${bad.toHex()}", step.warning)
    }

    @Test
    fun `reset forgets the packet but keeps the read rate limit`() {
        val busy =
            StatusAssembler(
                head(),
                Reply.parseStatus(packet())!!.extra,
                lastRemainderRead = 42,
                remainderPending = true,
            )
        assertEquals(StatusAssembler(lastRemainderRead = 42), busy.reset())
    }

    private enum class Input {
        HEAD,
        UNMARKED_HEAD,
        WHOLE,
        REMAINDER,
        READ_REMAINDER,
        READ_HEAD,
        READ_SETTLED,
        RESET,
    }

    @Test
    fun `no fresh update without extra, no second read while one is pending`() = runTest {
        val step =
            Arb.bind(Arb.element(Input.entries), Arb.long(0L..3000L), Arb.int(0..255), ::Triple)
        checkAll(Arb.list(step, 0..60)) { inputs ->
            var a = StatusAssembler()
            var t = 0L
            for ((input, dt, notification) in inputs) {
                t += dt
                val out =
                    when (input) {
                        Input.HEAD -> a.notified(head(notification), t, interval)
                        Input.UNMARKED_HEAD -> a.notified(head(notification, 0), t, interval)
                        Input.WHOLE -> a.notified(packet(notification), t, interval)
                        Input.REMAINDER -> a.notified(rest(notification), t, interval)
                        Input.READ_REMAINDER -> a.remainderRead(rest(notification))
                        Input.READ_HEAD -> a.remainderRead(head(notification))
                        Input.READ_SETTLED -> Assembled(a.remainderSettled())
                        Input.RESET -> Assembled(a.reset())
                    }
                val update = out.update
                assertTrue(update == null || !update.extraFresh || update.status.extra != null)
                assertTrue(!out.readRemainder || !a.remainderPending, "$inputs")
                assertTrue(!out.readRemainder || out.next.remainderPending)
                a = out.next
            }
        }
    }
}
