package link.thesymbolic.jetcommand

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoConnectTest {
    private val unit = Source.Device("AA:BB", "BedJet 3")

    @Test
    fun `connects only to a remembered unit, untried, unconnected, with permission`() {
        val flags = listOf(false, true)
        for (tried in flags) for (connected in flags) for (permission in flags) {
            val expected = unit.takeIf { !tried && !connected && permission }
            assertEquals(expected, autoConnectTarget(unit, tried, connected, permission))
            assertEquals(null, autoConnectTarget(null, tried, connected, permission))
        }
    }
}
