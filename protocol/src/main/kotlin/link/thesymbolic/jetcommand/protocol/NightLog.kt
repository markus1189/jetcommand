package link.thesymbolic.jetcommand.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** [value] carries the new number for changes (half °C for [Kind.TARGET], fan step, step, code). */
data class NightEvent(val at: Long, val kind: Kind, val detail: String, val value: Int? = null) {
    enum class Kind {
        CONNECTED,
        LOST,
        RECONNECTED,
        DISCONNECTED,
        GAVE_UP,
        MODE,
        TARGET,
        FAN,
        PROGRAM_STEP,
        NOTIFICATION,
        SHUTDOWN,
        COMMAND,
        /** Our own process and service lifecycle, to tell a killed app from a dropped link. */
        PROCESS,
        /** A command nobody watched run (Routines, launcher): its outcome; [value] 1 ok, 0 not. */
        SHORTCUT,
    }

    /** [detail] read as a [ProcessEvent] once, here, for [Kind.PROCESS]; null for other kinds. */
    val process: ProcessEvent? = if (kind == Kind.PROCESS) ProcessEvent.parse(detail) else null

    companion object {
        fun process(at: Long, event: ProcessEvent) = NightEvent(at, Kind.PROCESS, event.detail)

        /** [error] null: [label] ran. */
        fun shortcut(at: Long, label: String, outcome: RunOutcome) =
            when (outcome) {
                RunOutcome.Ran -> NightEvent(at, Kind.SHORTCUT, label, 1)
                is RunOutcome.Failed -> NightEvent(at, Kind.SHORTCUT, "$label: ${outcome.error}", 0)
                is RunOutcome.Unconfirmed ->
                    NightEvent(at, Kind.SHORTCUT, "$label: ${outcome.note}", 1)
            }
    }
}

/**
 * Temperatures in the status's raw units ([formatTemp]); [room] is the unit's ambient sensor, [air]
 * its outlet.
 */
data class NightSample(
    val at: Long,
    val room: Int,
    val air: Int,
    val target: Int,
    val fanStep: Int,
    val mode: Mode,
)

interface NightLogStore {
    suspend fun append(event: NightEvent)

    suspend fun appendSample(sample: NightSample)

    /** Newest first. */
    fun recent(limit: Int): Flow<List<NightEvent>>

    /** [from] inclusive, [to] exclusive, oldest first. */
    suspend fun events(from: Long, to: Long): List<NightEvent>
}

/**
 * [candidates] not already in [recorded]. The system's exit records outlive our "seen" mark, so
 * re-reading them must not log a kill twice.
 */
fun unrecorded(candidates: List<NightEvent>, recorded: List<NightEvent>): List<NightEvent> {
    val known = recorded.toSet()
    return candidates.filterNot(known::contains)
}

class InMemoryNightLogStore : NightLogStore {
    private val events = MutableStateFlow<List<NightEvent>>(emptyList())
    val all: List<NightEvent>
        get() = events.value

    val samples = mutableListOf<NightSample>()

    override suspend fun append(event: NightEvent) {
        events.value += event
    }

    override suspend fun appendSample(sample: NightSample) {
        samples += sample
    }

    override fun recent(limit: Int) = events.map { it.asReversed().take(limit) }

    override suspend fun events(from: Long, to: Long) =
        events.value.filter { it.at in from until to }.sortedBy { it.at }
}

/** Passes [BedJetTransport.write]s of commands (not queries) to [onCommand] before sending them. */
class TappedTransport(
    private val inner: BedJetTransport,
    private val onCommand: (ByteArray) -> Unit,
) : BedJetTransport by inner {
    override suspend fun write(characteristic: Characteristic, bytes: ByteArray) {
        if (characteristic == Characteristic.COMMAND && bytes.isNotEmpty() && bytes.u(0) != 0x41)
            onCommand(bytes.copyOf())
        inner.write(characteristic, bytes)
    }
}

/**
 * Turns one link's states and statuses into [NightEvent]s: changes only, since the unit notifies
 * ~4/s. Step and notification come from complete packets only; the first status after a reconnect
 * is diffed against the last one before the loss, so what changed in the gap is recorded once.
 * Samples are taken from arriving statuses at most once per [sampleMs], so a dropped link leaves a
 * gap rather than repeated stale values. Same threading rule as [BedJetLink].
 */
