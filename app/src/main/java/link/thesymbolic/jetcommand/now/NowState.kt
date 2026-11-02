package link.thesymbolic.jetcommand.now

import java.time.LocalTime
import link.thesymbolic.jetcommand.Span
import link.thesymbolic.jetcommand.countdown
import link.thesymbolic.jetcommand.durationMin
import link.thesymbolic.jetcommand.hhmm
import link.thesymbolic.jetcommand.hoursMinutes
import link.thesymbolic.jetcommand.linkText
import link.thesymbolic.jetcommand.prettyName
import link.thesymbolic.jetcommand.protocol.core.DeviceId
import link.thesymbolic.jetcommand.protocol.core.LinkState
import link.thesymbolic.jetcommand.protocol.core.Mode
import link.thesymbolic.jetcommand.protocol.core.Program
import link.thesymbolic.jetcommand.protocol.core.RUNTIME_MINUTES
import link.thesymbolic.jetcommand.protocol.core.SettableMode
import link.thesymbolic.jetcommand.protocol.core.Slot
import link.thesymbolic.jetcommand.protocol.core.SlotView
import link.thesymbolic.jetcommand.protocol.core.StartedSlot
import link.thesymbolic.jetcommand.protocol.core.Status
import link.thesymbolic.jetcommand.protocol.core.StatusUpdate
import link.thesymbolic.jetcommand.protocol.core.Timing
import link.thesymbolic.jetcommand.protocol.core.fanPercent
import link.thesymbolic.jetcommand.protocol.core.formatTemp
import link.thesymbolic.jetcommand.protocol.core.maxRuntimeMinutes
import link.thesymbolic.jetcommand.protocol.core.tempRange
import link.thesymbolic.jetcommand.timeline

/** What the Now screen reads, flattened from the view model and the program library. */
data class NowInputs(
    val stackLabel: String?,
    val link: LinkState,
    val update: StatusUpdate?,
    val statusAgeSeconds: Long,
    /** The remembered unit's label. */
    val remembered: String?,
    val canConnect: Boolean,
    /** By slot index, null where empty. */
    val programNames: List<String?>,
    val device: DeviceId?,
    val started: StartedSlot?,
    val slots: List<SlotView>,
)

data class NowState(val header: String, val canDisconnect: Boolean, val body: NowBody)

sealed interface NowBody {
    /** [reconnect] is the remembered unit's label, null when it can't be connected. */
    data class Offline(val reconnect: String?) : NowBody

    /** A unit is (re)connecting: nothing to control yet. */
    data object Waiting : NowBody

    data class Live(
        val status: Status,
        val summary: String,
        val running: RunningView?,
        val programs: List<ProgramButton>,
    ) : NowBody
}

data class ProgramButton(val slot: Slot, val label: String)

/**
 * [left] is the step's countdown; [next] what follows it. [nowMinute] counts from the program's
 * start, like the [spans].
 */
data class RunningView(
    val title: String,
    val left: String,
    val next: String,
    val spans: List<Span>,
    val nowMinute: Double,
)

sealed interface NowEvent {
    data object Disconnect : NowEvent

    data object ConnectRemembered : NowEvent

    data object OpenDiagnostics : NowEvent

    /** Off, with an undo offered. */
    data object TurnOff : NowEvent

    data class SetMode(val mode: SettableMode) : NowEvent

    data class SetTemperature(val halfC: Int) : NowEvent

    data class SetFan(val step: Int) : NowEvent

    data class StartProgram(val slot: Slot) : NowEvent

    data class SetRuntime(val minutes: Int) : NowEvent
}

fun nowState(inputs: NowInputs, now: LocalTime): NowState {
    val st = inputs.update?.status?.takeIf { inputs.link == LinkState.Ready }
    val header =
        listOfNotNull(
                inputs.stackLabel,
                linkText(inputs.link),
                st?.let { "${inputs.statusAgeSeconds} s ago" },
            )
            .joinToString(" · ")
    val body =
        when {
            st != null -> live(st, inputs, now)
            inputs.stackLabel == null ->
                NowBody.Offline(inputs.remembered?.takeIf { inputs.canConnect })
            else -> NowBody.Waiting
        }
    return NowState(header, canDisconnect = inputs.stackLabel != null, body)
}

