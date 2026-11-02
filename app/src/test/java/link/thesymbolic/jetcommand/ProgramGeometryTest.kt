package link.thesymbolic.jetcommand

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import java.time.LocalTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.thesymbolic.jetcommand.protocol.core.Mode
import link.thesymbolic.jetcommand.protocol.core.Step
import link.thesymbolic.jetcommand.protocol.core.Timing

class ProgramGeometryTest {
    private fun step(min: Int) = Step(Mode.COOL, 5, 40, Timing.For(min / 60, min % 60))

    private val spans = timeline(listOf(step(10), step(20)), LocalTime.of(22, 0))

    @Test
    fun `strip widths fill the space and never drop below the minimum`() = runTest {
        checkAll(Arb.list(Arb.int(0..600), 1..12)) { minutes ->
            val avail = 300f
            val widths = stripWidths(minutes, avail, minW = 4f)
            assertTrue(widths.all { it >= 4f }, "$minutes -> $widths")
            // 12 steps at the minimum leave room, so some step always takes up the rest.
            if (minutes.any { it > 0 })
                assertTrue(abs(widths.sum() - avail) < 0.5f, "$minutes -> $widths")
        }
    }

    @Test
    fun `strip widths keep proportions above the minimum`() {
        assertEquals(listOf(100f, 200f), stripWidths(listOf(10, 20), 300f, 4f))
        assertEquals(listOf(4f, 296f), stripWidths(listOf(0, 20), 300f, 4f))
    }

    @Test
    fun `now-line and chart cursor split at step ends differently`() {
        assertEquals(0, spanIndexAt(spans, 9.9))
        assertEquals(1, spanIndexAt(spans, 10.0))
        assertEquals(1, spanIndexAt(spans, 99.0))
        assertEquals(0, spanAt(spans, 10.0)?.index)
        assertEquals(1, spanAt(spans, 10.5)?.index)
        assertEquals(1, spanAt(spans, 99.0)?.index)
        assertNull(spanAt(emptyList(), 1.0))
    }

    @Test
    fun `chart caption names the step under the finger`() {
        val start = LocalTime.of(22, 0)
        assertEquals(
            "Touch and drag across the chart to read each step.",
            chartCaption(spans, start, null),
        )
        assertEquals(
            "22:15 · step 2 of 2 · Cool · fan 30 % · 20°C",
            chartCaption(spans, start, 15.0),
        )
        assertEquals(
            "Touch and drag across the chart to read each step.",
            chartCaption(emptyList(), start, 3.0),
        )
    }

    @Test
    fun `drag value maps bottom to 0 and top to the max, clamped`() {
        assertEquals(0, dragValue(200f, 200f, 20))
        assertEquals(19, dragValue(0f, 200f, 20))
        assertEquals(10, dragValue(100f, 200f, 21))
        assertEquals(0, dragValue(250f, 200f, 20))
        assertEquals(19, dragValue(-30f, 200f, 20))
    }
}
