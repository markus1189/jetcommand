// The program editor's state, its transitions and what its rows say; the step rules themselves live
// in protocol's StepRules.
package link.thesymbolic.jetcommand.edit

import link.thesymbolic.jetcommand.Span
import link.thesymbolic.jetcommand.durationMin
import link.thesymbolic.jetcommand.hhmm
import link.thesymbolic.jetcommand.pad2
import link.thesymbolic.jetcommand.plural
import link.thesymbolic.jetcommand.prettyName
import link.thesymbolic.jetcommand.protocol.core.ClockWindow
import link.thesymbolic.jetcommand.protocol.core.EditNote
import link.thesymbolic.jetcommand.protocol.core.Limits
import link.thesymbolic.jetcommand.protocol.core.Mode
import link.thesymbolic.jetcommand.protocol.core.PlannedStep
import link.thesymbolic.jetcommand.protocol.core.ProgramRevision
import link.thesymbolic.jetcommand.protocol.core.Step
import link.thesymbolic.jetcommand.protocol.core.StepEdit
import link.thesymbolic.jetcommand.protocol.core.TEMP_RANGES
import link.thesymbolic.jetcommand.protocol.core.Timing
import link.thesymbolic.jetcommand.protocol.core.applyEdit
import link.thesymbolic.jetcommand.protocol.core.durationRange
import link.thesymbolic.jetcommand.protocol.core.fanPercent
import link.thesymbolic.jetcommand.protocol.core.formatTemp
import link.thesymbolic.jetcommand.protocol.core.planProgram
import link.thesymbolic.jetcommand.protocol.core.untilAllowed

private const val DAY = 24 * 60

/** Programs store no start time (ROADMAP known gap), so the editor assumes a bedtime. */
const val DEFAULT_START = 22 * 60 + 30
const val START_STEP = 15

/** The last edit's side effect, shown under the step it changed. */
data class StepNote(val index: Int, val text: String)

data class EditorState(
    val name: String,
    val rev: Int,
    val base: List<Step>,
    val steps: List<Step> = base,
    val selected: Int? = null,
    /** Minute of the day the program is assumed to start; session-only. */
    val start: Int = DEFAULT_START,
    val note: StepNote? = null,
) {
    val planned: List<PlannedStep> by lazy { planProgram(steps, start) }

    val changes
        get() = changeCount(base, steps)

    val full
        get() = steps.size >= Limits.STEPS_PER_PROGRAM

    val single
        get() = steps.size <= 1
}

fun editorFor(r: ProgramRevision) = EditorState(r.program.name, r.rev, r.program.steps)

/** The store numbers a new revision after the highest one, which need not be the current. */
fun nextRev(current: Int, revisions: List<ProgramRevision>) =
    maxOf(current, revisions.maxOfOrNull { it.rev } ?: 0) + 1

/** Steps that differ by position, plus every step added or removed at the end. */
fun changeCount(base: List<Step>, steps: List<Step>): Int =
    Math.abs(base.size - steps.size) +
        (0 until minOf(base.size, steps.size)).count { base[it] != steps[it] }

fun EditorState.edit(i: Int, e: StepEdit): EditorState {
    val before = steps[i]
    val r = applyEdit(before, e, planned[i])
    return copy(
        steps = steps.toMutableList().apply { set(i, r.step) },
        note = r.note?.let { StepNote(i, noteText(it, before, r.step)) },
    )
}

fun EditorState.toggle(i: Int) = copy(selected = if (selected == i) null else i, note = null)

fun EditorState.open(i: Int) = copy(selected = i, note = null)

/** Where index [i] ends up when the step at [from] moves to [to]. */
fun movedIndex(i: Int, from: Int, to: Int): Int =
    when {
        i == from -> to
        i in (from + 1)..to -> i - 1
        i in to until from -> i + 1
        else -> i
    }

fun EditorState.move(from: Int, to: Int): EditorState {
    if (from !in steps.indices || to !in steps.indices || from == to) return this
    return copy(
        steps = steps.toMutableList().apply { add(to, removeAt(from)) },
        selected = selected?.let { movedIndex(it, from, to) },
        note = null,
    )
}

fun EditorState.duplicate(i: Int): EditorState =
    if (full) this
    else
        copy(
            steps = steps.toMutableList().apply { add(i + 1, steps[i]) },
            selected = i + 1,
            note = null,
        )

