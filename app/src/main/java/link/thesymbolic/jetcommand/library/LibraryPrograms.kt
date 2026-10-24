package link.thesymbolic.jetcommand.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import link.thesymbolic.jetcommand.BedJetController
import link.thesymbolic.jetcommand.BedJetStack
import link.thesymbolic.jetcommand.ProgramsSource
import link.thesymbolic.jetcommand.protocol.BedJetException
import link.thesymbolic.jetcommand.protocol.ErrorCode
import link.thesymbolic.jetcommand.protocol.LibraryEntry
import link.thesymbolic.jetcommand.protocol.LibraryOutcome
import link.thesymbolic.jetcommand.protocol.LogLevel
import link.thesymbolic.jetcommand.protocol.ProgramLibrary
import link.thesymbolic.jetcommand.protocol.Refusal
import link.thesymbolic.jetcommand.protocol.Resolution
import link.thesymbolic.jetcommand.protocol.Slot
import link.thesymbolic.jetcommand.protocol.attempt

/** What the user reads when [ProgramLibrary] refuses [what] ("keep slot 2"). */
fun refusalText(reason: Refusal, what: String): String =
    when (reason) {
        Refusal.PROGRAM_RUNNING ->
            "Stop the program first: a running program's slot can't be written."
        Refusal.NOT_IN_LIBRARY -> "This program is not in the library."
        Refusal.DELETED -> "This program was deleted; restore it first."
        Refusal.SLOT_CHANGED -> ErrorCode.SLOT_CHANGED.text
        Refusal.STALE -> "Couldn't $what."
    }

/**
 * The real source: [ProgramLibrary] for state, [BedJetController.act] for anything that reaches the
 * unit. [scope] outlives configuration changes (a ViewModel's).
 */
class LibraryPrograms(
    private val library: ProgramLibrary,
    private val controller: BedJetController,
    private val scope: CoroutineScope,
) : ProgramsSource {
    // The remembered unit keeps its cached slots visible while disconnected.
    override val device: StateFlow<String?> =
        combine(controller.deviceId, controller.remembered) { id, r -> id ?: r?.address }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                controller.deviceId.value ?: controller.remembered.value?.address,
            )
    override val live: StateFlow<Boolean> =
        controller.deviceId
            .map { it != null }
            .stateIn(scope, SharingStarted.Eagerly, controller.deviceId.value != null)

    private val entries: StateFlow<List<LibraryEntry>> =
        library.entries().stateIn(scope, SharingStarted.Eagerly, emptyList())

    // A channel, not a SharedFlow: an error raised while no screen collects waits for the next one.
    private val errorChannel = Channel<String>(8, BufferOverflow.DROP_OLDEST)
    override val errors: Flow<String> = errorChannel.receiveAsFlow()
    override val started = library.started

    override fun entries() = entries

    override fun slots(device: String) = library.slots(device)

    override suspend fun revisions(programId: String) = library.revisions(programId)

    private fun refused(what: String, outcome: LibraryOutcome<*>) {
        if (outcome !is LibraryOutcome.Refused) return
        controller.log(LogLevel.WARN, "$what: refused, ${outcome.reason}")
        errorChannel.trySend(refusalText(outcome.reason, what))
    }

    private fun onUnit(
        what: String,
        block: suspend ProgramLibrary.(String, BedJetStack) -> LibraryOutcome<*>,
    ) {
        val id =
            controller.deviceId.value ?: return controller.log(LogLevel.ERROR, "$what: no device")
        controller.act(what) { stack ->
            val outcome =
                try {
                    library.block(id, stack)
                } catch (e: BedJetException) {
                    errorChannel.trySend(e.message ?: e.code.text)
                    throw e
                }
            refused(what, outcome)
        }
    }

    override fun run(slot: Slot, programId: String) =
        onUnit("run program in slot ${slot.number}") { id, s -> run(id, s.device, slot, programId) }

    override fun push(slot: Slot, programId: String) =
        onUnit("load slot ${slot.number}") { id, s -> push(id, s.device, slot, programId) }

    private fun local(what: String, block: suspend ProgramLibrary.() -> LibraryOutcome<*>) {
        scope.launch {
            attempt(
                    onError = {
                        controller.log(
                            LogLevel.ERROR,
                            "$what: ${it::class.simpleName} ${it.message}",
                        )
                        errorChannel.trySend("Couldn't $what.")
                    }
                ) {
                    library.block()
                }
                .onSuccess { refused(what, it) }
        }
    }

    private fun onSlots(what: String, block: suspend ProgramLibrary.(String) -> LibraryOutcome<*>) {
        val id = device.value ?: return
        local(what) { block(id) }
    }

    override fun resolve(slot: Slot, resolution: Resolution) =
        onSlots("resolve slot ${slot.number}") { resolve(it, slot, resolution) }

    override fun keep(slot: Slot) = onSlots("keep slot ${slot.number}") { keep(it, slot) }

    // Library-only: no device needed.
    override fun restore(programId: String) =
        local("restore") { LibraryOutcome.Done(restore(programId)) }

    override fun delete(programId: String) =
        local("delete") { LibraryOutcome.Done(delete(programId)) }
}
