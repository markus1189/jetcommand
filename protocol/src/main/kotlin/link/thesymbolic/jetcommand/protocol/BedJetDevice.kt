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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class DeviceNotification(val code: Int, val text: String)

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
) {
    private val scope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    var status: StatusUpdate? = null
        private set

    private val _notifications = MutableSharedFlow<DeviceNotification>(extraBufferCapacity = 16)
    val notifications: SharedFlow<DeviceNotification> = _notifications

    // Re-emitted after this device has seen them, so a waiter that resumes finds the ack already
    // started.
    private val seen =
        MutableSharedFlow<StatusUpdate>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private var shownNotification = 0
    private val ackedAt = mutableMapOf<Int, Long>()
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

    suspend fun press(code: Int) = send(Command.button(code))

    suspend fun setMode(mode: Mode) = send(Command.setMode(mode))

    suspend fun setTemperature(halfC: Int) = send(Command.setTemperature(halfC))

    suspend fun setFan(step: Int) = send(Command.setFan(step))

    suspend fun setRuntime(minutes: Int) = send(Command.setRuntime(minutes))

    suspend fun syncClock(time: LocalTime = clock()) =
        send(Command.setClock(time.hour, time.minute))

    suspend fun ack() = send(Command.ack())

    suspend fun setLed(on: Boolean) = send(Command.setLed(on))

    suspend fun setMuted(muted: Boolean) = send(Command.setMuted(muted))

    suspend fun recallMemory(n: Int) = send(Command.recallMemory(n))

    suspend fun saveMemory(n: Int) = send(Command.saveMemory(n))

    suspend fun stopProgram() = send(Command.button(Button.OFF))

    suspend fun deleteProgram(slot: Int) = send(Command.deleteProgram(slot))

    suspend fun rename(param: Int, name: String) = send(Command.nameFrame(param, name))

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

    suspend fun programNames() =
        Reply.parseNames(link.query(Query.PROGRAM_NAMES)).take(Limits.SLOTS)

    suspend fun programSteps(slot: Int): List<Step> {
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

    suspend fun storedPrograms(): List<Program?> {
        val names = programNames()
        return (0 until Limits.SLOTS).map { slot ->
            val name = names.getOrNull(slot) ?: return@map null
            programSteps(slot).takeIf { it.isNotEmpty() }?.let { Program(name, it) }
        }
    }

    /**
     * Writes, then reads back: [ErrorCode.VERIFY_FAILED] unless the unit holds exactly [program].
     */
    suspend fun store(
        program: Program,
        slot: Int,
        nowMinutes: Int = clock().let { it.hour * 60 + it.minute },
    ): Program {
        if (slot !in 0 until Limits.SLOTS)
            throw BedJetException(ErrorCode.INVALID_PROGRAM, "Choose program slot 1, 2 or 3.")
        validateProgram(program.steps, nowMinutes).firstOrNull()?.let { first ->
            val where = if (first.index >= 0) "Step ${first.index + 1}: " else ""
            throw BedJetException(first.code, "$where${first.message}")
        }
        val name = Command.sanitizeName(program.name)
        for (frame in Command.writeProgram(slot, program.steps)) send(frame)
        send(Command.nameFrame(NameParam.program(slot), name))
        val back = programSteps(slot)
        val names = programNames()
        if (back != program.steps || names[slot] != name)
            throw BedJetException(ErrorCode.VERIFY_FAILED)
        return Program(name, back)
    }

    /**
     * Syncs the clock and starts [slot]; the status that confirmed it, or the unit's refusal as a
     * typed error.
     */
    suspend fun startProgram(slot: Int, time: LocalTime = clock(), timeoutMs: Long = 4000): Status {
        val pending = status?.status?.extra?.notification
        if (pending == Notification.PROGRAM_NEEDS_CLOCK || pending == Notification.PROGRAM_TOO_LONG)
            ack()
        syncClock(time)
        val update =
            coroutineScope {
                val outcome =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        waitForStatus(timeoutMs) {
                            val extra = it.status.extra
                            it.extraFresh &&
                                extra != null &&
                                (extra.programStep > 0 ||
                                    extra.notification == Notification.PROGRAM_NEEDS_CLOCK ||
                                    extra.notification == Notification.PROGRAM_TOO_LONG)
                        }
                    }
                send(Command.startProgram(slot))
                outcome.await()
            }
                ?: throw BedJetException(
                    ErrorCode.TIMEOUT,
                    "Your BedJet didn't confirm that the program started.",
                )
        val extra = update.status.extra!!
        if (extra.programStep > 0) return update.status
        if (autoAck) ackJob?.join() else ack()
        throw BedJetException(
            if (extra.notification == Notification.PROGRAM_NEEDS_CLOCK) ErrorCode.CLOCK_NOT_SET
            else ErrorCode.STEP_TOO_LONG
        )
    }

    /** The first status after this call that matches, or null after [timeoutMs]. */
    suspend fun waitForStatus(
        timeoutMs: Long = 4000,
        predicate: (StatusUpdate) -> Boolean,
    ): StatusUpdate? = withTimeoutOrNull(timeoutMs) { seen.first(predicate) }

    // The official app acknowledges every notification at once and shows its text once per code
    // until byte 29 returns to 0 (MainFragment.java:370-386).
    private fun onStatus(update: StatusUpdate) {
        status = update
        val extra = update.status.extra
        if (extra == null || !update.extraFresh) return
        val code = extra.notification
        if (code == 0) {
            shownNotification = 0
            return
        }
        if (code != shownNotification) {
            shownNotification = code
            _notifications.tryEmit(
                DeviceNotification(code, Notification.TEXT[code] ?: "BedJet notification $code")
            )
        }
        if (!autoAck || link.state.value != LinkState.Ready) return
        val t = now()
        if (t - (ackedAt[code] ?: Long.MIN_VALUE / 2) < 2000) return
        ackedAt[code] = t
        ackJob = scope.launch {
            try {
                ack()
            } catch (_: BedJetException) {
                ackedAt[code] = Long.MIN_VALUE / 2
            }
        }
    }
}
