package link.thesymbolic.jetcommand.protocol

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.thesymbolic.jetcommand.protocol.NightEvent.Kind
import link.thesymbolic.jetcommand.protocol.NightSummary.Cause
import link.thesymbolic.jetcommand.protocol.NightSummary.Gap

class NightGroupingTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun t(day: Int, h: Int, m: Int, s: Int = 0) =
        LocalDateTime.of(2026, 10, day, h, m, s).atZone(zone).toInstant().toEpochMilli()

    private fun ev(at: Long, kind: Kind, detail: String = "") = NightEvent(at, kind, detail)

    private fun started(at: Long) = NightEvent.process(at, ProcessEvent.Started)

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

    @Test
    fun `nothing logged summarizes to null`() {
        assertNull(summarize(emptyList(), emptyList(), ongoing = false))
    }

    @Test
    fun `on a DST change day the night still ends at local noon`() {
        // Berlin falls back on 25 Oct 2026 and springs forward on 29 Mar 2026.
        assertEquals(LocalDate.of(2026, 10, 24), nightOf(t(25, 11, 30), zone))
        assertEquals(LocalDate.of(2026, 10, 25), nightOf(t(25, 12, 0), zone))
        val spring = LocalDateTime.of(2026, 3, 29, 12, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 3, 29), nightOf(spring, zone))
    }

    private val zones = Arb.element(ZoneId.of("Europe/Berlin"), ZoneId.of("America/New_York"))

    /** Instants of 2026, weighted towards the days around each zone's DST changes. */
    private fun instants(zone: ZoneId): Arb<Long> {
        val rules = zone.rules
        val year = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant()
        val changes =
            generateSequence(rules.nextTransition(year)) { rules.nextTransition(it.instant) }
                .take(2)
                .map { it.instant.toEpochMilli() }
                .toList()
        val day = 24 * 3_600_000L
        return Arb.choice(
            Arb.long(year.toEpochMilli(), year.toEpochMilli() + 366 * day),
            *changes.map { Arb.long(it - 2 * day, it + 2 * day) }.toTypedArray(),
        )
    }

    @Test
    fun `every instant lies in the window of its night, across DST changes`() = runTest {
        checkAll(zones.flatMap { z -> instants(z).map { z to it } }) { (z, at) ->
            assertTrue(
                at in nightWindow(nightOf(at, z), z),
                "$z ${java.time.Instant.ofEpochMilli(at)}",
            )
        }
    }

    @Test
    fun `groupNights puts every event in exactly one night and loses none`() = runTest {
        val events = zones.flatMap { z ->
            val event =
                Arb.bind(
                    instants(z),
                    Arb.enum<Kind>(),
                    Arb.element("", "process started", "exit low memory importance 1: x"),
                ) { at, kind, detail ->
                    NightEvent(at, kind, detail)
                }
            Arb.list(event, 0..40).map { z to it }
        }
        checkAll(events) { (z, evs) ->
            val nights = groupNights(evs, emptyList(), z, now = 0)
            evs.forEach { e ->
                assertEquals(1, nights.count { e.at in it.start..it.end }, "$e")
            }
            assertEquals(evs.map { nightOf(it.at, z) }.distinct().size, nights.size)
            assertEquals(evs.count { it.kind == Kind.LOST }, nights.sumOf { it.drops })
            assertEquals(evs.count { it.kind == Kind.COMMAND }, nights.sumOf { it.presses })
        }
    }
}
