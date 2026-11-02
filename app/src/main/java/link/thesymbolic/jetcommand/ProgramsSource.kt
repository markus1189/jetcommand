package link.thesymbolic.jetcommand

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import link.thesymbolic.jetcommand.protocol.core.DeviceId
import link.thesymbolic.jetcommand.protocol.core.LibraryEntry
import link.thesymbolic.jetcommand.protocol.core.Program
import link.thesymbolic.jetcommand.protocol.core.ProgramId
import link.thesymbolic.jetcommand.protocol.core.ProgramRevision
import link.thesymbolic.jetcommand.protocol.core.Resolution
import link.thesymbolic.jetcommand.protocol.core.Slot
import link.thesymbolic.jetcommand.protocol.core.SlotView
import link.thesymbolic.jetcommand.protocol.core.StartedSlot

/**
 * Everything the program screens read or do; `LibraryPrograms` in the app, [FakePrograms] for
 * previews and the emulator.
 */
interface ProgramsSource {
    /** The device whose slots are shown; null before any was used. */
    val device: StateFlow<DeviceId?>
    /** A link is up, so slot actions can reach the unit. */
    val live: StateFlow<Boolean>
    /** Refused or failed actions, as text for the user; each delivered once. */
    val errors: Flow<String>
    val started: StateFlow<StartedSlot?>

    fun entries(): Flow<List<LibraryEntry>>

    fun slots(device: DeviceId): Flow<List<SlotView>>

    suspend fun revisions(programId: ProgramId): List<ProgramRevision>

    fun run(slot: Slot, programId: ProgramId)

    fun push(slot: Slot, programId: ProgramId)

    fun resolve(slot: Slot, resolution: Resolution)

    fun keep(slot: Slot)

    fun restore(programId: ProgramId)

    fun delete(programId: ProgramId)

    /** Saves an edited [program] as [programId]'s new current revision. */
    suspend fun saveEdit(programId: ProgramId, program: Program): ProgramRevision
}