private fun live(st: Status, inputs: NowInputs, now: LocalTime): NowBody.Live {
    val step = st.extra?.programStep ?: 0
    val running =
        runningProgram(step, inputs.device, inputs.started, inputs.slots)?.let {
            runningView(it, step, st.remainingSeconds, now)
        }
    val summary =
        listOfNotNull(
                // In a program the shutoff timer is the step's remaining time, which the strip
                // shows; outside one the countdown card does.
                "auto off ${hoursMinutes(st.remainingSeconds)}"
                    .takeIf { st.mode != Mode.STANDBY && running == null && step > 0 },
                "program step $step".takeIf { step > 0 && running == null },
            )
            .joinToString(" · ")
    return NowBody.Live(st, summary, running, programButtons(inputs.programNames))
}

fun programButtons(names: List<String?>): List<ProgramButton> =
    Slot.all.zip(names).mapNotNull { (slot, name) ->
        name?.let { ProgramButton(slot, prettyName(it)) }
    }

/**
 * The program the unit is running, from [device]'s slot cache. The status carries only the step
 * number: the slot this app started, else the one cached program long enough for the step; null
 * when none or several fit.
 */
fun runningProgram(
    step: Int,
    device: DeviceId?,
    started: StartedSlot?,
    slots: List<SlotView>,
): Program? {
    if (step <= 0) return null
    val cached = slots.filter { it.state.device == device }
    fun fits(p: Program?) = p?.takeIf { it.steps.size >= step }
    val mine =
        started
            ?.takeIf { it.device == device }
            ?.let { s -> fits(cached.find { it.state.slot == s.slot }?.state?.program) }
    return mine ?: cached.mapNotNull { fits(it.state.program) }.singleOrNull()
}

/**
 * "Twostep · step 2/5", the step's countdown and what follows: "until 06:30 · then cool 20 min". No
 * clock time for a timed step, it would only be now plus the countdown; null for a program without
 * steps.
 */
fun runningView(p: Program, step: Int, remainingSeconds: Int, now: LocalTime): RunningView? {
    if (p.steps.isEmpty()) return null
    val i = (step - 1).coerceIn(0, p.steps.lastIndex)
    // The step's remaining time puts us inside it; clock-time steps depend on the start, so place
    // it twice.
    fun place(start: LocalTime) =
        timeline(p.steps, start).let { sp -> sp to sp[i].to - remainingSeconds / 60.0 }
    val start = now.minusMinutes(place(now).second.toLong().coerceAtLeast(0))
    val (spans, nowMinute) = place(start)
    val until = (p.steps[i].timing as? Timing.Until)?.let { "until ${hhmm(it.hour, it.minute)}" }
    val then =
        spans.getOrNull(i + 1)?.let {
            "then ${it.step.mode.label.lowercase()} ${durationMin(it.minutes)}"
        } ?: "last step"
    return RunningView(
        "${prettyName(p.name)} · step $step/${p.steps.size}",
        countdown(remainingSeconds),
        listOfNotNull(until, then).joinToString(" · "),
        spans,
        nowMinute,
    )
}

/** A countdown button; [target] is null when the press would change nothing. */
data class Nudge(val label: String, val target: Int?)

/** [fraction] is the time left as a share of the unit's ceiling. */
data class CountdownView(
    val left: String,
    val offAt: String,
    val ceiling: String,
    val fraction: Float,
    val nudges: List<Nudge>,
)

private val NUDGES = listOf(-15, -5, -1, 1, 5, 15)

/**
 * The auto-off card for a manual mode, null in standby and programs (whether moving a step's time
 * works there is unverified). [draft] is the minutes tapped but not yet seen in a status. A step
 * that overshoots a limit becomes "to" the limit, so a tap never silently does less than it says.
 */
