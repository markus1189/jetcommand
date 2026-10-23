package link.thesymbolic.jetcommand.protocol

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.thesymbolic.jetcommand.protocol.NightEvent.Kind
import link.thesymbolic.jetcommand.protocol.NightSummary.Cause
import link.thesymbolic.jetcommand.protocol.NightSummary.Gap

class NightGroupingTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun t(day: Int, h: Int, m: Int, s: Int = 0) =
        LocalDateTime.of(2026, 10, day, h, m, s).atZone(zone).toInstant().toEpochMilli()

    private fun ev(at: Long, kind: Kind, detail: String = "") = NightEvent(at, kind, detail)

    private fun started(at: Long) = ev(at, Kind.PROCESS, "process started")

    private fun minutes(from: Long, to: Long) = (from..to step 60_000).toList()

    @Test
    fun `empty input has no nights`() {
        assertEquals(emptyList(), groupNights(emptyList(), emptyList(), zone, t(22, 23, 0)))
    }

    @Test
    fun `a normal night - one connection, presses, no gaps, finished`() {
        val events =
            listOf(
                started(t(21, 22, 0)),
                ev(t(21, 22, 0, 5), Kind.CONNECTED, "BedJet"),
                ev(t(21, 22, 1), Kind.COMMAND, "heat"),
                ev(t(21, 22, 1, 1), Kind.MODE, "Heat"),
                ev(t(22, 6, 30), Kind.COMMAND, "off"),
                ev(t(22, 6, 31), Kind.DISCONNECTED, "by the app"),
            )
        val night =
            groupNights(events, minutes(t(21, 22, 1), t(22, 6, 30)), zone, t(22, 9, 0)).single()
        assertEquals(t(21, 22, 0), night.start)
        assertEquals(t(22, 6, 31), night.end)
        assertEquals(0, night.drops)
        assertEquals(2, night.presses)
        assertEquals(emptyList(), night.gaps)
        assertEquals(emptyList(), night.killedAt)
        assertEquals(false, night.ongoing)
    }

    @Test
    fun `night 1 - four link drops, then the app killed until the morning`() {
        val drops =
            listOf(
                t(21, 21, 40, 0) to 85,
                t(21, 21, 48, 0) to 27,
                t(21, 21, 55, 0) to 358,
                t(21, 22, 4, 53) to 67,
            )
        val events = buildList {
            add(started(t(21, 21, 30)))
            add(ev(t(21, 21, 30, 2), Kind.CONNECTED, "BedJet"))
            add(ev(t(21, 21, 31), Kind.COMMAND, "heat"))
            drops.forEach { (at, s) ->
                add(ev(at, Kind.LOST, "timeout"))
                add(ev(at + s * 1000L, Kind.RECONNECTED, "after $s s"))
            }
            // Logged at the next start, dated by the system's exit record.
            add(ev(t(21, 22, 55, 54), Kind.PROCESS, "exit low memory importance 125: kill"))
            add(started(t(22, 4, 55, 50)))
            add(ev(t(22, 4, 56), Kind.CONNECTED, "BedJet"))
        }
        val samples = minutes(t(21, 21, 31), t(21, 22, 55))
        val night =
            groupNights(events.shuffled(java.util.Random(1)), samples, zone, t(22, 5, 0)).single()
        assertEquals(4, night.drops)
        assertEquals(listOf(t(21, 22, 55, 54)), night.killedAt)
        val link = drops.map { (at, s) -> Gap(at, at + s * 1000L, Cause.LINK) }
        assertEquals(link + Gap(t(21, 22, 55, 54), t(22, 4, 55, 50), Cause.APP), night.gaps)
        assertEquals(1, night.presses)
        assertEquals(true, night.ongoing)
    }

    @Test
    fun `a start without an exit record dates the death by the last thing logged, link gap cut there`() {
        val events =
            listOf(
                started(t(21, 22, 0)),
                ev(t(21, 22, 0, 1), Kind.CONNECTED),
                ev(t(21, 23, 0), Kind.LOST),
                started(t(22, 3, 0)),
                ev(t(22, 3, 0, 2), Kind.CONNECTED),
            )
        val night =
            groupNights(events, listOf(t(21, 22, 59), t(21, 23, 30)), zone, t(22, 12, 0)).single()
        assertEquals(
            listOf(
                Gap(t(21, 23, 0), t(21, 23, 30), Cause.LINK),
                Gap(t(21, 23, 30), t(22, 3, 0), Cause.APP),
            ),
            night.gaps,
        )
        assertEquals(listOf(t(21, 23, 30)), night.killedAt)
    }

    @Test
    fun `an app update or the user stopping it is a gap, not a kill`() {
        val events =
            listOf(
                started(t(21, 22, 0)),
                ev(t(21, 23, 0), Kind.PROCESS, "exit package updated importance 100: stop"),
                started(t(21, 23, 1)),
                ev(t(22, 1, 0), Kind.PROCESS, "exit user stopped importance 125: null"),
                started(t(22, 2, 0)),
            )
        val night = groupNights(events, emptyList(), zone, t(22, 12, 0)).single()
        assertEquals(listOf(Cause.APP, Cause.APP), night.gaps.map { it.cause })
        assertEquals(emptyList(), night.killedAt)
    }

    @Test
    fun `two nights split at noon, newest first, only tonight ongoing`() {
        val events =
            listOf(
                started(t(20, 23, 0)),
                ev(t(20, 23, 0, 1), Kind.CONNECTED),
                ev(t(21, 7, 0), Kind.DISCONNECTED),
                ev(t(21, 11, 59), Kind.COMMAND),
                started(t(21, 12, 1)),
                ev(t(21, 22, 0), Kind.CONNECTED),
            )
        val now = t(21, 22, 10)
        val nights = groupNights(events, emptyList(), zone, now)
        assertEquals(listOf(t(21, 12, 1), t(20, 23, 0)), nights.map { it.start })
        assertEquals(t(21, 11, 59), nights[1].end)
        assertEquals(1, nights[1].presses)
        assertEquals(listOf(true, false), nights.map { it.ongoing })
        // Each night's own process start is just the app being opened, not a kill.
        assertTrue(nights.all { it.killedAt.isEmpty() && it.gaps.isEmpty() })
    }
}