class NightRecorder(
    private val link: BedJetLink,
    private val store: NightLogStore,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sampleMs: Long = 60_000,
) {
    private val queue = Channel<NightEvent>(Channel.UNLIMITED)
    private val sampleQueue = Channel<NightSample>(Channel.UNLIMITED)
    private var state = RecorderState(link.state.value)

    init {
        // One writer keeps the store in event order even when append suspends on another thread.
        scope.launch { for (e in queue) store.append(e) }
        scope.launch { for (s in sampleQueue) store.appendSample(s) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.state.collect {
                feed(
                    RecorderInput.Link(
                        clock(),
                        it,
                        link.deviceName,
                        link.reconnectAttempt,
                        link.lastDropReason,
                    )
                )
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.statuses.collect { feed(RecorderInput.Update(clock(), it)) }
        }
    }

    /** Call before [BedJetLink.disconnect], so the drop is recorded as asked for, not lost. */
    fun userDisconnecting() = feed(RecorderInput.UserDisconnecting)

    fun command(bytes: ByteArray) {
        queue.trySend(
            NightEvent(
                clock(),
                NightEvent.Kind.COMMAND,
                "${describeCommand(bytes)} (${bytes.toHex()})",
            )
        )
    }

    private fun feed(input: RecorderInput) {
        val (next, out) = state.next(input, sampleMs)
        state = next
        out.forEach {
            when (it) {
                is RecorderOutput.Event -> queue.trySend(it.event)
                is RecorderOutput.Sample -> sampleQueue.trySend(it.sample)
            }
        }
    }
}

/**
 * What [NightRecorder] remembers between inputs. [link] is the last state seen; [lostAt] is set
 * while a loss awaits its reconnect; the settings are the last values recorded, null before the
 * first status.
 */
internal data class RecorderState(
    val link: LinkState,
    val nextSampleAt: Long? = null,
    val everReady: Boolean = false,
    val userDisconnect: Boolean = false,
    val lostAt: Long? = null,
    val mode: Mode? = null,
    val target: Int? = null,
    val fan: Int? = null,
    val step: Int? = null,
    val notification: Int? = null,
    /**
     * Null after each (re)connect: the unit keeps its last reason, and a repeat stop must still
     * show.
     */
    val shutdown: Int? = null,
)

internal sealed interface RecorderInput {
    /** The link's name, attempt and drop reason as they read when [state] arrived. */
    data class Link(
        val at: Long,
        val state: LinkState,
        val deviceName: String?,
        val reconnectAttempt: Int,
        val dropReason: String?,
    ) : RecorderInput

    data class Update(val at: Long, val update: StatusUpdate) : RecorderInput

    data object UserDisconnecting : RecorderInput
}

internal sealed interface RecorderOutput {
    data class Event(val event: NightEvent) : RecorderOutput

    data class Sample(val sample: NightSample) : RecorderOutput
}

internal fun RecorderState.next(
    input: RecorderInput,
    sampleMs: Long,
): Pair<RecorderState, List<RecorderOutput>> =
    when (input) {
        RecorderInput.UserDisconnecting -> copy(userDisconnect = true) to emptyList()
        is RecorderInput.Link -> onLink(input)
        is RecorderInput.Update -> onUpdate(input, sampleMs)
    }

private class Outputs(private val at: Long) {
    val list = mutableListOf<RecorderOutput>()

    fun event(kind: NightEvent.Kind, detail: String, value: Int? = null) {
        list += RecorderOutput.Event(NightEvent(at, kind, detail, value))
    }
}

private fun RecorderState.onLink(i: RecorderInput.Link): Pair<RecorderState, List<RecorderOutput>> {
    val out = Outputs(i.at)
    var s = copy(link = i.state)
    if (i.state == LinkState.Ready && link != LinkState.Ready) {
        if (everReady && lostAt != null)
            out.event(
                NightEvent.Kind.RECONNECTED,
                "after ${(i.at - lostAt) / 1000} s",
                i.reconnectAttempt,
            )
        else out.event(NightEvent.Kind.CONNECTED, i.deviceName ?: "BedJet")
        s = s.copy(everReady = true, lostAt = null, userDisconnect = false, shutdown = null)
    } else if (link == LinkState.Ready && i.state != LinkState.Ready) {
        if (userDisconnect) {
            out.event(NightEvent.Kind.DISCONNECTED, "by the app")
            s = s.copy(everReady = false)
        } else {
            out.event(NightEvent.Kind.LOST, i.dropReason ?: "unknown")
            s = s.copy(lostAt = i.at)
        }
    }
    if (i.state is LinkState.Disconnected && i.state.gaveUp)
        out.event(NightEvent.Kind.GAVE_UP, "reconnect stopped")
    return s to out.list
}

private fun RecorderState.onUpdate(
    i: RecorderInput.Update,
    sampleMs: Long,
): Pair<RecorderState, List<RecorderOutput>> {
    val out = Outputs(i.at)
    val st = i.update.status
    val sampled = nextSampleAt == null || i.at >= nextSampleAt
    if (sampled)
        out.list +=
            RecorderOutput.Sample(
                NightSample(i.at, st.ambientTemp, st.actualTemp, st.targetTemp, st.fanStep, st.mode)
            )
    out.settings(this, st)
    val x = st.extra?.takeIf { i.update.extraFresh }
    x?.let { out.extra(this, it) }
    return copy(
        nextSampleAt = if (sampled) i.at + sampleMs else nextSampleAt,
        mode = st.mode,
        target = st.targetTemp,
        fan = st.fanStep,
        shutdown = st.shutdownReason,
        step = x?.programStep ?: step,
        notification = x?.notification?.code ?: notification,
    ) to out.list
}

private fun Outputs.settings(was: RecorderState, st: Status) {
    if (st.mode != was.mode) event(NightEvent.Kind.MODE, st.mode.label, st.mode.ordinal)
    if (st.targetTemp != was.target)
        event(NightEvent.Kind.TARGET, formatTemp(st.targetTemp), st.targetTemp)
    if (st.fanStep != was.fan) event(NightEvent.Kind.FAN, "${st.fanPercent}%", st.fanStep)
    if (st.shutdownReason != was.shutdown && st.shutdownReason != 0)
        event(NightEvent.Kind.SHUTDOWN, "reason ${st.shutdownReason}", st.shutdownReason)
}

// A first reading of 0 (no program, no notification) is the resting state, not a change.
private fun Outputs.extra(was: RecorderState, x: StatusExtra) {
    if (x.programStep != was.step && (x.programStep != 0 || was.step != null))
        event(
            NightEvent.Kind.PROGRAM_STEP,
            if (x.programStep == 0) "none" else "step ${x.programStep}",
            x.programStep,
        )
    val n = x.notification
    if (n.code != was.notification && (n != Notification.None || was.notification != null))
        event(NightEvent.Kind.NOTIFICATION, n.text, n.code)
}

private val BUTTON_NAMES =
    mapOf(
        Button.OFF to "off",
        Button.COOL to "cool",
        Button.HEAT to "heat",
        Button.TURBO to "turbo",
        Button.DRY to "dry",
        Button.EXT_HEAT to "ext. heat",
        Button.FAN_UP to "fan up",
        Button.FAN_DOWN to "fan down",
        Button.TEMP_UP_C to "temp up",
        Button.TEMP_DOWN_C to "temp down",
        Button.TEMP_UP_F to "temp up °F",
        Button.TEMP_DOWN_F to "temp down °F",
        Button.LED_ON to "LED on",
        Button.LED_OFF to "LED off",
        Button.MUTE to "mute",
        Button.UNMUTE to "unmute",
        Button.ACK to "ack",
    )

fun describeCommand(b: ByteArray): String {
    if (b.size < 2) return "frame"
    val code = b.u(1)
    return when (b.u(0)) {
        0x01 -> describeButton(code)
        0x02 -> "runtime ${code}:%02d".format(if (b.size > 2) b.u(2) else 0)
        0x03 -> "target ${formatTemp(code)}"
        0x07 -> "fan ${fanPercent(code)}%"
        0x08 -> "clock ${code}:%02d".format(if (b.size > 2) b.u(2) else 0)
        0x40 -> "program fragment"
        else -> "frame"
    }
}

private fun describeButton(code: Int): String =
    BUTTON_NAMES[code]
        ?: when (code) {
            in Button.MEMORY_1..Button.MEMORY_3 -> "recall memory ${code - Button.MEMORY_1 + 1}"
            in Button.SAVE_MEMORY_1..Button.SAVE_MEMORY_3 ->
                "save memory ${code - Button.SAVE_MEMORY_1 + 1}"
            else ->
                Button.slotOfStartProgram(code)?.let { "start program ${it.number}" }
                    ?: Button.slotOfDeleteProgram(code)?.let { "delete program ${it.number}" }
                    ?: "button"
        }