fun countdownView(st: Status, draft: Int?, now: LocalTime): CountdownView? {
    if (st.mode == Mode.STANDBY || st.mode == Mode.WAIT || (st.extra?.programStep ?: 0) > 0)
        return null
    val max = maxRuntimeMinutes(st.mode, st.targetTemp, st.fanStep)
    val limits = RUNTIME_MINUTES.first..max
    val current = draft ?: runtimeMinutes(st.remainingSeconds)
    val nudges = NUDGES.map { d ->
        val target = (current + d).coerceIn(limits)
        val label =
            if (target == current + d || target == current) (if (d > 0) "+$d" else "−${-d}")
            else "to ${hoursMinutes(target * 60)}"
        Nudge(label, target.takeIf { it != current })
    }
    val seconds = draft?.let { it * 60 } ?: st.remainingSeconds
    val off = now.plusSeconds(seconds.toLong())
    return CountdownView(
        // The minutes the nudges count from, or 9:50 would read 0:09 with "+1" already at a
        // 10 min ceiling.
        left = hoursMinutes(current * 60),
        offAt = "off at ${hhmm(off.hour, off.minute)}",
        ceiling = "max ${hoursMinutes(max * 60)}",
        fraction = (seconds / (max * 60f)).coerceIn(0f, 1f),
        nudges = nudges,
    )
}

/** The whole minutes to nudge from; a unit set to h:mm reports h:mm:00, so round to nearest. */
fun runtimeMinutes(remainingSeconds: Int) = (remainingSeconds + 30) / 60

/**
 * A slider's view: values are half-°C or fan steps, [index] is the value minus [offset]. Sent but
 * not yet seen in a status shows hollow.
 */
data class FieldView(
    val text: String,
    val sub: String,
    val index: Int,
    val count: Int,
    val offset: Int,
    val enabled: Boolean,
    val hollow: Boolean,
) {
    fun valueAt(index: Int) = offset + index
}

/**
 * The sensor dithers between neighbouring half-degrees; the shown reading moves only once [reading]
 * is a full degree away, so it lags the truth by at most 0.5 °C.
 */
fun steadyActual(shown: Int?, reading: Int): Int =
    if (shown == null || kotlin.math.abs(reading - shown) >= 2) reading else shown

/** Always one decimal, so the line keeps its width when the reading crosses a half-degree. */
fun formatActual(halfC: Int): String = "%.1f°C".format(java.util.Locale.ROOT, halfC / 2.0)

/**
 * [preview] is the value under the finger, [sent] the last one sent; both null when idle. [actual]
 * is the held reading from [steadyActual].
 */
fun tempField(st: Status, preview: Int?, sent: Int?, actual: Int = st.actualTemp): FieldView {
    val (range, _) = tempRange(st.mode, st)
    val unconfirmed = sent?.takeIf { it != st.targetTemp }
    val shown = preview ?: unconfirmed ?: st.targetTemp
    return FieldView(
        text = if (st.mode.usesTemperature || st.mode == Mode.TURBO) formatTemp(shown) else "--",
        sub =
            when {
                st.mode == Mode.TURBO -> "fixed in Turbo"
                unconfirmed != null -> "sending · now ${formatTemp(st.targetTemp)}"
                else -> "actual ${formatActual(actual)}"
            },
        index = shown - range.min,
        count = range.max - range.min + 1,
        offset = range.min,
        enabled = st.mode.usesTemperature,
        hollow = unconfirmed != null,
    )
}

fun fanField(st: Status, preview: Int?, sent: Int?): FieldView {
    val on = st.mode != Mode.STANDBY && st.mode != Mode.WAIT
    val unconfirmed = sent?.takeIf { it != st.fanStep }
    val shown = preview ?: unconfirmed ?: st.fanStep
    return FieldView(
        text = if (on) "${fanPercent(shown)}%" else "--",
        sub = if (unconfirmed != null) "sending · now ${st.fanPercent}%" else "",
        index = shown,
        count = FAN_STEPS,
        offset = 0,
        enabled = on,
        hollow = unconfirmed != null,
    )
}

private const val FAN_STEPS = 20

val MODE_BUTTONS =
    listOf(
        SettableMode.COOL,
        SettableMode.DRY,
        SettableMode.EXT_HEAT,
        SettableMode.HEAT,
        SettableMode.TURBO,
    )

fun shortLabel(mode: Mode) = if (mode == Mode.EXT_HEAT) "Ext" else mode.label

/** The mode an undo of Off returns to; WAIT can't be set by a button, programs aren't restored. */
fun undoTarget(previous: Mode): SettableMode? = SettableMode.of(previous)
