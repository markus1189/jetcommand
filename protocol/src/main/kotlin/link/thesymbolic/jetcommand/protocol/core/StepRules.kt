package link.thesymbolic.jetcommand.protocol.core

/*
 * The program editor's rules. What the unit enforces comes from its ESP32 firmware (build 146,
 * V1.20 line): at start it checks only clock-time steps (`FUN_400ece98`), then hands each step to
 * the heater board like a manual setting (`FUN_400e65bc`), so a step longer than
 * [maxRuntimeMinutes] ends early instead of being refused [inferred: the board's firmware is
 * encrypted]. The editor therefore never offers a duration above that cap.
 */

/** The modes a step can take, in the editor's order. */
val EDITOR_MODES = listOf(Mode.TURBO, Mode.HEAT, Mode.EXT_HEAT, Mode.COOL, Mode.DRY, Mode.WAIT)

/** The official app's convention; the firmware converts a clock time for every mode. */
fun untilAllowed(mode: Mode) = mode == Mode.WAIT || mode == Mode.EXT_HEAT || mode == Mode.COOL

/** The durations the editor offers for [step]'s mode, temperature and fan. */
fun durationRange(step: Step): IntRange = 1..maxRuntimeMinutes(step.mode, step.temp, step.fan)

/** A step as the unit will run it when the program starts at a given minute of the day. */
data class PlannedStep(
    /** Minutes from the start day's midnight to where the program says this step starts. */
    val plannedFrom: Int,
    /** Where it really starts, once earlier steps ended early. */
    val from: Int,
    /** The length the program asks for; a clock-time step's is fixed at start. */
    val wanted: Int,
    /** The length it runs: [wanted], cut to [maxRuntimeMinutes]. */
    val runs: Int,
    /** The unit refuses to start: a clock-time step more than 12 h after the step before. */
    val refused: Boolean,
) {
    val to
        get() = from + runs

    val cut
        get() = runs < wanted
}

private const val MINUTES_PER_DAY = 24 * 60
private const val UNTIL_LIMIT_MINUTES = 12 * 60

fun planProgram(steps: List<Step>, startMinute: Int): List<PlannedStep> {
    var planned = startMinute
    var actual = startMinute
    return steps.map { step ->
        val timing = step.timing
        val wanted =
            when (timing) {
                is Timing.For -> timing.totalMinutes
                is Timing.Until -> Math.floorMod(timing.minuteOfDay - planned, MINUTES_PER_DAY)
            }
        val runs = minOf(wanted, maxRuntimeMinutes(step.mode, step.temp, step.fan))
        PlannedStep(
                planned,
                actual,
                wanted,
                runs,
                refused = timing is Timing.Until && wanted > UNTIL_LIMIT_MINUTES,
            )
            .also {
                planned += wanted
                actual += runs
            }
    }
}

/** Start minutes [from]..[to], inclusive; wraps past midnight when [to] < [from]. */
data class ClockWindow(val from: Int, val to: Int)

/** When the unit accepts a start: the whole day without clock-time steps, else possibly none. */
fun startWindows(steps: List<Step>): List<ClockWindow> {
    val ok = BooleanArray(MINUTES_PER_DAY) { m -> planProgram(steps, m).none { it.refused } }
    val firstBad = ok.indexOfFirst { !it }
    if (firstBad < 0) return listOf(ClockWindow(0, MINUTES_PER_DAY - 1))
    val windows = mutableListOf<ClockWindow>()
    var open: Int? = null
    for (k in 1..MINUTES_PER_DAY) {
        val m = (firstBad + k) % MINUTES_PER_DAY
        if (ok[m] && open == null) open = m
        if (!ok[m] && open != null) {
            windows += ClockWindow(open, Math.floorMod(m - 1, MINUTES_PER_DAY))
            open = null
        }
    }
    return windows
}

sealed interface StepEdit {
    data class SetMode(val mode: Mode) : StepEdit

    data class SetFan(val fan: Int) : StepEdit

