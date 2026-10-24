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
    }

    /** [detail] read as a [ProcessEvent] once, here, for [Kind.PROCESS]; null for other kinds. */
    val process: ProcessEvent? = if (kind == Kind.PROCESS) ProcessEvent.parse(detail) else null

    companion object {
        fun process(at: Long, event: ProcessEvent) = NightEvent(at, Kind.PROCESS, event.detail)
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
    private var nextSampleAt = Long.MIN_VALUE
    private var everReady = false
    private var userDisconnect = false
    private var lostAt: Long? = null
    private var mode: Mode? = null
    private var target: Int? = null
    private var fan: Int? = null
    private var step: Int? = null
    private var notification: Int? = null
    private var shutdown = 0

    init {
        // One writer keeps the store in event order even when append suspends on another thread.
        scope.launch { for (e in queue) store.append(e) }
        scope.launch { for (s in sampleQueue) store.appendSample(s) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous: LinkState = link.state.value
            link.state.collect { state ->
                onState(previous, state)
                previous = state
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { link.statuses.collect(::onStatus) }
    }

    /** Call before [BedJetLink.disconnect], so the drop is recorded as asked for, not lost. */
    fun userDisconnecting() {
        userDisconnect = true
    }

    fun command(bytes: ByteArray) =
        record(NightEvent.Kind.COMMAND, "${describeCommand(bytes)} (${bytes.toHex()})")

    private fun onState(previous: LinkState, state: LinkState) {
        if (state == LinkState.Ready && previous != LinkState.Ready) {
            val since = lostAt
            if (everReady && since != null)
                record(
                    NightEvent.Kind.RECONNECTED,
                    "after ${(clock() - since) / 1000} s",
                    link.reconnectAttempt,
                )
            else record(NightEvent.Kind.CONNECTED, link.deviceName ?: "BedJet")
            everReady = true
            lostAt = null
            userDisconnect = false
        } else if (previous == LinkState.Ready && state != LinkState.Ready) {
            if (userDisconnect) {
                record(NightEvent.Kind.DISCONNECTED, "by the app")
                everReady = false
            } else {
                lostAt = clock()
                record(NightEvent.Kind.LOST, link.lastDropReason ?: "unknown")
            }
        }
        if (state is LinkState.Disconnected && state.gaveUp)
            record(NightEvent.Kind.GAVE_UP, "reconnect stopped")
    }

    private fun onStatus(update: StatusUpdate) {
        val s = update.status
        val now = clock()
        if (now >= nextSampleAt) {
            sampleQueue.trySend(
                NightSample(now, s.ambientTemp, s.actualTemp, s.targetTemp, s.fanStep, s.mode)
            )
            nextSampleAt = now + sampleMs
        }
        recordSettings(s)
        val x = s.extra ?: return
        if (update.extraFresh) recordExtra(x)
    }

    private fun recordSettings(s: Status) {
        if (s.mode != mode)
            record(NightEvent.Kind.MODE, s.mode.label, s.mode.ordinal).also { mode = s.mode }
        if (s.targetTemp != target)
            record(NightEvent.Kind.TARGET, formatTemp(s.targetTemp), s.targetTemp).also {
                target = s.targetTemp
            }
        if (s.fanStep != fan)
            record(NightEvent.Kind.FAN, "${s.fanPercent}%", s.fanStep).also { fan = s.fanStep }
        if (s.shutdownReason != shutdown) {
            shutdown = s.shutdownReason
            if (shutdown != 0) record(NightEvent.Kind.SHUTDOWN, "reason $shutdown", shutdown)
        }
    }

    private fun recordExtra(x: StatusExtra) {
        if (x.programStep != step) {
            if (x.programStep != 0 || step != null) {
                record(
                    NightEvent.Kind.PROGRAM_STEP,
                    if (x.programStep == 0) "none" else "step ${x.programStep}",
                    x.programStep,
                )
            }
            step = x.programStep
        }
        if (x.notification != notification) {
            if (x.notification != 0 || notification != null) {
                record(
                    NightEvent.Kind.NOTIFICATION,
                    Notification.TEXT[x.notification] ?: "code ${x.notification}",
                    x.notification,
                )
            }
            notification = x.notification
        }
    }

    private fun record(kind: NightEvent.Kind, detail: String, value: Int? = null) {
        queue.trySend(NightEvent(clock(), kind, detail, value))
    }
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
                Button.slotOfStartProgram(code)?.let { "start program ${it.index}" }
                    ?: Button.slotOfDeleteProgram(code)?.let { "delete program ${it.index}" }
                    ?: "button"
        }
