package link.thesymbolic.jetcommand

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChart
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.BaseAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import link.thesymbolic.jetcommand.night.NightText
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.NightDetail
import link.thesymbolic.jetcommand.protocol.NightEvent
import link.thesymbolic.jetcommand.protocol.NightSummary
import link.thesymbolic.jetcommand.protocol.fanPercent

val Amber = Color(0xFFE8B04A)
private val HatchBase = Color(0xFF1A1F25)
private val HatchStripe = Color(0xFF3A2E14)
private val Room = Color(0xFFC8CDD3)
private val Target = Color(0xFF8FB4D9)
private val Air = Color(0xFF4C9AFF)
private val FanColor = Color(0xFF9AB0A8)
private val Cursor = Color(0xFFCFE2F5)

/** Same width on every panel, so their plot areas line up on one time axis. */
private val AxisWidth = 36.dp

/**
 * Three Vico charts on one x range (minutes since the night's start): lanes (link, mode),
 * temperatures, fan. Vico has one y range per layer and one vertical axis per side, so °C and fan %
 * are separate panels. [cursor] is the scrubbed minute, shared so all panels draw it; null when the
 * finger is up.
 */
@Composable
fun NightTimeline(
    d: NightDetail,
    text: NightText,
    cursor: MutableState<Double?>,
    modifier: Modifier = Modifier,
) {
    val m = remember(d) { TimelineData(d) }
    val listener =
        remember(cursor) {
            object : CartesianMarkerVisibilityListener {
                override fun onShown(
                    marker: CartesianMarker,
                    targets: List<CartesianMarker.Target>,
                ) {
                    cursor.value = targets.firstOrNull()?.x
                }

                override fun onUpdated(
                    marker: CartesianMarker,
                    targets: List<CartesianMarker.Target>,
                ) {
                    cursor.value = targets.firstOrNull()?.x
                }

                override fun onHidden(marker: CartesianMarker) {
                    cursor.value = null
                }
            }
        }
    val decorations = remember(m, cursor) { listOf(Unobserved(m.unobserved), CursorLine(cursor)) }
    Column(modifier.fillMaxWidth()) {
        Lanes(m, listener, cursor)
        Panel("°C") {
            TempChart(m, listener, decorations)
        }
        Panel("Fan %") {
            FanChart(m, text, listener, decorations)
        }
    }
}

@Composable
private fun Panel(label: String, content: @Composable () -> Unit) =
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }

/**
 * Everything the charts plot, in chart units: x in minutes since [NightSummary.start], temperatures
 * in °C.
 */
private class TimelineData(d: NightDetail) {
    val s = d.summary
    val maxX = minuteOf(s.end).coerceAtLeast(1.0)

    private fun minuteOf(t: Long) = ((t - s.start) / 60_000.0).roundToLong().toDouble()

    private val samples = d.samples.distinctBy { minuteOf(it.at) }
    val room = samples.map { minuteOf(it.at) to it.room / 2.0 }
    val air = samples.map { minuteOf(it.at) to it.air / 2.0 }
    val target =
        samples.filter { it.mode.usesTemperature }.map { minuteOf(it.at) to it.target / 2.0 }
    val fan = samples.map { minuteOf(it.at) to fanPercent(it.fanStep).toDouble() }
    val presses =
        d.events
            .filter { it.kind == NightEvent.Kind.COMMAND }
            .map { c ->
                minuteOf(c.at) to
                    (d.events
                            .lastOrNull { it.kind == NightEvent.Kind.FAN && it.at <= c.at + 5_000 }
                            ?.value
                            ?.let(::fanPercent) ?: 0)
                        .toDouble()
            }
            .distinctBy { it.first }
    val all = (room + air + target).map { it.second }
    val minY = floor(all.minOrNull() ?: 18.0) - 1
    val maxY = ceil(all.maxOrNull() ?: 26.0) + 1
    /**
     * One invisible point a minute across the night: Vico's marker snaps to existing points, so
     * this lets it land in gaps too.
     */
    val everyMinute = (0..maxX.toInt()).map { it.toDouble() }
    val unobserved = s.gaps.map { minuteOf(it.from)..minuteOf(it.to) }
    val drops =
        s.gaps
            .filter { it.cause == NightSummary.Cause.LINK }
            .map { minuteOf(it.from)..minuteOf(it.to) }
    val apps =
        s.gaps
            .filter { it.cause == NightSummary.Cause.APP }
            .map { minuteOf(it.from)..minuteOf(it.to) }
    val modes: List<Pair<ClosedFloatingPointRange<Double>, Mode>> = buildList {
        samples.forEach { sample ->
            val x = minuteOf(sample.at)
            val last = lastOrNull()
            if (
                last != null &&
                    last.second == sample.mode &&
                    x - last.first.endInclusive <= GAP_MINUTES
            )
                set(size - 1, last.first.start..x + 1 to sample.mode)
            else add(x..x + 1 to sample.mode)
        }
    }
    val startMinuteOfHour =
        java.time.Instant.ofEpochMilli(s.start).atZone(java.time.ZoneId.systemDefault()).minute
}

