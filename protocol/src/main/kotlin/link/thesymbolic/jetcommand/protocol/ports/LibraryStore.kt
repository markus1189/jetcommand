package link.thesymbolic.jetcommand.protocol.ports

import kotlinx.coroutines.flow.Flow
import link.thesymbolic.jetcommand.protocol.core.DeviceId
import link.thesymbolic.jetcommand.protocol.core.LibraryEntry
import link.thesymbolic.jetcommand.protocol.core.Origin
import link.thesymbolic.jetcommand.protocol.core.Program
import link.thesymbolic.jetcommand.protocol.core.ProgramId
import link.thesymbolic.jetcommand.protocol.core.ProgramRevision
import link.thesymbolic.jetcommand.protocol.core.Slot
import link.thesymbolic.jetcommand.protocol.core.SlotState

/**
 * Persistence only; every rule lives in [ProgramLibrary]. Room in the app, [InMemoryLibraryStore]
 * in tests.
 */
interface LibraryStore {
    /** Includes tombstoned entries; most recently edited first. */
    fun entries(): Flow<List<LibraryEntry>>

    /** Every revision of every program, tombstoned ones included, in no particular order. */
    fun allRevisions(): Flow<List<ProgramRevision>>

    fun slots(device: DeviceId): Flow<List<SlotState>>

    suspend fun entry(id: ProgramId): LibraryEntry?

    /** Newest first. */
    suspend fun revisions(programId: ProgramId): List<ProgramRevision>

    suspend fun slot(device: DeviceId, slot: Slot): SlotState?

    suspend fun createProgram(program: Program, origin: Origin, at: Long): LibraryEntry

    suspend fun addRevision(
        programId: ProgramId,
        program: Program,
        origin: Origin,
        at: Long,
        makeCurrent: Boolean,
    ): ProgramRevision

    suspend fun setCurrent(programId: ProgramId, rev: Int)

    suspend fun setDeleted(programId: ProgramId, at: Long?)

    suspend fun putSlot(state: SlotState)
}
