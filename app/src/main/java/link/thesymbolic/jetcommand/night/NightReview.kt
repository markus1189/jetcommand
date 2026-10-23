package link.thesymbolic.jetcommand.night

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.NightDetail
import link.thesymbolic.jetcommand.protocol.NightEvent
import link.thesymbolic.jetcommand.protocol.NightEvent.Kind
import link.thesymbolic.jetcommand.protocol.NightSample
import link.thesymbolic.jetcommand.protocol.NightSummary
import link.thesymbolic.jetcommand.protocol.ProcessLog
import link.thesymbolic.jetcommand.protocol.fanPercent
import link.thesymbolic.jetcommand.protocol.formatTemp

/**
 * What the Nights screens show, derived from the [NightsSource] contract; no Android, no Compose.
 */
class NightText(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)
    private val monthFmt = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

    fun time(at: Long): String = hm.format(Instant.ofEpochMilli(at).atZone(zone))

    fun timeSec(at: Long): String = hms.format(Instant.ofEpochMilli(at).atZone(zone))

    /**
     * "Wed 22 → Thu 23 Oct"; a night counts from the evening it began, so a log started after
     * midnight is the previous evening's.
     */
    fun title(s: NightSummary): String {
        val first =
            Instant.ofEpochMilli(s.start).atZone(zone).toLocalDate().let { d ->
                if (Instant.ofEpochMilli(s.start).atZone(zone).hour < 12) d.minusDays(1) else d
            }
        val second = first.plusDays(1)
        val firstText =
            dayFmt.format(first) +
                if (first.month != second.month) " " + monthFmt.format(first) else ""
        return "$firstText → ${dayFmt.format(second)} ${monthFmt.format(second)}"
    }

    fun span(s: NightSummary) = "${time(s.start)} – ${time(s.end)}"
}

fun duration(ms: Long): String {
    val m = (ms + 30_000) / 60_000
    return when {
        ms < 120_000 -> "${(ms + 500) / 1000} s"
        m < 60 -> "$m min"
        m % 60 == 0L -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} m"
    }
}

enum class Watch {
    CONNECTED,
    DROP,
    UNOBSERVED,
}

/** The full-night strip: [from]/[to] are fractions of the night. */
data class Stretch(val from: Float, val to: Float, val kind: Watch)

fun stretches(s: NightSummary): List<Stretch> {
    val total = (s.end - s.start).coerceAtLeast(1).toFloat()
    fun f(t: Long) = ((t - s.start) / total).coerceIn(0f, 1f)
    var cursor = s.start
    return buildList {
        s.gaps.forEach { g ->
            if (g.from > cursor) add(Stretch(f(cursor), f(g.from), Watch.CONNECTED))
            add(
                Stretch(
                    f(g.from),
                    f(g.to),
                    if (g.cause == NightSummary.Cause.LINK) Watch.DROP else Watch.UNOBSERVED,
                )
            )
            cursor = maxOf(cursor, g.to)
        }
        if (s.end > cursor) add(Stretch(f(cursor), 1f, Watch.CONNECTED))
    }
}

val NightSummary.longestDrop: Long?
    get() = gaps.filter { it.cause == NightSummary.Cause.LINK }.maxOfOrNull { it.to - it.from }
val NightSummary.watched: Long
    get() = (end - start) - gaps.sumOf { it.to - it.from }

data class Stats(
    val watched: Long,
    val total: Long,
    val drops: Int,
    val longestDrop: Long?,
    val presses: Int,
    val pressSplit: String,
    val deviation: Double?,
)

fun stats(d: NightDetail): Stats {
    val s = d.summary
    val commands = d.events.filter { it.kind == Kind.COMMAND }
    val fan = commands.count { it.detail.startsWith("fan") }
    val temp = commands.count { it.detail.startsWith("target") || it.detail.startsWith("temp") }
    val other = commands.size - fan - temp
    val split =
        listOfNotNull(
                "$fan fan".takeIf { fan > 0 },
                "$temp temp".takeIf { temp > 0 },
                "$other other".takeIf { other > 0 },
            )
            .joinToString(", ")
    val inUse = d.samples.filter { it.mode.usesTemperature }
    val deviation =
        inUse
            .takeIf { it.isNotEmpty() }
            ?.let { xs -> xs.sumOf { abs(it.room - it.target) } / 2.0 / xs.size }
    return Stats(s.watched, s.end - s.start, s.drops, s.longestDrop, s.presses, split, deviation)
}