fun EditorState.delete(i: Int): EditorState =
    if (single) this
    else copy(steps = steps.filterIndexed { k, _ -> k != i }, selected = null, note = null)

val NEW_STEP = Step(Mode.WAIT, fan = 19, temp = 80, Timing.For(0, 30))

fun EditorState.add(): EditorState =
    if (full) this else copy(steps = steps + NEW_STEP, selected = steps.size, note = null)

fun EditorState.discard() =
    copy(steps = base, selected = selected?.takeIf { it < base.size }, note = null)

fun EditorState.shiftStart(minutes: Int) = copy(start = Math.floorMod(start + minutes, DAY))

// ---- Step controls: each returns the edit, or null where the control is disabled ----

fun maxMinutes(step: Step) = durationRange(step).last

/** The minutes a held −/+ moves per tick, after [ticks] repeats. */
val HOLD_STEPS = listOf(1, 5, 15)

fun holdLabel(by: Int) = if (by == 1) "tap 1 min" else "hold $by min"

fun typeTitle(step: Step, i: Int) =
    "${if (step.timing is Timing.Until) "Run until" else "Run for"} · step ${i + 1}"

fun holdStep(ticks: Int) =
    when {
        ticks < 8 -> 1
        ticks < 20 -> 5
        else -> 15
    }

/** [v] moved one [by] towards [dir]; a coarse step lands on the next multiple of [by]. */
fun stepped(v: Int, dir: Int, by: Int): Int =
    when {
        by == 1 -> v + dir
        dir > 0 -> Math.floorDiv(v, by) * by + by
        else -> -Math.floorDiv(-v, by) * by - by
    }

/** A clock time wraps past midnight; the 12 h limit there depends on the start, see [issue]. */
fun nudge(step: Step, dir: Int, by: Int = 1): StepEdit? =
    when (val t = step.timing) {
        is Timing.Until -> StepEdit.SetUntil(Math.floorMod(stepped(t.minuteOfDay, dir, by), DAY))
        is Timing.For ->
            stepped(t.totalMinutes, dir, by)
                .coerceIn(durationRange(step))
                .takeIf { it != t.totalMinutes }
                ?.let(StepEdit::SetDuration)
    }

// ---- Typed time: up to four digits filling h:mm (or HH:MM) from the right ----

const val BACKSPACE = "⌫"
val KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", BACKSPACE)

fun typeKey(digits: String, key: String): String =
    when {
        key == BACKSPACE -> digits.dropLast(1)
        digits.isEmpty() && key.startsWith("0") -> digits
        else -> (digits + key).take(4)
    }

/**
 * The keypad's reading of [digits] for [step], which [p] plans; [edit] is null until it is one the
 * step takes. A clock time past the 12 h limit only warns: the start is an assumption.
 */
data class Typed(
    val hours: String,
    val minutes: String,
    val typing: Boolean,
    val note: String,
    val bad: Boolean,
    val edit: StepEdit?,
)

fun typed(step: Step, p: PlannedStep, digits: String): Typed {
    val until = step.timing is Timing.Until
    val hoursText = { h: Int -> if (until) pad2(h) else h.toString() }
    if (digits.isEmpty()) {
        val v =
            when (val t = step.timing) {
                is Timing.Until -> t.minuteOfDay
                is Timing.For -> t.totalMinutes
            }
        val hint =
            if (until) "24-hour clock: 6 3 0 is 06:30" else "Hours, then minutes: 7 4 3 is 7 h 43"
        return Typed(hoursText(v / 60), pad2(v % 60), typing = false, hint, bad = false, null)
    }
    val d = digits.padStart(4, '0')
    val h = d.take(2).toInt()
    val m = d.drop(2).toInt()
    val v = h * 60 + m
    typedError(step, h, m)?.let {
        return Typed(hoursText(h), pad2(m), typing = true, it, bad = true, null)
    }
    if (!until)
        return Typed(hoursText(h), pad2(m), typing = true, "", bad = false, StepEdit.SetDuration(v))
    val wanted = Math.floorMod(v - p.plannedFrom, DAY)
    val late = wanted > Limits.MAX_STEP_MINUTES
    val note =
        "${durationMin(wanted)} after the step before" +
            if (late) ": the BedJet refuses to start" else ""
    return Typed(hoursText(h), pad2(m), typing = true, note, bad = late, StepEdit.SetUntil(v))
}

