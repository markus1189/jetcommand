package link.thesymbolic.jetcommand

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import link.thesymbolic.jetcommand.protocol.Characteristic
import link.thesymbolic.jetcommand.protocol.LinkState
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.NightEvent
import link.thesymbolic.jetcommand.protocol.Notification
import link.thesymbolic.jetcommand.protocol.Step
import link.thesymbolic.jetcommand.protocol.Timing

class DebugTextTest {
    @Test
    fun `link line names the reconnect attempt and a give-up`() {
        listOf(
                LinkState.Ready to "Link: Ready",
                LinkState.Connecting() to "Link: Connecting",
                LinkState.Connecting(3) to "Link: Reconnecting (attempt 3)",
                LinkState.Disconnected() to "Link: Disconnected",
                LinkState.Disconnected(gaveUp = true) to "Link: Disconnected (gave up)",
            )
            .forEach { (link, text) -> assertEquals(text, debugLinkLine(link)) }
    }

    @Test
    fun `connection line shows the short uuid of the reply characteristic`() {
        assertEquals("MTU: -   Replies from: -", connectionLine(null, null))
        val ch = Characteristic.REPLY
        assertEquals(
            "MTU: 247   Replies from: ${ch.uuid.substring(4, 8)}",
            connectionLine(247, ch),
        )
    }

    @Test
    fun `status lines without a status`() {
        assertEquals(listOf("Status: -"), statusLines(null, 1_000))
    }

    @Test
    fun `status lines pad the remainder and name a notification`() {
        val st =
            status(
                mode = Mode.COOL,
                remainingSeconds = 2 * 3600 + 5 * 60 + 7,
                actualTemp = 41,
                targetTemp = 40,
                fanStep = 9,
                ambientTemp = 44,
                programStep = 2,
                notification = Notification.CleanFilter,
            )
        assertEquals(
            listOf(
                "Mode Cool   actual 20.5°C   target 20°C   fan 50%",
                "Remaining 2:05:07   ambient 22°C",
                "Program step 2   extra 1.5 s old\nNotification 1: " +
                    Notification.CleanFilter.text,
            ),
            statusLines(st, 1_500),
        )
    }

    @Test
    fun `status lines before any extra read`() {
        assertEquals(
            "Program step -   extra never read",
            statusLines(status(), null).last(),
        )
    }

    @Test
    fun `an extra newer than the screen's clock tick is 0 s old, not negative`() {
        assertEquals("Program step -   extra 0.0 s old", statusLines(status(), -200).last())
    }

    @Test
    fun `program names mark empty slots`() {
        assertEquals("A | - | C", programNamesText(listOf("A", null, "C")))
        assertEquals("", programNamesText(emptyList()))
    }

    @Test
    fun `slot text joins steps and names an empty slot`() {
        val heat = Step(Mode.HEAT, 19, 70, Timing.For(1, 5))
        val cool = Step(Mode.COOL, 0, 40, Timing.Until(6, 30))
        assertEquals("(empty)", slotText(emptyList()))
        assertEquals("Heat 100% 35°C 1:05; Cool 5% 20°C until 6:30", slotText(listOf(heat, cool)))
    }

    @Test
    fun `results are sorted by label`() {
        assertEquals(
            listOf("Firmware: 1.2", "Name: BJ"),
            resultLines(mapOf("Name" to "BJ", "Firmware" to "1.2")),
        )
    }

    @Test
    fun `night line is wall-clock time in the given zone`() {
        val e = NightEvent(at = 3_723_000, kind = NightEvent.Kind.LOST, detail = "gatt 8")
        val day = LocalDate.of(1970, 1, 1)
        assertEquals("01:02:03 LOST gatt 8", nightLine(e, ZoneOffset.UTC, day))
        assertEquals("03:02:03 LOST gatt 8", nightLine(e, ZoneOffset.ofHours(2), day))
    }

    @Test
    fun `night line from another day carries its date, in the zone's calendar`() {
        val e = NightEvent(at = 3_723_000, kind = NightEvent.Kind.LOST, detail = "gatt 8")
        val later = LocalDate.of(1970, 1, 3)
        assertEquals("01-01 01:02:03 LOST gatt 8", nightLine(e, ZoneOffset.UTC, later))
        // 22:02 on Dec 31 at UTC-3: the zone decides the date, not UTC.
        assertEquals(
            "12-31 22:02:03 LOST gatt 8",
            nightLine(e, ZoneOffset.ofHours(-3), LocalDate.of(1970, 1, 1)),
        )
    }
}
