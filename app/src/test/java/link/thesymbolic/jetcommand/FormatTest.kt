package link.thesymbolic.jetcommand

import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import link.thesymbolic.jetcommand.night.NightText
import link.thesymbolic.jetcommand.night.chips
import link.thesymbolic.jetcommand.now.runningView
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.NightSummary
import link.thesymbolic.jetcommand.protocol.Program
import link.thesymbolic.jetcommand.protocol.Step
import link.thesymbolic.jetcommand.protocol.Timing

/** Every caller of the shared formatters, pinned to the exact text it printed before the merge. */
class FormatTest {
    private fun cool(timing: Timing) = Step(Mode.COOL, 0, 40, timing)

    private fun check(cases: List<Pair<String, String>>) = cases.forEach { (actual, expected) ->
        assertEquals(expected, actual)
    }

    @Test
    fun `wall clock is zero-padded HH-mm`() =
        check(
            listOf(
                clockAt(LocalTime.of(6, 0), 0) to "06:00",
                clockAt(LocalTime.of(23, 30), 45) to "00:15",
                clockAt(LocalTime.of(7, 5), 1440) to "07:05",
                hhmm(3_720_000, ZoneOffset.UTC) to "01:02",
                hhmm(0, ZoneOffset.ofHours(2)) to "02:00",
                NightText(ZoneOffset.UTC).time(3_723_000) to "01:02",
                NightText(ZoneOffset.UTC).timeSec(3_723_000) to "01:02:03",
                NightText(ZoneOffset.ofHours(-1)).timeSec(0) to "23:00:00",
                stepCell(cool(Timing.Until(6, 5))).second to "fan 5 % · 20°C · until 06:05",
                stepCell(cool(Timing.Until(23, 0))).second to "fan 5 % · 20°C · until 23:00",
                programMeta(null, listOf(cool(Timing.Until(6, 30))), LocalTime.of(23, 0)) to
                    "1 step · until 06:30",
            )
        )

    @Test
    fun `the running program's next step is a wall clock time`() {
        val p = Program("P", listOf(cool(Timing.For(0, 30)), cool(Timing.For(1, 5))))
        check(
            listOf(
                runningView(p, 1, 600, LocalTime.of(23, 55))!!.next to "next 00:05 · cool 1 h 05",
                runningView(p, 2, 600, LocalTime.of(9, 58))!!.next to "ends 10:08",
            )
        )
    }

    @Test
    fun `remaining time has unpadded hours`() =
        check(
            listOf(
                hoursMinutes(0) to "0:00",
                hoursMinutes(3_599) to "0:59",
                hoursMinutes(36_000) to "10:00",
                statusLines(status(remainingSeconds = 9), null)[1] to
                    "Remaining 0:00:09   ambient 22°C",
                statusLines(status(remainingSeconds = 36_067), null)[1] to
                    "Remaining 10:01:07   ambient 22°C",
                stepText(cool(Timing.Until(6, 5))) to "Cool 5% 20°C until 6:05",
                stepText(cool(Timing.For(0, 5))) to "Cool 5% 20°C 0:05",
            )
        )

    @Test
    fun `step durations in minutes`() =
        check(
            listOf(
                durationMin(0) to "0 min",
                durationMin(59) to "59 min",
                durationMin(60) to "1 h 00",
                durationMin(65) to "1 h 05",
                durationMin(600) to "10 h 00",
                stepCell(cool(Timing.For(1, 5))).second to "fan 5 % · 20°C · 1 h 05",
                programMeta(null, listOf(cool(Timing.For(0, 30))), LocalTime.of(6, 0)) to
                    "1 step · 30 min",
            )
        )

    @Test
    fun `elapsed durations in milliseconds`() =
        check(
            listOf(
                durationMs(0) to "0 s",
                durationMs(89_999) to "90 s",
                durationMs(119_000) to "119 s",
                durationMs(120_000) to "2 min",
                durationMs(3_570_000) to "1 h",
                durationMs(3_600_000) to "1 h",
                durationMs(5_400_000) to "1 h 30 m",
            )
        )

    @Test
    fun `plurals`() {
        fun presses(n: Int) =
            chips(NightSummary(0, 1, false, 0, emptyList(), n, emptyList()), NightText())
                .last()
                .first
        check(
            listOf(
                plural(0, "step") to "0 steps",
                plural(1, "edit") to "1 edit",
                plural(2, "program") to "2 programs",
                presses(1) to "1 press",
                presses(2) to "2 presses",
            )
        )
    }
}
