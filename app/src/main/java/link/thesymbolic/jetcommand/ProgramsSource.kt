package link.thesymbolic.jetcommand

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import link.thesymbolic.jetcommand.protocol.LibraryEntry
import link.thesymbolic.jetcommand.protocol.ProgramRevision
import link.thesymbolic.jetcommand.protocol.Resolution
import link.thesymbolic.jetcommand.protocol.Slot
import link.thesymbolic.jetcommand.protocol.SlotView
import link.thesymbolic.jetcommand.protocol.StartedSlot

/**
 * Everything the program screens read or do; `LibraryPrograms` in the app, [FakePrograms] for
 * previews and the emulator.
 */
interface ProgramsSource {
    /** The device whose slots are shown; null before any was used. */
    val device: StateFlow<String?>
    /** A link is up, so slot actions can reach the unit. */
    val live: StateFlow<Boolean>
    /** Refused or failed actions, as text for the user; each delivered once. */
    val errors: Flow<String>
    val started: StateFlow<StartedSlot?>

    fun entries(): Flow<List<LibraryEntry>>

    fun slots(device: String): Flow<List<SlotView>>

    suspend fun revisions(programId: String): List<ProgramRevision>

    fun run(slot: Slot, programId: String)

    fun push(slot: Slot, programId: String)

    fun resolve(slot: Slot, resolution: Resolution)

    fun keep(slot: Slot)

    fun restore(programId: String)

    fun delete(programId: String)
}
