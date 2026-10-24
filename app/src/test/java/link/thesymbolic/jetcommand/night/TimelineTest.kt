package link.thesymbolic.jetcommand.night

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.NightDetail
import link.thesymbolic.jetcommand.protocol.NightEvent
import link.thesymbolic.jetcommand.protocol.NightEvent.Kind
import link.thesymbolic.jetcommand.protocol.NightSample
import link.thesymbolic.jetcommand.protocol.NightSummary
import link.thesymbolic.jetcommand.protocol.NightSummary.Cause
import link.thesymbolic.jetcommand.protocol.NightSummary.Gap

class TimelineTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val start =
        LocalDateTime.of(2026, 10, 21, 22, 17).atZone(berlin).toInstant().toEpochMilli()

    private fun min(m: Double) = start + (m * 60_000).toLong()

    private fun summary(endMinute: Double, gaps: List<Gap> = emptyList()) =
        NightSummary(start, min(endMinute), false, 0, gaps, 0, emptyList())

    private fun sample(m: Double, mode: Mode = Mode.HEAT, room: Int = 40, air: Int = 47) =
        NightSample(min(m), room, air, target = 44, fanStep = 9, mode = mode)

    @Test
    fun `samples land on whole minutes, one per minute, target only where the mode uses it`() {
        val t =
            timeline(
                NightDetail(
                    summary(4.0),
                    emptyList(),
                    listOf(sample(0.0), sample(0.3), sample(1.6), sample(3.0, Mode.STANDBY)),
                ),
                berlin,
            )
        assertEquals(listOf(0.0 to 20.0, 2.0 to 20.0, 3.0 to 20.0), t.room)
        assertEquals(listOf(0.0 to 23.5, 2.0 to 23.5, 3.0 to 23.5), t.air)
        assertEquals(listOf(0.0 to 22.0, 2.0 to 22.0), t.target)
        assertEquals(listOf(0.0 to 50.0, 2.0 to 50.0, 3.0 to 50.0), t.fan)
        assertEquals(19.0 to 25.0, t.minY to t.maxY)
        assertEquals(4.0, t.maxX)
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0), t.everyMinute)
    }

    @Test
    fun `an empty night still has a one-minute axis and a default temperature range`() {
        val t = timeline(NightDetail(summary(0.0), emptyList(), emptyList()), berlin)
        assertEquals(1.0, t.maxX)
        assertEquals(listOf(0.0, 1.0), t.everyMinute)
        assertEquals(17.0 to 27.0, t.minY to t.maxY)
        assertEquals(emptyList(), t.modes)
    }

    @Test
    fun `mode bands join across short gaps and split on longer ones or a change`() {
        val samples = listOf(0.0, 1.0, 2.0, 5.0, 9.0).map { sample(it) } + sample(10.0, Mode.COOL)
        val t = timeline(NightDetail(summary(10.0), emptyList(), samples), berlin)
        assertEquals(
            listOf(0.0..6.0 to Mode.HEAT, 9.0..10.0 to Mode.HEAT, 10.0..11.0 to Mode.COOL),
            t.modes,
        )
    }

    @Test
    fun `a press dot sits at the last fan level logged up to 5 s after it, one per minute`() {
        val events =
            listOf(
                NightEvent(min(1.0), Kind.COMMAND, "heat"),
                NightEvent(min(3.0), Kind.COMMAND, "fan 50%"),
                NightEvent(min(3.0) + 4_000, Kind.FAN, "50%", 9),
                NightEvent(min(6.0), Kind.COMMAND, "fan 75%"),
                NightEvent(min(6.0) + 6_000, Kind.FAN, "75%", 14),
                NightEvent(min(6.2), Kind.COMMAND, "fan 75%"),
            )
        val t = timeline(NightDetail(summary(8.0), events, emptyList()), berlin)
        assertEquals(listOf(1.0 to 0.0, 3.0 to 50.0, 6.0 to 50.0), t.presses)
    }

    @Test
    fun `gaps become minute spans by cause`() {
        val gaps = listOf(Gap(min(1.0), min(2.0), Cause.LINK), Gap(min(3.0), min(5.4), Cause.APP))
        val t = timeline(NightDetail(summary(8.0, gaps), emptyList(), emptyList()), berlin)
        assertEquals(listOf(1.0..2.0, 3.0..5.0), t.unobserved)
        assertEquals(listOf(1.0..2.0), t.drops)
        assertEquals(listOf(3.0..5.0), t.apps)
    }

    @Test
    fun `the axis aligns to the hour in the zone it is given`() {
        val d = NightDetail(summary(60.0), emptyList(), emptyList())
        listOf("Europe/Berlin" to 17, "America/New_York" to 17, "Asia/Kolkata" to 47).forEach {
            (zone, minute) ->
            assertEquals(minute, timeline(d, ZoneId.of(zone)).startMinuteOfHour, zone)
        }
    }

    @Test
    fun `time ticks - at most five labels, on the hour, never at the left edge`() {
        val t = timeline(NightDetail(summary(0.0), emptyList(), emptyList()), berlin)
        listOf(
                Triple(60.0, 0, TimeTicks(15, 15)),
                Triple(480.0, 17, TimeTicks(120, 43)),
                Triple(480.0, 58, TimeTicks(120, 62)),
                Triple(600.0, 0, TimeTicks(120, 60)),
                Triple(10_000.0, 30, TimeTicks(1440, 90)),
            )
            .forEach { (maxX, minute, ticks) ->
                assertEquals(
                    ticks,
                    timeTicks(t.copy(maxX = maxX, startMinuteOfHour = minute)),
                    "$maxX $minute",
                )
            }
    }

    @Test
    fun `atMinute maps chart x back to an instant`() {
        assertEquals(1_000L + 90_000, atMinute(1_000, 1.5))
        assertEquals(1_000L, atMinute(1_000, 0.0))
    }
}
