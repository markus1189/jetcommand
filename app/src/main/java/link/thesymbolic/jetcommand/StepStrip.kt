package link.thesymbolic.jetcommand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.Step
import link.thesymbolic.jetcommand.protocol.fanPercent
import link.thesymbolic.jetcommand.protocol.formatTemp

/**
 * A clock-time step ends at its wall-clock time, so the layout needs the start; the unit starts at
 * phone time.
 */
fun timeline(steps: List<Step>, start: LocalTime): List<Span> {
    var t = 0
    val startMin = start.hour * 60 + start.minute
    return steps.mapIndexed { i, s ->
        val len =
            if (s.clock)
                Math.floorMod(s.totalMinutes - (startMin + t), 1440).takeIf { it != 0 } ?: 1440
            else s.totalMinutes
        Span(i, s, t, t + len).also { t += len }
    }
}

fun clockAt(start: LocalTime, minutes: Int): String =
    start.plusMinutes(minutes.toLong()).let { "%02d:%02d".format(it.hour, it.minute) }

fun duration(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "%d h %02d".format(minutes / 60, minutes % 60)

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

private val WaitStub = Color(0xFF4A515C)

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
        val total = spans.sumOf { it.minutes }.coerceAtLeast(1)
        val avail = size.width - gap * (spans.size - 1)
        // Short steps (turbo, 3 min) still need to be seen: give them minW and scale the rest into
        // what's left.
        val raw = spans.map { avail * it.minutes / total }
        val small = raw.count { it < minW }
        val bigSum = raw.filter { it >= minW }.sum().coerceAtLeast(1f)
        val scale = (avail - small * minW) / bigSum
        val widths = raw.map { if (it < minW) minW else it * scale }
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
            val i = spans.indexOfFirst { m < it.to }.takeIf { it >= 0 } ?: spans.lastIndex
            val s = spans[i]
            val nx =
                xs[i] +
                    widths[i] *
                        ((m - s.from) / s.minutes.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
            drawLine(Color(0xFFE8EAED), Offset(nx, 0f), Offset(nx, size.height), 2.dp.toPx())
        }
    }
}