private fun typedError(step: Step, h: Int, m: Int): String? =
    when {
        m > 59 -> "Minutes go up to 59"
        step.timing is Timing.Until -> if (h > 23) "Hours go up to 23" else null
        h * 60 + m == 0 -> "At least 1 min"
        h * 60 + m > maxMinutes(step) -> overText(step)
        else -> null
    }

private fun overText(step: Step) =
    when {
        step.mode == Mode.TURBO -> "Turbo runs at most ${Limits.TURBO_MAX_MINUTES} min"
        maxMinutes(step) < Limits.MAX_STEP_MINUTES ->
            "${step.mode.label} at this setting runs at most ${durationMin(maxMinutes(step))}"
        else -> "At most ${durationMin(Limits.MAX_STEP_MINUTES)}"
    }

fun fanBy(step: Step, delta: Int): StepEdit? =
    (step.fan + delta).takeIf { step.mode != Mode.WAIT && it in 0..19 }?.let(StepEdit::SetFan)

fun tempBy(step: Step, delta: Int): StepEdit? {
    if (!step.mode.usesTemperature) return null
    val r = TEMP_RANGES.getValue(step.mode)
    return (step.temp + delta).takeIf { it in r.min..r.max }?.let(StepEdit::SetTemp)
}

// ---- Texts ----

fun clock(minute: Int) = Math.floorMod(minute, DAY).let { hhmm(it / 60, it % 60) }

fun statusText(rev: Int, changes: Int) =
    if (changes == 0) "r$rev · no changes" else "Editing r$rev · ${plural(changes, "change")}"

fun saveLabel(nextRev: Int) = "Save as r$nextRev"

fun leaveText(s: EditorState, nextRev: Int): String {
    val n = s.changes
    return "${plural(n, "change")} to ${prettyName(s.name)} ${if (n == 1) "is" else "are"} not saved. Saving makes r$nextRev; the BedJet keeps the version it holds until you update its slot."
}

fun stepsTitle(n: Int) = "Steps · $n of ${Limits.STEPS_PER_PROGRAM}"

fun detailText(step: Step): String =
    when (step.mode) {
        Mode.WAIT -> "heater and fan off"
        Mode.TURBO -> "fan ${fanPercent(step.fan)} % · ${formatTemp(step.temp)} fixed"
        else -> "fan ${fanPercent(step.fan)} % · ${formatTemp(step.temp)}"
    }

fun lengthText(step: Step): String =
    when (val t = step.timing) {
        is Timing.Until -> "until ${clock(t.minuteOfDay)}"
        is Timing.For -> durationMin(t.totalMinutes)
    }

fun timeText(step: Step): String =
    when (val t = step.timing) {
        is Timing.Until -> clock(t.minuteOfDay)
        is Timing.For -> durationMin(t.totalMinutes)
    }

fun timeHint(step: Step, p: PlannedStep): String =
    when {
        step.timing is Timing.Until -> "clock time · ${durationMin(p.wanted)} from the step before"
        step.mode == Mode.TURBO -> "1–${Limits.TURBO_MAX_MINUTES} min"
        maxMinutes(step) < Limits.MAX_STEP_MINUTES ->
            "${step.mode.label} at this setting: at most ${durationMin(maxMinutes(step))}"
        else -> "up to ${durationMin(Limits.MAX_STEP_MINUTES)}"
    }

fun fanText(step: Step) = if (step.mode == Mode.WAIT) "off" else "${fanPercent(step.fan)} %"

fun tempText(step: Step) = if (step.mode == Mode.WAIT) "none" else formatTemp(step.temp)

fun tempRangeText(mode: Mode): String =
    when {
        mode.usesTemperature ->
            TEMP_RANGES.getValue(mode).let { "${formatTemp(it.min)} – ${formatTemp(it.max)}" }
        mode == Mode.TURBO -> "fixed in Turbo"
        else -> ""
    }

fun untilWhy(mode: Mode): String? =
    when {
        untilAllowed(mode) -> null
        mode == Mode.TURBO ->
            "Turbo runs 1–${Limits.TURBO_MAX_MINUTES} minutes, never until a clock time."
        else -> "Run until works with Wait, Ext. heat and Cool only."
    }

