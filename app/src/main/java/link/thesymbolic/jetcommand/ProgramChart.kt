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

private val TargetLine = Color(0xFF8FB4D9)
private val FanLine = Color(0xFF9AB0A8)
private val Grid = Color(0xFF1A1F25)
private val Tick = Color(0xFF6B737D)
private val Label = Color(0xFF9AA2AB)
private val AxisColor = Color(0xFF3A424B)
private val ScrubColor = Color(0xFFCFE2F5)

/**
 * The program's profile: mode band, target °C and fan % as step lines over one time axis. The °C
 * line lifts the pen where a step has no set point (turbo, wait), the fan line where the air is
 * off. Touch and drag to read a step.
 */
@Composable
fun ProgramChart(spans: List<Span>, start: LocalTime, modifier: Modifier = Modifier) {
    var cursor by remember { mutableStateOf<Double?>(null) }
    val total = spans.lastOrNull()?.to?.coerceAtLeast(1) ?: 1
    val at = cursor?.let { m -> spans.firstOrNull { m <= it.to } ?: spans.last() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (cursor == null || at == null) "Touch and drag across the chart to read each step."
            else
                "${clockAt(start, cursor!!.toInt())} · step ${at.index + 1} of ${spans.size} · ${at.step.mode.label} · ${stepDetail(at.step)}",
            color = if (cursor == null) Label else MaterialTheme.colorScheme.onSurface,
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
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        // Only horizontal drags scrub; the column's vertical scroll keeps the rest.
                        val d = c.position - c.previousPosition
                        if (kotlin.math.abs(d.x) >= kotlin.math.abs(d.y)) c.consume()
                        cursor = minuteAt(c.position.x)
                    }
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
            Text("— fan", color = FanLine, fontSize = 12.sp)
            Text("gap = no setpoint", color = Label, fontSize = 12.sp)
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

    text("Mode", Offset(0f, 0f), Label, 11)
    spans.forEach {
        drawRect(
            modeColor(it.step.mode),
            Offset(x(it.from), 18 * u),
            Size(x(it.to) - x(it.from), 12 * u),
        )
    }

    // °C panel: 18–34 °C over y 134–62.
    text("°C", Offset(0f, 38 * u), Label, 11)
    val tempY = { c: Double -> (62 + (34 - c) * 4.5).toFloat() * u }
    listOf(34, 26, 18).forEach { c ->
        drawLine(
            Grid,
            Offset(left, tempY(c.toDouble())),
            Offset(size.width, tempY(c.toDouble())),
            u,
        )
        text("$c", Offset(0f, tempY(c.toDouble()) - 6 * u), Tick, 9)
    }
    stepLine(spans, x, TargetLine, u) { s -> targetC(s.step)?.coerceIn(16.0, 36.0)?.let(tempY) }

    // Fan panel: 20–100 % over y 210–170.
    text("Fan %", Offset(0f, 148 * u), Label, 11)
    val fanY = { p: Int -> (210 - (p - 20) * 0.5f) * u }
    drawLine(Grid, Offset(left, fanY(100)), Offset(size.width, fanY(100)), u)
    drawLine(Grid, Offset(left, fanY(20)), Offset(size.width, fanY(20)), u)
    text("100", Offset(size.width - 18 * u, fanY(100) - 13 * u), Tick, 9)
    text("20", Offset(size.width - 14 * u, fanY(20) - 15 * u), Tick, 9)
    stepLine(spans, x, FanLine, u) { s ->
        if (s.step.mode == Mode.WAIT || s.step.mode == Mode.STANDBY) null
        else fanY(fanPercent(s.step.fan))
    }

    // Time axis: whole hours, every second one when the program is long.
    drawLine(AxisColor, Offset(left, 220 * u), Offset(size.width, 220 * u), u)
    val every =
        when {
            total > 600 -> 180
            total > 300 -> 120
            else -> 60
        }
    var m = (60 - start.minute) % 60
    while (m <= total - 20) {
        text(clockAt(start, m), Offset(x(m) - 14 * u, 226 * u), Label, 10)
        m += every
    }

    if (cursor != null && at != null) {
        val cx = x(cursor)
        drawRect(
            ScrubColor,
            Offset(x(at.from), 16 * u),
            Size(x(at.to) - x(at.from), 16 * u),
            style = Stroke(1.5f * u),
        )
        drawLine(ScrubColor, Offset(cx, 14 * u), Offset(cx, 220 * u), u)
        targetC(at.step)?.let {
            drawCircle(TargetLine, 4 * u, Offset(cx, tempY(it.coerceIn(16.0, 36.0))))
        }
        if (at.step.mode != Mode.WAIT && at.step.mode != Mode.STANDBY)
            drawCircle(FanLine, 4 * u, Offset(cx, fanY(fanPercent(at.step.fan))))
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
