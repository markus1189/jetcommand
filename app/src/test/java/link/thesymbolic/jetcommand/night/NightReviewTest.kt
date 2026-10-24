package link.thesymbolic.jetcommand.night

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import link.thesymbolic.jetcommand.protocol.NightDetail
import link.thesymbolic.jetcommand.protocol.NightEvent
import link.thesymbolic.jetcommand.protocol.NightEvent.Kind
import link.thesymbolic.jetcommand.protocol.NightSummary
import link.thesymbolic.jetcommand.protocol.NightSummary.Cause
import link.thesymbolic.jetcommand.protocol.NightSummary.Gap

class NightReviewTest {
    private val text = NightText(ZoneOffset.UTC)
    private val h = 3_600_000L

    private fun detail(events: List<NightEvent>, killedAt: List<Long>, gaps: List<Gap>) =
        NightDetail(NightSummary(0, 8 * h, false, 0, gaps, 0, killedAt), events, emptyList())

    @Test
    fun `stat tiles`() {
        listOf(
                Stats(h, 2 * h, 0, null, 0, "", null) to
                    listOf(
                        "1 h" to "watched of 2 h",
                        "0" to "drops",
                        "0" to "presses",
                        "--" to "room vs target, average",
                    ),
                Stats(h, 2 * h, 2, 90_000, 3, "2 fan, 1 temp", 1.04) to
                    listOf(
                        "1 h" to "watched of 2 h",
                        "2" to "drops · longest 90 s",
                        "3" to "presses · 2 fan, 1 temp",
                        "±1.0 °C" to "room vs target, average",
                    ),
            )
            .forEach { (stats, tiles) -> assertEquals(tiles, statTiles(stats)) }
    }

    @Test
    fun `a kill's callout names the description's last parenthesis, else the reason`() {
        listOf(
                "exit signaled importance 125: kill (oom) (signal 9)" to "signal 9",
                "exit low memory importance 125: null" to "low memory",
            )
            .forEach { (line, why) ->
                val d = detail(listOf(NightEvent(h, Kind.PROCESS, line)), listOf(h), emptyList())
                assertEquals(
                    listOf("The app was killed at 01:00" to "Android ended the app ($why)."),
                    killedCallouts(d, text),
                )
            }
    }

    @Test
    fun `an exit that was not a kill is a plain row with its gap, notes are not shown`() {
        val events =
            listOf(
                NightEvent(h, Kind.PROCESS, "trim memory 15"),
                NightEvent(2 * h, Kind.PROCESS, "exit package updated importance 100: stop"),
                NightEvent(3 * h, Kind.PROCESS, "process started"),
                NightEvent(3 * h + 1_000, Kind.CONNECTED, "BedJet"),
            )
        val d = detail(events, emptyList(), listOf(Gap(2 * h, 3 * h, Cause.APP)))
        assertEquals(
            listOf(
                EventRow(2 * h, "App ended · package updated"),
                EventRow(null, "1 h unobserved", muted = true),
                EventRow(3 * h + 1_000, "App started · Connected"),
            ),
            importantRows(d, text),
        )
    }
}
