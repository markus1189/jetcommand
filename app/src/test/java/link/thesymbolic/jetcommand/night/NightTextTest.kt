package link.thesymbolic.jetcommand.night

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.thesymbolic.jetcommand.durationMs

class NightTextTest {
    @Test
    fun `duration switches units at two minutes and one hour`() {
        assertEquals("119 s", durationMs(119_000))
        assertEquals("2 min", durationMs(120_000))
        assertEquals("1 h", durationMs(3_600_000))
        assertEquals("1 h 30 m", durationMs(5_400_000))
    }

    @Test
    fun `duration never goes empty or negative`() = runTest {
        checkAll(Arb.long(0L..7 * 24 * 3_600_000L)) { ms ->
            val text = durationMs(ms)
            assertTrue(text.isNotBlank() && !text.startsWith("-"), "$ms -> $text")
        }
    }
}