fun noteText(note: EditNote, before: Step, after: Step): String =
    when (note) {
        is EditNote.Shortened ->
            "Shortened to ${durationMin(note.minutes)}: " +
                when (after.mode) {
                    Mode.HEAT ->
                        "Heat at ${formatTemp(after.temp)}, fan ${fanPercent(after.fan)} % runs at most that long."
                    else -> "${after.mode.label} runs at most that long."
                }
        is EditNote.ClockTimeDropped ->
            "${note.mode.label} has no clock time: kept as ${durationMin(note.minutes)}" +
                ((before.timing as? Timing.Until)?.let {
                        " instead of until ${clock(it.minuteOfDay)}"
                    })
                    .orEmpty() +
                "."
    }

data class Issue(val text: String, val refused: Boolean)

/**
 * Each step's [issue]. A refused program never runs, so then only the refusals are worth saying:
 * the plan past a refused step (cut to 12 h) describes nothing that happens.
 */
fun issues(steps: List<Step>, planned: List<PlannedStep>): List<Issue?> {
    val all = steps.mapIndexed { i, s -> issue(s, planned[i], last = i == steps.lastIndex) }
    return if (planned.any { it.refused }) all.map { it?.takeIf(Issue::refused) } else all
}

/** Why [p] won't run as the program says; [last] drops the knock-on sentence. */
fun issue(step: Step, p: PlannedStep, last: Boolean): Issue? {
    val until = step.timing as? Timing.Until
    return when {
        p.refused && until != null ->
            Issue(
                "The BedJet refuses to start: until ${clock(until.minuteOfDay)} is ${durationMin(p.wanted)} after the previous step ends (${clock(p.plannedFrom)}). The limit is 12 h.",
                refused = true,
            )
        p.cut -> {
            val why =
                if (step.mode == Mode.HEAT)
                    " (heat limit at ${formatTemp(step.temp)}, fan ${fanPercent(step.fan)} %)"
                else ""
            val later =
                if (last) ""
                else " Every later step starts ${durationMin(p.wanted - p.runs)} early."
            Issue("The BedJet stops this step after ${durationMin(p.runs)}$why.$later", false)
        }
        until != null && p.from != p.plannedFrom ->
            Issue(
                "Ends ${clock(p.to)}, not ${clock(until.minuteOfDay)}: an earlier step stops early, and clock times become durations when the program starts.",
                refused = false,
            )
        else -> null
    }
}

data class WindowText(val title: String, val sub: String, val refused: Boolean)

/** [windows] is `startWindows(steps)`, passed in because it plans the program 1440 times. */
fun windowText(
    steps: List<Step>,
    windows: List<ClockWindow>,
    planned: List<PlannedStep>,
    start: Int,
): WindowText {
    val targets = steps.mapNotNull { (it.timing as? Timing.Until)?.minuteOfDay }
    if (targets.isEmpty())
        return WindowText(
            "Can start any time",
            "No clock-time steps, so start time and the BedJet clock don't matter.",
            refused = false,
        )
    val title =
        when {
            windows == listOf(ClockWindow(0, DAY - 1)) -> "Can start any time"
            windows.isEmpty() -> "Can't start at any time"
            else ->
                "Can start between " +
                    windows.joinToString(", or ") { "${clock(it.from)} and ${clock(it.to)}" }
        }
    val refused = planned.any { it.refused }
    return WindowText(
        if (refused) "Refused at ${clock(start)} · ${title.replaceFirstChar(Char::lowercase)}"
        else title,
        "Clock-time steps (${targets.joinToString { "until ${clock(it)}" }}) must be at most 12 h after the step before them, or the BedJet refuses to start.",
        refused,
    )
}

/** The strip's spans: where each step really runs, in minutes since the start. */
fun spans(steps: List<Step>, planned: List<PlannedStep>, start: Int): List<Span> =
    steps.mapIndexed { i, s ->
        Span(i, s, planned[i].from - start, planned[i].to - start)
    }

fun stripLabel(i: Int, step: Step) = "Step ${i + 1}, ${step.mode.label}: open it"

/** How many row midpoints lie above [y]: the slot a dragged row drops into. */
fun insertionSlot(mids: List<Float>, y: Float) = mids.count { it < y }

/** The index a row dragged from [from] lands on when dropped into [slot]. */
fun dropTarget(from: Int, slot: Int) = if (slot > from) slot - 1 else slot
