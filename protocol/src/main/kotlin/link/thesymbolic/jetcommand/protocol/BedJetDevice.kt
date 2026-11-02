// Port of the web app's `BedJetDevice`: the operations above single frames.
package link.thesymbolic.jetcommand.protocol

import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How [BedJetDevice.startProgram] ended. */
sealed interface StartOutcome {
    /** [status] is the first one that showed the program running. */
    data class Started(val status: Status) : StartOutcome

    data class Failed(val reason: StartFailure) : StartOutcome
}

/** Why a program didn't start; [code] and [text] for surfaces that report errors. */
enum class StartFailure(val code: ErrorCode, val text: String = code.text) {
    CLOCK_NOT_SET(ErrorCode.CLOCK_NOT_SET),
    STEP_TOO_LONG(ErrorCode.STEP_TOO_LONG),
    UNCONFIRMED(ErrorCode.TIMEOUT, "Your BedJet didn't confirm that the program started."),
}

/**
 * [clock] is the phone's local time, synced to the unit before a program starts; [now] (ms)
 * debounces auto-acks. Same threading rule as [BedJetLink].
 */
class BedJetDevice(
    val link: BedJetLink,
    scope: CoroutineScope,
    val autoAck: Boolean = true,
    private val clock: () -> LocalTime = { LocalTime.now() },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : SlotReader {
    private val scope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    var status: StatusUpdate? = null
        private set

    /** A [store] is writing; commands sent now wait until it is done. */
    var storing = false
        private set

    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 16)
    val notifications: SharedFlow<Notification> = _notifications

    // Re-emitted after this device has seen them, so a waiter that resumes finds the ack already
    // started.
    private val seen =
        MutableSharedFlow<StatusUpdate>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private var shownNotification: Notification = Notification.None
    private val ackedAt = mutableMapOf<Notification, Long>()
    private var ackJob: Job? = null

    init {
        this.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.statuses.collect {
                onStatus(it)
                seen.emit(it)
            }
        }
    }

    suspend fun send(bytes: ByteArray) = link.send(bytes)

    suspend fun press(key: Key) = send(Command.press(key))

    suspend fun setMode(mode: SettableMode) = send(Command.setMode(mode))

    suspend fun setTemperature(halfC: Int) = send(Command.setTemperature(halfC))

    suspend fun setFan(step: Int) = send(Command.setFan(step))

    suspend fun setRuntime(minutes: Int) = send(Command.setRuntime(minutes))

    suspend fun syncClock(time: LocalTime = clock()) =
        send(Command.setClock(time.hour, time.minute))

    suspend fun ack() = send(Command.ack())

    suspend fun setLed(on: Boolean) = send(Command.setLed(on))

    suspend fun setMuted(muted: Boolean) = send(Command.setMuted(muted))

    suspend fun recallMemory(memory: MemorySlot) = send(Command.recallMemory(memory))

    suspend fun saveMemory(memory: MemorySlot) = send(Command.saveMemory(memory))

    suspend fun stopProgram() = press(Key.OFF)

    suspend fun deleteProgram(slot: Slot) = send(Command.deleteProgram(slot))

    suspend fun rename(target: NameTarget, name: String) = send(Command.nameFrame(target, name))

    suspend fun deviceName() = Reply.parseText(link.query(Query.DEVICE_NAME))

    // Three NUL-padded 16-byte version strings; only the first fits a 22-byte 2005 read.
    suspend fun firmware(): String {
        val b = link.query(Query.FIRMWARE)
        return (b.indices step 16)
            .map { Reply.parseText(b.copyOfRange(it, minOf(it + 16, b.size))) }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
    }

    suspend fun memoryNames() = Reply.parseNames(link.query(Query.MEMORY_NAMES)).take(3)

    override suspend fun programNames(): List<String?> =
        Reply.parseNames(link.query(Query.PROGRAM_NAMES)).take(Limits.SLOTS)

    override suspend fun programSteps(slot: Slot): List<Step> {
        val steps = mutableListOf<Step>()
        for (index in 0 until Limits.FRAGMENTS_PER_SLOT) {
            val fragment =
                Reply.parseFragment(link.query(Query.fragment(slot, index)), index)
                    ?: throw BedJetException(ErrorCode.BAD_RESPONSE)
            steps += fragment.steps
            if (steps.size >= fragment.total || fragment.steps.isEmpty()) return steps
        }
        throw BedJetException(ErrorCode.BAD_RESPONSE)
    }

    override suspend fun storedPrograms(): List<Program?> {
        val names = programNames()
        return Slot.all.map { slot ->
            val name = names.getOrNull(slot.index) ?: return@map null
            programSteps(slot).takeIf { it.isNotEmpty() }?.let { Program(name, it) }
        }
    }

    /**
     * Writes, then reads back: [ErrorCode.VERIFY_FAILED] unless the unit holds exactly [program].
     */
    suspend fun store(
        program: Program,
        slot: Slot,
        nowMinutes: Int = clock().let { it.hour * 60 + it.minute },
    ): Program {
        validateProgram(program.steps, nowMinutes).firstOrNull()?.let { first ->
            val where = if (first.index >= 0) "Step ${first.index + 1}: " else ""
            throw BedJetException(first.code, "$where${first.message}")
        }
        val name = Command.sanitizeName(program.name)
        storing = true
        try {
            link.sendAll(
                Command.writeProgram(slot, program.steps) +
                    Command.nameFrame(NameTarget.ProgramName(slot), name)
            )
        } finally {
            storing = false
        }
        val back = programSteps(slot)
        val names = programNames()
        if (back != program.steps || names[slot.index] != name)
            throw BedJetException(ErrorCode.VERIFY_FAILED)
        return Program(name, back)
    }

    /** Syncs the clock and starts [slot]; transport failures still throw. */
    suspend fun startProgram(
        slot: Slot,
        time: LocalTime = clock(),
        timeoutMs: Long = 4000,
    ): StartOutcome {
        val pending = status?.status?.extra?.notification
        if (pending == Notification.ProgramNeedsClock || pending == Notification.ProgramTooLong)
            ack()
        syncClock(time)
        val (status, extra) =
            coroutineScope {
                val outcome =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        waitForExtra(timeoutMs) {
                            it.programStep > 0 ||
                                it.notification == Notification.ProgramNeedsClock ||
                                it.notification == Notification.ProgramTooLong
                        }
                    }
                send(Command.startProgram(slot))
                outcome.await()
            } ?: return StartOutcome.Failed(StartFailure.UNCONFIRMED)
        if (extra.programStep > 0) return StartOutcome.Started(status)
        if (autoAck) ackJob?.join() else ack()
        return StartOutcome.Failed(
            if (extra.notification == Notification.ProgramNeedsClock) StartFailure.CLOCK_NOT_SET
            else StartFailure.STEP_TOO_LONG
        )
    }

    /** The first status after this call that matches, or null after [timeoutMs]. */
    suspend fun waitForStatus(
        timeoutMs: Long = 4000,
        predicate: (StatusUpdate) -> Boolean,
    ): StatusUpdate? = withTimeoutOrNull(timeoutMs) { seen.first(predicate) }

    /** Like [waitForStatus], over statuses whose [StatusExtra] is fresh. */
    private suspend fun waitForExtra(
        timeoutMs: Long,
        predicate: (StatusExtra) -> Boolean,
    ): Pair<Status, StatusExtra>? =
        withTimeoutOrNull(timeoutMs) {
            seen
                .mapNotNull { u ->
                    u.status.extra?.takeIf { u.extraFresh && predicate(it) }?.let { u.status to it }
                }
                .first()
        }

    // The official app acknowledges every notification at once and shows its text once per code
    // until byte 29 returns to 0 (MainFragment.java:370-386).
    private fun onStatus(update: StatusUpdate) {
        status = update
        val extra = update.status.extra
        if (extra == null || !update.extraFresh) return
        val code = extra.notification
        if (code == Notification.None) {
            shownNotification = code
            return
        }
        if (code != shownNotification) {
            shownNotification = code
            _notifications.tryEmit(code)
        }
        if (!autoAck || link.state.value != LinkState.Ready) return
        val t = now()
        if (ackedAt[code]?.let { t - it < 2000 } == true) return
        ackedAt[code] = t
        ackJob = scope.launch {
            try {
                ack()
            } catch (_: BedJetException) {
                ackedAt.remove(code)
            }
        }
    }
}