fun chips(s: NightSummary, text: NightText): List<Pair<String, Boolean>> = buildList {
    s.killedAt.forEach { add("App killed ${text.time(it)}" to true) }
    when (s.drops) {
        0 -> if (s.killedAt.isEmpty()) add("No drops" to false)
        1 -> add("1 drop · ${duration(s.longestDrop ?: 0)}" to false)
        else -> add("${s.drops} drops · longest ${duration(s.longestDrop ?: 0)}" to false)
    }
    if (s.presses > 0) add("${s.presses} ${if (s.presses == 1) "press" else "presses"}" to false)
}

/**
 * One line of the event list; [at] null for a note between events. [lead] is the warning-coloured
 * start.
 */
data class EventRow(
    val at: Long?,
    val text: String,
    val lead: String? = null,
    val note: String? = null,
    val warn: Boolean = false,
    val muted: Boolean = false,
)

/** The run of changes a user makes in one go collapses into one row when they are this close. */
private const val RUN_MS = 10 * 60_000L
/**
 * A state event this close after a command is that command's effect, not something the unit did.
 */
private const val CAUSED_MS = 5_000L

fun importantRows(d: NightDetail, text: NightText): List<EventRow> = ImportantRows(d, text).build()

private class ImportantRows(private val d: NightDetail, private val text: NightText) {
    private val ev = d.events
    private val commands = ev.filter { it.kind == Kind.COMMAND }
    private val rows = mutableListOf<EventRow>()
    private val folded = mutableSetOf<NightEvent>()

    private fun pressed(e: NightEvent) = commands.any { it.at in e.at - CAUSED_MS..e.at }

    fun build(): List<EventRow> {
        ev.indices.forEach { i ->
            val e = ev[i]
            when (e.kind) {
                Kind.CONNECTED -> connected(i, e)
                Kind.LOST -> lost(i, e)
                Kind.FAN,
                Kind.TARGET -> if (pressed(e) && e !in folded) change(i, e)
                Kind.MODE -> if (pressed(e)) rows += EventRow(e.at, "Mode ${e.detail}")
                Kind.PROCESS -> if (e.detail.startsWith("exit")) exit(e)
                else -> plain(e)?.let { rows += it }
            }
        }
        return rows
    }

    /** Rows shown for every event of their kind, unconditionally. */
    private fun plain(e: NightEvent): EventRow? =
        when (e.kind) {
            Kind.DISCONNECTED -> EventRow(e.at, "Disconnected ${e.detail}")
            Kind.GAVE_UP -> EventRow(e.at, "Reconnect stopped", warn = true)
            Kind.SHUTDOWN -> EventRow(e.at, "BedJet shut down · ${e.detail}", warn = true)
            Kind.NOTIFICATION -> EventRow(e.at, "BedJet: ${e.detail}")
            else -> null
        }

    private fun connected(i: Int, e: NightEvent) {
        val started =
            ev.any {
                it.kind == Kind.PROCESS &&
                    it.detail == ProcessLog.STARTED &&
                    it.at in e.at - 60_000..e.at
            } && rows.isNotEmpty()
        val mode = ev.drop(i + 1).firstOrNull { it.kind == Kind.MODE && it.at - e.at < 60_000 }
        val modeNote = mode?.takeIf { started }?.let { " · BedJet ${it.detail}" } ?: ""
        rows += EventRow(e.at, (if (started) "App started · Connected" else "Connected") + modeNote)
    }

    private fun lost(i: Int, e: NightEvent) {
        val back =
            ev.drop(i + 1).firstOrNull {
                it.kind == Kind.RECONNECTED || it.kind == Kind.CONNECTED || it.kind == Kind.GAVE_UP
            }
        rows +=
            if (back?.kind == Kind.RECONNECTED)
                EventRow(e.at, " · back after ${duration(back.at - e.at)}", lead = "Lost")
            else EventRow(e.at, " · ${e.detail}", lead = "Lost")
    }

    /** Pressed changes of [e]'s kind from [i] on, until a pressed change of another kind. */
    private fun run(i: Int, e: NightEvent): List<NightEvent> {
        val run = mutableListOf(e)
        var j = i + 1
        while (j < ev.size && ev[j].at - run.last().at <= RUN_MS) {
            if (ev[j].kind == e.kind && pressed(ev[j])) run += ev[j]
            else if (
                ev[j].kind in setOf(Kind.FAN, Kind.TARGET, Kind.MODE) &&
                    ev[j].kind != e.kind &&
                    pressed(ev[j])
            )
                break
            j++
        }
        return run
    }