/**
 * More than this many minutes between samples is a gap, drawn as a break rather than a line across
 * it.
 */
private const val GAP_MINUTES = 2.5

private val MIN_RUN = 4.dp

/**
 * Vico joins every pair of neighbouring points; this breaks the line where samples are missing and
 * optionally steps it.
 */
private class GapInterpolator(private val step: Boolean) : LineCartesianLayer.Interpolator {
    override fun interpolate(
        context: CartesianDrawingContext,
        path: Path,
        points: List<Offset>,
        visibleIndexRange: IntRange,
    ) {
        val maxDx = context.layerDimensions.xSpacing * GAP_MINUTES.toFloat()
        val minRun = with(context) { MIN_RUN.pixels }
        var prev: Offset? = null
        var runStart = 0f
        // Over a long night a few minutes of samples are a pixel or two wide, and a lone sample
        // draws nothing: stretch such runs.
        val right = context.layerBounds.right
        fun closeRun(last: Offset) {
            if (last.x - runStart >= minRun) return
            if (runStart + minRun <= right) path.lineTo(runStart + minRun, last.y)
            else {
                path.moveTo(right - minRun, last.y)
                path.lineTo(right, last.y)
            } // at the plot's end, grow leftwards so it isn't clipped
        }
        for (i in visibleIndexRange) {
            val p = points[i]
            if (prev == null || p.x - prev.x > maxDx) {
                prev?.let(::closeRun)
                path.moveTo(p.x, p.y)
                runStart = p.x
            } else {
                if (step) path.lineTo(p.x, prev.y)
                path.lineTo(p.x, p.y)
            }
            prev = p
        }
        prev?.let(::closeRun)
    }
}

private fun CartesianDrawingContext.px(x: Double): Float =
    layerBounds.left +
        layerDimensions.startPadding +
        ((x - ranges.minX) / ranges.xStep * layerDimensions.xSpacing).toFloat() - scroll

/** Hatches the stretches nothing was watching, behind the lines. */
private class Unobserved(
    private val spans: List<ClosedFloatingPointRange<Double>>,
    private val full: Boolean = true,
    private val top: Float = 0f,
    private val bottom: Float = 1f,
) : Decoration {
    private val base = Paint().apply { color = HatchBase }
    private val stripe =
        Paint().apply {
            color = HatchStripe
            strokeWidth = 4f
        }

    override fun drawUnderLayers(context: CartesianDrawingContext) =
        with(context) {
            val b = layerBounds
            val y0 = if (full) b.top else b.top + b.height * top
            val y1 = if (full) b.bottom else b.top + b.height * bottom
            spans.forEach { span ->
                val r = Rect(px(span.start), y0, px(span.endInclusive), y1)
                if (r.width <= 0f) return@forEach
                canvas.save()
                canvas.clipRect(r)
                canvas.drawRect(r, base)
                var x = r.left - r.height
                while (x < r.right) {
                    canvas.drawLine(Offset(x, r.bottom), Offset(x + r.height, r.top), stripe)
                    x += 12f
                }
                canvas.restore()
            }
        }
}

/** The scrub position, drawn in every panel from shared state so a drag on one shows on all. */
private class CursorLine(private val cursor: MutableState<Double?>) : Decoration {
    private val paint =
        Paint().apply {
            color = Cursor
            strokeWidth = 3f
        }

    override fun drawOverLayers(context: CartesianDrawingContext) {
        val x = cursor.value ?: return
        with(context) {
            canvas.drawLine(
                Offset(px(x), layerBounds.top),
                Offset(px(x), layerBounds.bottom),
                paint,
            )
        }
    }
}

/** Vico needs a marker to report the touched x; the cursor itself is [CursorLine]. */
private object Scrub : CartesianMarker

/**
 * The link lane and the mode band, painted as decorations so they share the panels' x mapping
 * exactly.
 */
private class LaneBands(private val m: TimelineData) : Decoration {
    private val paint = Paint()

