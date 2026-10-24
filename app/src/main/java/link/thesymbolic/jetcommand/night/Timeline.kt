package link.thesymbolic.jetcommand.night

import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.NightDetail
import link.thesymbolic.jetcommand.protocol.NightEvent
import link.thesymbolic.jetcommand.protocol.NightSummary
import link.thesymbolic.jetcommand.protocol.fanPercent

/** More than this many minutes between samples is a gap, drawn as a break rather than a line. */
const val GAP_MINUTES = 2.5

typealias ChartPoint = Pair<Double, Double>

typealias Minutes = ClosedFloatingPointRange<Double>

/**
 * Everything the night charts plot, in chart units: x in minutes since [start], temperatures in °C.
 * [everyMinute] is one invisible point a minute across the night: Vico's marker snaps to existing
 * points, so this lets it land in gaps too.
 */
data class Timeline(
    val start: Long,
    val maxX: Double,
    val room: List<ChartPoint>,
    val air: List<ChartPoint>,
    val target: List<ChartPoint>,
    val fan: List<ChartPoint>,
    val presses: List<ChartPoint>,
    val minY: Double,
    val maxY: Double,
    val everyMinute: List<Double>,
    val unobserved: List<Minutes>,
    val drops: List<Minutes>,
    val apps: List<Minutes>,
    val modes: List<Pair<Minutes, Mode>>,
    val startMinuteOfHour: Int,
)

/** The instant [x] minutes into a night that began at [start]. */
fun atMinute(start: Long, x: Double): Long = start + (x * 60_000).toLong()

/** A press dot sits at the fan level it led to: the fan change logged up to this long after it. */
private const val PRESS_EFFECT_MS = 5_000L

fun timeline(d: NightDetail, zone: ZoneId): Timeline {
    val s = d.summary
    fun minuteOf(t: Long) = ((t - s.start) / 60_000.0).roundToLong().toDouble()
    fun spans(cause: NightSummary.Cause?) =
        s.gaps
            .filter { cause == null || it.cause == cause }
            .map { minuteOf(it.from)..minuteOf(it.to) }

    val maxX = minuteOf(s.end).coerceAtLeast(1.0)
    val samples = d.samples.distinctBy { minuteOf(it.at) }
    val room = samples.map { minuteOf(it.at) to it.room / 2.0 }
    val air = samples.map { minuteOf(it.at) to it.air / 2.0 }
    val target =
        samples.filter { it.mode.usesTemperature }.map { minuteOf(it.at) to it.target / 2.0 }
    val presses =
        d.events
            .filter { it.kind == NightEvent.Kind.COMMAND }
            .map { c ->
                val fan =
                    d.events.lastOrNull {
                        it.kind == NightEvent.Kind.FAN && it.at <= c.at + PRESS_EFFECT_MS
                    }
                minuteOf(c.at) to (fan?.value?.let(::fanPercent) ?: 0).toDouble()
            }
            .distinctBy { it.first }
    val all = (room + air + target).map { it.second }
    val modes =
        buildList<Pair<Minutes, Mode>> {
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
    return Timeline(
        start = s.start,
        maxX = maxX,
        room = room,
        air = air,
        target = target,
        fan = samples.map { minuteOf(it.at) to fanPercent(it.fanStep).toDouble() },
        presses = presses,
        minY = floor(all.minOrNull() ?: 18.0) - 1,
        maxY = ceil(all.maxOrNull() ?: 26.0) + 1,
        everyMinute = (0..maxX.toInt()).map { it.toDouble() },
        unobserved = spans(null),
        drops = spans(NightSummary.Cause.LINK),
        apps = spans(NightSummary.Cause.APP),
        modes = modes,
        startMinuteOfHour = Instant.ofEpochMilli(s.start).atZone(zone).minute,
    )
}

/** Where the time axis puts its labels, in minutes: every [spacing], the first at [offset]. */
data class TimeTicks(val spacing: Int, val offset: Int)

/** Labels on whole spans aligned to the hour. */
fun timeTicks(t: Timeline): TimeTicks {
    // At most five labels: Vico ellipsizes a label wider than its share of the axis.
    val spacing =
        listOf(15, 30, 60, 120, 180, 240, 360, 720).firstOrNull { t.maxX / it <= 5 } ?: 1440
    val hour = minOf(spacing, 60)
    // Vico clips the first label when it sits at the plot's left edge; start one step later then.
    val offset = ((60 - t.startMinuteOfHour) % hour).let { if (it < t.maxX / 20) it + hour else it }
    return TimeTicks(spacing, offset)
}