    private fun change(i: Int, e: NightEvent) {
        val run = run(i, e)
        val before = ev.take(i).lastOrNull { it.kind == e.kind }?.value
        val after = run.last().value
        val presses = commands.count { c ->
            c.at in e.at - CAUSED_MS..run.last().at &&
                (if (e.kind == Kind.FAN) c.detail.startsWith("fan")
                else !c.detail.startsWith("fan"))
        }
        fun show(v: Int?) =
            if (v == null) "?"
            else if (e.kind == Kind.FAN) "${fanPercent(v)}" else formatTemp(v, withUnit = false)
        val unit = if (e.kind == Kind.FAN) "%" else "°C"
        val label = if (e.kind == Kind.FAN) "Fan" else "Target"
        val change =
            if (before != null) "${show(before)} → ${show(after)} $unit" else "${show(after)} $unit"
        val note =
            when {
                presses <= 1 -> null
                run.last().at - e.at >= 60_000 ->
                    " · $presses presses until ${text.time(run.last().at)}"
                else -> " · $presses presses"
            }
        rows += EventRow(e.at, "$label $change", note = note)
        folded += run
    }

    private fun exit(e: NightEvent) {
        val killed = e.at in d.summary.killedAt
        rows +=
            EventRow(
                e.at,
                if (killed) "App killed by Android"
                else
                    "App ended · ${e.detail.removePrefix(ProcessLog.EXIT_PREFIX).substringBefore(" importance")}",
                warn = killed,
            )
        d.summary.gaps
            .firstOrNull { it.cause == NightSummary.Cause.APP && it.from == e.at }
            ?.let {
                rows += EventRow(null, "${duration(it.to - it.from)} unobserved", muted = true)
            }
    }
}

fun allRows(d: NightDetail): List<EventRow> =
    d.events.map {
        EventRow(
            it.at,
            "${label(it.kind)} · ${it.detail}",
            warn = it.kind == Kind.LOST || it.kind == Kind.GAVE_UP || it.at in d.summary.killedAt,
        )
    }

private fun label(k: Kind) =
    k.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

fun shareText(d: NightDetail, text: NightText): String = buildString {
    appendLine("JetCommand night ${text.title(d.summary)}, ${text.span(d.summary)}")
    d.events.forEach {
        appendLine("${text.timeSec(it.at)}  ${it.kind.name.padEnd(12)} ${it.detail}")
    }
}

/**
 * The sample covering [at], or null when nothing was recorded then (samples are a minute apart).
 */
fun sampleAt(samples: List<NightSample>, at: Long): NightSample? {
    var lo = 0
    var hi = samples.size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (samples[mid].at < at) lo = mid + 1 else hi = mid
    }
    return listOfNotNull(samples.getOrNull(lo - 1), samples.getOrNull(lo))
        .minByOrNull { abs(it.at - at) }
        ?.takeIf { abs(it.at - at) <= 90_000 }
}

fun readout(sample: NightSample?, at: Long, text: NightText): String =
    if (sample == null) "${text.time(at)} · not recorded"
    else
        buildString {
            append("${text.time(at)} · ${sample.mode.label}\n")
            append("room ${formatTemp(sample.room)} · target ")
            append(if (sample.mode.usesTemperature) formatTemp(sample.target) else "--")
            append(" · air ${formatTemp(sample.air)} · fan ${fanPercent(sample.fanStep)} %")
        }

/** The amber callout for each time the app was killed: title and explanation. */
fun killedCallouts(d: NightDetail, text: NightText): List<Pair<String, String>> =
    d.summary.killedAt.map { at ->
        val why =
            d.events
                .firstOrNull { it.at == at && it.kind == Kind.PROCESS }
                ?.detail
                ?.let { detail ->
                    Regex("""\(([^()]*)\)""").findAll(detail).lastOrNull()?.groupValues?.get(1)
                        ?: detail.removePrefix(ProcessLog.EXIT_PREFIX).substringBefore(' ')
                }
        val gap = d.summary.gaps.firstOrNull { it.cause == NightSummary.Cause.APP && it.from == at }
        val mode = gap?.let { g -> d.events.firstOrNull { it.kind == Kind.MODE && it.at >= g.to } }
        val body = buildString {
            append("Android ended the app")
            if (why != null) append(" ($why)")
            append(".")
            if (gap != null) {
                append(
                    " For the next ${duration(gap.to - gap.from)} nothing was watching the BedJet"
                )
                append(
                    if (mode != null && mode.at - gap.to < 60_000)
                        "; at ${text.time(gap.to)} it was ${if (mode.value == Mode.STANDBY.ordinal) "already Off" else mode.detail}."
                    else "."
                )
            }
        }
        "The app was killed at ${text.time(at)}" to body
    }