    override fun drawUnderLayers(context: CartesianDrawingContext) =
        with(context) {
            val b = layerBounds
            val linkBottom = b.top + b.height * 0.55f
            val modeTop = b.top + b.height * 0.72f
            fun bar(x0: Double, x1: Double, y0: Float, y1: Float, c: Color, minPx: Float = 0f) {
                paint.color = c
                val l = px(x0)
                canvas.drawRect(Rect(l, y0, maxOf(px(x1), l + minPx), y1), paint)
            }
            bar(0.0, m.maxX, b.top, linkBottom, Target)
            m.drops.forEach { bar(it.start, it.endInclusive, b.top, linkBottom, Amber, minPx = 3f) }
            m.modes.forEach { (r, mode) ->
                bar(r.start, r.endInclusive, modeTop, b.bottom, modeColor(mode))
            }
        }
}

@Composable
private fun rememberRange(m: TimelineData, minY: Double, maxY: Double) =
    remember(m) {
        CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = m.maxX, minY = minY, maxY = maxY)
    }

@Composable
private fun invisibleLine() =
    LineCartesianLayer.rememberLine(
        fill = remember { LineCartesianLayer.LineFill.single(Fill.Transparent) },
        // Vico widens the plot margins by half the largest point; every panel carries the press
        // dot's size to stay aligned.
        pointProvider =
            remember {
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(ShapeComponent(Fill.Transparent), size = DotSize)
                )
            },
    )

private val DotSize = 6.dp

@Composable
private fun startAxis(step: Double, suffix: String) =
    VerticalAxis.rememberStart(
        line = null,
        tick = null,
        label =
            rememberAxisLabelComponent(
                style =
                    TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            ),
        guideline =
            rememberAxisGuidelineComponent(fill = Fill(MaterialTheme.colorScheme.surfaceVariant)),
        itemPlacer = remember(step) { VerticalAxis.ItemPlacer.step({ step }) },
        valueFormatter = remember(suffix) { CartesianValueFormatter.decimal(suffix = suffix) },
        size = BaseAxis.Size.Fixed(AxisWidth),
    )

@Composable
private fun Host(chart: CartesianChart, model: CartesianChartModel, height: Dp) =
    CartesianChartHost(
        chart = chart,
        model = model,
        modifier = Modifier.fillMaxWidth().height(height),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
    )

@Composable
private fun scrubChart(
    layer: LineCartesianLayer,
    startAxis: VerticalAxis<Axis.Position.Vertical.Start>,
    bottomAxis: HorizontalAxis<Axis.Position.Horizontal.Bottom>?,
    listener: CartesianMarkerVisibilityListener,
    decorations: List<Decoration>,
) =
    rememberCartesianChart(
        layer,
        startAxis = startAxis,
        bottomAxis = bottomAxis,
        marker = Scrub,
        markerVisibilityListener = listener,
        markerController = CartesianMarkerController.rememberShowOnPress(),
        decorations = decorations,
        getXStep = { _, _, _ -> 1.0 },
    )

