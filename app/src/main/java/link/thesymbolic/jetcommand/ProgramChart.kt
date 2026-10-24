package link.thesymbolic.jetcommand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.fanPercent

/** The step under [minute]; past the end, the last one. */
fun spanAt(spans: List<Span>, minute: Double): Span? =
    spans.firstOrNull { minute <= it.to } ?: spans.lastOrNull()

fun chartCaption(spans: List<Span>, start: LocalTime, minute: Double?): String {
    val at = minute?.let { spanAt(spans, it) }
    return if (minute == null || at == null) "Touch and drag across the chart to read each step."
    else
        "${clockAt(start, minute.toInt())} · step ${at.index + 1} of ${spans.size} · ${at.step.mode.label} · ${stepDetail(at.step)}"
}

/**
 * The program's profile: mode band, target °C and fan % as step lines over one time axis. The °C
 * line lifts the pen where a step has no set point (turbo, wait), the fan line where the air is
 * off. Touch and drag to read a step.
 */
@Composable
fun ProgramChart(spans: List<Span>, start: LocalTime, modifier: Modifier = Modifier) {
    var cursor by remember { mutableStateOf<Double?>(null) }
    val total = spans.lastOrNull()?.to?.coerceAtLeast(1) ?: 1
    val at = cursor?.let { spanAt(spans, it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            chartCaption(spans, start, cursor),
            color = if (cursor == null) Muted else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 36.dp),
        )
        val measurer = rememberTextMeasurer()
        Canvas(
            Modifier.fillMaxWidth().height(252.dp).pointerInput(spans) {
                val left = 21.dp.toPx()
                fun minuteAt(x: Float) =
                    ((x - left) / (size.width - left)).coerceIn(0f, 1f) * total.toDouble()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    cursor = minuteAt(down.position.x)
                    do {
                        val c = awaitPointerEvent().changes.firstOrNull()
                        val pressed = c?.pressed == true
                        if (c != null && pressed) {
                            // Only horizontal drags scrub; the column's vertical scroll keeps the
                            // rest.
                            val d = c.position - c.previousPosition
                            if (kotlin.math.abs(d.x) >= kotlin.math.abs(d.y)) c.consume()
                            cursor = minuteAt(c.position.x)
                        }
                    } while (pressed)
                    cursor = null
                }
            }
        ) {
            drawProfile(spans, start, total, at, cursor, measurer)
        }
        Row(
            Modifier.padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("— target", color = TargetLine, fontSize = 12.sp)
            Text("— fan", color = Sage, fontSize = 12.sp)
            Text("gap = no setpoint", color = Muted, fontSize = 12.sp)
        }
    }
}

private fun DrawScope.drawProfile(
    spans: List<Span>,
    start: LocalTime,
    total: Int,
    at: Span?,
    cursor: Double?,
    tm: TextMeasurer,
) {
    // Same geometry as the design's 358×252 viewBox, scaled vertically by dp.
    val u = 1.dp.toPx()
    val left = 21 * u
    val x = { m: Number -> left + (size.width - left) * m.toFloat() / total }
    fun text(s: String, at: Offset, color: Color, sp: Int) =
        drawText(tm, s, at, TextStyle(color = color, fontSize = sp.sp))

    text("Mode", Offset(0f, 0f), Muted, 11)
    spans.forEach {
        drawRect(
            modeColor(it.step.mode),
            Offset(x(it.from), 18 * u),
            Size(x(it.to) - x(it.from), 12 * u),
        )
    }

    // °C panel: 18–34 °C over y 134–62.
    text("°C", Offset(0f, 38 * u), Muted, 11)
    val tempY = { c: Double -> (62 + (34 - c) * 4.5).toFloat() * u }
    listOf(34, 26, 18).forEach { c ->
        drawLine(
            ChartGrid,
            Offset(left, tempY(c.toDouble())),
            Offset(size.width, tempY(c.toDouble())),
            u,
        )
        text("$c", Offset(0f, tempY(c.toDouble()) - 6 * u), TickLabel, 9)
    }
    stepLine(spans, x, TargetLine, u) { s -> targetC(s.step)?.coerceIn(16.0, 36.0)?.let(tempY) }

    // Fan panel: 20–100 % over y 210–170.
    text("Fan %", Offset(0f, 148 * u), Muted, 11)
    val fanY = { p: Int -> (210 - (p - 20) * 0.5f) * u }
    drawLine(ChartGrid, Offset(left, fanY(100)), Offset(size.width, fanY(100)), u)
    drawLine(ChartGrid, Offset(left, fanY(20)), Offset(size.width, fanY(20)), u)
    text("100", Offset(size.width - 18 * u, fanY(100) - 13 * u), TickLabel, 9)
    text("20", Offset(size.width - 14 * u, fanY(20) - 15 * u), TickLabel, 9)
    stepLine(spans, x, Sage, u) { s ->
        if (s.step.mode == Mode.WAIT || s.step.mode == Mode.STANDBY) null
        else fanY(fanPercent(s.step.fan))
    }

    drawTimeAxis(start, total, left, u, x, tm)

    if (cursor != null && at != null) {
        val cx = x(cursor)
        drawRect(
            ScrubLine,
            Offset(x(at.from), 16 * u),
            Size(x(at.to) - x(at.from), 16 * u),
            style = Stroke(1.5f * u),
        )
        drawLine(ScrubLine, Offset(cx, 14 * u), Offset(cx, 220 * u), u)
        targetC(at.step)?.let {
            drawCircle(TargetLine, 4 * u, Offset(cx, tempY(it.coerceIn(16.0, 36.0))))
        }
        if (at.step.mode != Mode.WAIT && at.step.mode != Mode.STANDBY)
            drawCircle(Sage, 4 * u, Offset(cx, fanY(fanPercent(at.step.fan))))
    }
}

/**
 * Joins consecutive steps with a riser; a null value lifts the pen until the next step that has
 * one.
 */
private fun DrawScope.stepLine(
    spans: List<Span>,
    x: (Number) -> Float,
    color: Color,
    u: Float,
    y: (Span) -> Float?,
) {
    var prev: Offset? = null
    spans.forEach { s ->
        val v =
            y(s)
                ?: run {
                    prev = null
                    return@forEach
                }
        val a = Offset(x(s.from), v)
        prev?.let { drawLine(color, it, a, 2 * u) }
        val b = Offset(x(s.to), v)
        drawLine(color, a, b, 2 * u)
        prev = b
    }
}

/** Whole hours, every second one when the program is long. */
private fun DrawScope.drawTimeAxis(
    start: LocalTime,
    total: Int,
    left: Float,
    u: Float,
    x: (Number) -> Float,
    tm: TextMeasurer,
) {
    drawLine(AxisLine, Offset(left, 220 * u), Offset(size.width, 220 * u), u)
    val every =
        when {
            total > 600 -> 180
            total > 300 -> 120
            else -> 60
        }
    var m = (60 - start.minute) % 60
    while (m <= total - 20) {
        drawText(
            tm,
            clockAt(start, m),
            Offset(x(m) - 14 * u, 226 * u),
            TextStyle(color = Muted, fontSize = 10.sp),
        )
        m += every
    }
}
