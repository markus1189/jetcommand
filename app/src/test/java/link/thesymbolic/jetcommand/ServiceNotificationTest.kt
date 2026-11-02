package link.thesymbolic.jetcommand

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import link.thesymbolic.jetcommand.protocol.core.LinkState
import link.thesymbolic.jetcommand.protocol.core.Mode
import link.thesymbolic.jetcommand.protocol.core.StatusUpdate

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
                    ServiceNotification("Heat · 30.5°C → 35°C", "Ready", true, 1_000L + 5_430_000),
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
                assertEquals(expected, serviceNotification("BJ", link, u, 1_000L), "$link")
            }
    }

    @Test
    fun `a timer end that wobbles by a second is the same notification`() {
        val n = serviceNotification("BJ", LinkState.Ready, update, 0)
        val later = { ms: Long, u: StatusUpdate ->
            serviceNotification("BJ", LinkState.Ready, u, ms)
        }
        val ticked = update.copy(status = update.status.copy(remainingSeconds = 5_429))
        assertTrue(later(1_900, ticked).sameAs(n))
        assertFalse(later(3_000, update).sameAs(n))
        val off = update.copy(status = update.status.copy(remainingSeconds = 0))
        assertEquals(null, later(0, off).endsAt)
        assertFalse(later(0, off).sameAs(n))
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