@Composable
private fun Lanes(
    m: TimelineData,
    listener: CartesianMarkerVisibilityListener,
    cursor: MutableState<Double?>,
) {
    val layer =
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(invisibleLine()),
            rangeProvider = rememberRange(m, 0.0, 1.0),
        )
    val axis =
        VerticalAxis.rememberStart(
            line = null,
            tick = null,
            label = null,
            guideline = null,
            size = BaseAxis.Size.Fixed(AxisWidth),
        )
    val decorations =
        remember(m, cursor) {
            listOf(
                LaneBands(m),
                Unobserved(m.apps, full = false, bottom = 0.55f),
                CursorLine(cursor),
            )
        }
    val chart = scrubChart(layer, axis, null, listener, decorations)
    val model =
        remember(m) {
            CartesianChartModel(
                LineCartesianLayerModel.build { series(m.everyMinute, m.everyMinute.map { 0 }) }
            )
        }
    Box {
        Host(chart, model, 22.dp)
        Text(
            "Link",
            Modifier.width(AxisWidth),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TempChart(
    m: TimelineData,
    listener: CartesianMarkerVisibilityListener,
    decorations: List<Decoration>,
) {
    val lines =
        mapOf(
            "grid" to invisibleLine(),
            "target" to
                LineCartesianLayer.rememberLine(
                    fill = remember { LineCartesianLayer.LineFill.single(Fill(Target)) },
                    stroke =
                        LineCartesianLayer.LineStroke.Dashed(
                            thickness = 2.dp,
                            dashLength = 5.dp,
                            gapLength = 3.dp,
                        ),
                    interpolator = remember { GapInterpolator(step = true) },
                ),
            "air" to
                LineCartesianLayer.rememberLine(
                    fill = remember { LineCartesianLayer.LineFill.single(Fill(Air)) },
                    stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
                    interpolator = remember { GapInterpolator(step = false) },
                ),
            "room" to
                LineCartesianLayer.rememberLine(
                    fill = remember { LineCartesianLayer.LineFill.single(Fill(Room)) },
                    stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.dp),
                    interpolator = remember { GapInterpolator(step = false) },
                ),
        )
    val layer =
        rememberLineCartesianLayer(
            lineProvider =
                remember(lines) {
                    LineCartesianLayer.LineProvider { key, _, _ -> lines.getValue(key as String) }
                },
            rangeProvider = rememberRange(m, m.minY, m.maxY),
        )
    val chart = scrubChart(layer, startAxis(2.0, "°"), null, listener, decorations)
    val model =
        remember(m) {
            CartesianChartModel(
                LineCartesianLayerModel.build {
                    series(m.everyMinute, m.everyMinute.map { m.minY }, key = "grid")
                    if (m.target.isNotEmpty())
                        series(
                            m.target.map { it.first },
                            m.target.map { it.second },
                            key = "target",
                        )
                    if (m.air.isNotEmpty())
                        series(m.air.map { it.first }, m.air.map { it.second }, key = "air")
                    if (m.room.isNotEmpty())
                        series(m.room.map { it.first }, m.room.map { it.second }, key = "room")
                }
            )
        }
    Host(chart, model, 150.dp)
}

@Composable
private fun FanChart(
    m: TimelineData,
    text: NightText,
    listener: CartesianMarkerVisibilityListener,
    decorations: List<Decoration>,
) {
    val dot = remember {
        LineCartesianLayer.Point(ShapeComponent(Fill(Room), CircleShape), size = DotSize)
    }
    val lines =
        mapOf(
            "grid" to invisibleLine(),
            "fan" to
                LineCartesianLayer.rememberLine(
                    fill = remember { LineCartesianLayer.LineFill.single(Fill(FanColor)) },
                    stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.dp),
                    interpolator = remember { GapInterpolator(step = true) },
                ),
            "press" to
                LineCartesianLayer.rememberLine(
                    fill = remember { LineCartesianLayer.LineFill.single(Fill.Transparent) },
                    pointProvider = remember { LineCartesianLayer.PointProvider.single(dot) },
                ),
        )
    val layer =
        rememberLineCartesianLayer(
            lineProvider =
                remember(lines) {
                    LineCartesianLayer.LineProvider { key, _, _ -> lines.getValue(key as String) }
                },
            rangeProvider = rememberRange(m, 0.0, 100.0),
        )
    val bottom = rememberTimeAxis(m, text)
    val chart = scrubChart(layer, startAxis(50.0, ""), bottom, listener, decorations)
    val model =
        remember(m) {
            CartesianChartModel(
                LineCartesianLayerModel.build {
                    series(m.everyMinute, m.everyMinute.map { 0 }, key = "grid")
                    if (m.fan.isNotEmpty())
                        series(m.fan.map { it.first }, m.fan.map { it.second }, key = "fan")
                    if (m.presses.isNotEmpty())
                        series(
                            m.presses.map { it.first },
                            m.presses.map { it.second },
                            key = "press",
                        )
                }
            )
        }
    Host(chart, model, 96.dp)
}

/** Bottom axis of clock times, labelled on whole spans aligned to the hour. */
@Composable
private fun rememberTimeAxis(
    m: TimelineData,
    text: NightText,
): HorizontalAxis<Axis.Position.Horizontal.Bottom> {
    // At most five labels: Vico ellipsizes a label wider than its share of the axis.
    val spacing =
        listOf(15, 30, 60, 120, 180, 240, 360, 720).firstOrNull { m.maxX / it <= 5 } ?: 1440
    val hour = minOf(spacing, 60)
    // Vico clips the first label when it sits at the plot's left edge; start one step later then.
    val offset = ((60 - m.startMinuteOfHour) % hour).let { if (it < m.maxX / 20) it + hour else it }
    return HorizontalAxis.rememberBottom(
        line =
            rememberAxisGuidelineComponent(
                fill = Fill(MaterialTheme.colorScheme.outline),
                shape = androidx.compose.ui.graphics.RectangleShape,
            ),
        tick = null,
        guideline = null,
        label =
            rememberAxisLabelComponent(
                style =
                    TextStyle(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                    )
            ),
        valueFormatter =
            remember(m) {
                CartesianValueFormatter { _, x, _ ->
                    text.time(m.s.start + (x * 60_000).toLong())
                }
            },
        itemPlacer =
            remember(spacing, offset) {
                HorizontalAxis.ItemPlacer.aligned(
                    spacing = { spacing },
                    offset = { offset },
                    addExtremeLabelPadding = false,
                )
            },
    )
}
