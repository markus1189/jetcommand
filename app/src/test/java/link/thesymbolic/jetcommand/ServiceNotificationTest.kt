package link.thesymbolic.jetcommand

import kotlin.test.Test
import kotlin.test.assertEquals
import link.thesymbolic.jetcommand.protocol.LinkState
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.StatusUpdate

class ServiceNotificationTest {
    private val update =
        StatusUpdate(
            status(mode = Mode.HEAT, actualTemp = 61, targetTemp = 70, remainingSeconds = 5_430),
            extraFresh = false,
        )

    @Test
    fun `notification shows the status only while ready`() {
        listOf(
                Triple(
                    LinkState.Ready,
                    update,
                    ServiceNotification("Heat · 30.5°C → 35°C", "1:30 left · Ready", true),
                ),
                Triple(LinkState.Ready, null, ServiceNotification("BJ", "Ready")),
                Triple(
                    LinkState.Connecting(1),
                    update,
                    ServiceNotification("BJ", "Reconnecting (1)"),
                ),
                Triple(LinkState.Disconnected(), update, ServiceNotification("BJ", "Disconnected")),
            )
            .forEach { (link, u, expected) ->
                assertEquals(expected, serviceNotification("BJ", link, u), "$link")
            }
    }

    @Test
    fun `link text`() {
        listOf(
                LinkState.Ready to "Ready",
                LinkState.Connecting() to "Connecting",
                LinkState.Connecting(4) to "Reconnecting (4)",
                LinkState.Disconnected() to "Disconnected",
                LinkState.Disconnected(gaveUp = true) to "Gave up",
            )
            .forEach { (link, text) -> assertEquals(text, linkText(link)) }
    }
}
