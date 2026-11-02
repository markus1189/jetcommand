package link.thesymbolic.jetcommand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import link.thesymbolic.jetcommand.protocol.core.Mode
import link.thesymbolic.jetcommand.protocol.core.Step
import link.thesymbolic.jetcommand.protocol.core.Timing
import link.thesymbolic.jetcommand.protocol.core.fanPercent
import link.thesymbolic.jetcommand.protocol.core.formatTemp

/**
 * A clock-time step ends at its wall-clock time, so the layout needs the start; the unit starts at
 * phone time.
 */
fun timeline(steps: List<Step>, start: LocalTime): List<Span> {
    var t = 0
    val startMin = start.hour * 60 + start.minute
    return steps.mapIndexed { i, s ->
        val len =
            when (val timing = s.timing) {
                is Timing.Until ->
                    Math.floorMod(timing.minuteOfDay - (startMin + t), 1440).takeIf { it != 0 }
                        ?: 1440
                is Timing.For -> timing.totalMinutes
            }
        Span(i, s, t, t + len).also { t += len }
    }
}

/**
 * The step's set point in °C, null where it has none (turbo runs at a fixed 43 °C, wait has no
 * air).
 */
fun targetC(step: Step): Double? =
    when {
        step.mode == Mode.WAIT || step.mode == Mode.STANDBY -> null
        step.mode == Mode.TURBO -> null
        else -> step.temp / 2.0
    }

fun stepDetail(step: Step): String =
    when (step.mode) {
        Mode.WAIT,
        Mode.STANDBY -> "fan off"
        Mode.TURBO -> "fan ${fanPercent(step.fan)} %"
        else -> "fan ${fanPercent(step.fan)} % · ${formatTemp(step.temp)}"
    }

/**
 * Widths proportional to [minutes] filling [avail]; short steps (turbo, 3 min) still need to be
 * seen, so they get [minW] and the rest scale into what's left.
 */
fun stripWidths(minutes: List<Int>, avail: Float, minW: Float): List<Float> {
    val total = minutes.sum().coerceAtLeast(1)
    val raw = minutes.map { avail * it / total }
    var small = raw.map { it < minW }
    // Scaling the rest down can push another step under minW: repeat until no new one does.
    while (true) {
        val bigSum = raw.filterIndexed { i, _ -> !small[i] }.sum().coerceAtLeast(1f)
        val scale = (avail - small.count { it } * minW) / bigSum
        val next = raw.mapIndexed { i, r -> small[i] || r * scale < minW }
        if (next == small) return raw.mapIndexed { i, r -> if (small[i]) minW else r * scale }
        small = next
    }
}

/** The span [minute] falls in; past the end, the last. [spans] must not be empty. */
fun spanIndexAt(spans: List<Span>, minute: Double): Int =
    spans.indexOfFirst { minute < it.to }.takeIf { it >= 0 } ?: spans.lastIndex

/**
 * The one step strip (library rows, detail, Now): width = duration, height = target °C over 19–43,
 * a wait is a low grey stub. [nowMinute] draws the now-line, minutes since the program's start.
 */
@Composable
fun StepStrip(
    spans: List<Span>,
    modifier: Modifier = Modifier,
    height: Dp = 24.dp,
    nowMinute: Double? = null,
) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (spans.isEmpty()) return@Canvas
        val gap = 2.dp.toPx()
        val minW = 4.dp.toPx()
        val widths =
            stripWidths(spans.map { it.minutes }, size.width - gap * (spans.size - 1), minW)
        val minH = 6.dp.toPx()
        val r = CornerRadius(3.dp.toPx())
        var x = 0f
        val xs = ArrayList<Float>(spans.size)
        spans.forEachIndexed { i, s ->
            xs += x
            val w = widths[i]
            val c = if (s.step.mode == Mode.TURBO) 43.0 else targetC(s.step)
            val h =
                if (c == null) minH * 0.8f
                else minH + (size.height - minH) * ((c - 19) / 24).toFloat().coerceIn(0f, 1f)
            val color = if (c == null) WaitStub else modeColor(s.step.mode)
            drawRoundRect(color, Offset(x, size.height - h), Size(w, h), r)
            x += w + gap
        }
        nowMinute?.let { m ->
            val i = spanIndexAt(spans, m)
            val s = spans[i]
            val nx =
                xs[i] +
                    widths[i] *
                        ((m - s.from) / s.minutes.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
            drawLine(NowLine, Offset(nx, 0f), Offset(nx, size.height), 2.dp.toPx())
        }
    }
}