    data class SetTemp(val temp: Int) : StepEdit

    data class SetDuration(val minutes: Int) : StepEdit

    data class SetUntil(val minuteOfDay: Int) : StepEdit

    /** A clock-time step keeps the length it has from where it starts by plan. */
    data object ToDuration : StepEdit

    /** A duration step ends where it ends by plan. */
    data object ToUntil : StepEdit
}

/** Why a step changed beyond the edit itself. */
sealed interface EditNote {
    /** The mode, temperature and fan allow at most [minutes]. */
    data class Shortened(val minutes: Int) : EditNote

    /** [mode] has no clock time, so the step runs [minutes] instead. */
    data class ClockTimeDropped(val mode: Mode, val minutes: Int) : EditNote
}

data class Edited(val step: Step, val note: EditNote? = null)

// A mode switch from one without a temperature lands on these, not on a range bound.
private val START_TEMPS =
    mapOf(Mode.HEAT to 64, Mode.EXT_HEAT to 62, Mode.COOL to 40, Mode.DRY to 50)

/** [at] is [step]'s entry from [planProgram], for converting between duration and clock time. */
fun applyEdit(step: Step, edit: StepEdit, at: PlannedStep): Edited {
    val (edited, note) =
        when (edit) {
            is StepEdit.SetMode -> setMode(step, edit.mode, at)
            is StepEdit.SetFan -> Edited(step.copy(fan = edit.fan.coerceIn(0, 19)))
            is StepEdit.SetTemp ->
                Edited(
                    if (!step.mode.usesTemperature) step
                    else
                        TEMP_RANGES.getValue(step.mode).let {
                            step.copy(temp = edit.temp.coerceIn(it.min, it.max))
                        }
                )
            else -> Edited(step.copy(timing = editTiming(step, edit, at)))
        }
    val timing = edited.timing
    val cap = maxRuntimeMinutes(edited.mode, edited.temp, edited.fan)
    if (timing is Timing.For && timing.totalMinutes > cap)
        return Edited(edited.copy(timing = forMinutes(cap)), EditNote.Shortened(cap))
    return Edited(edited, note)
}

private fun setMode(step: Step, mode: Mode, at: PlannedStep): Edited {
    val moved = step.copy(mode = mode, temp = tempFor(step, mode))
    if (step.timing !is Timing.Until || untilAllowed(mode)) return Edited(moved)
    val minutes = at.wanted.coerceIn(1, Limits.MAX_STEP_MINUTES)
    return Edited(
        moved.copy(timing = forMinutes(minutes)),
        EditNote.ClockTimeDropped(mode, minutes),
    )
}

private fun editTiming(step: Step, edit: StepEdit, at: PlannedStep): Timing {
    val t = step.timing
    return when (edit) {
        is StepEdit.SetDuration ->
            if (t is Timing.For) forMinutes(edit.minutes.coerceAtLeast(1)) else t
        is StepEdit.SetUntil -> if (t is Timing.Until) clockAt(edit.minuteOfDay) else t
        StepEdit.ToDuration ->
            if (t is Timing.Until) forMinutes(at.wanted.coerceIn(1, Limits.MAX_STEP_MINUTES)) else t
        StepEdit.ToUntil ->
            if (t is Timing.For && untilAllowed(step.mode)) clockAt(at.plannedFrom + at.wanted)
            else t
        else -> t
    }
}

private fun tempFor(step: Step, mode: Mode): Int =
    when {
        !mode.usesTemperature -> TEMP_RANGES.getValue(mode).min
        !step.mode.usesTemperature -> START_TEMPS.getValue(mode)
        else -> TEMP_RANGES.getValue(mode).let { step.temp.coerceIn(it.min, it.max) }
    }

private fun forMinutes(minutes: Int) = Timing.For(minutes / 60, minutes % 60)

private fun clockAt(minute: Int) =
    Math.floorMod(minute, MINUTES_PER_DAY).let { Timing.Until(it / 60, it % 60) }
