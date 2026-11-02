package link.thesymbolic.jetcommand.protocol.shell

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import link.thesymbolic.jetcommand.protocol.core.DeviceId
import link.thesymbolic.jetcommand.protocol.core.LibraryEntry
import link.thesymbolic.jetcommand.protocol.core.Origin
import link.thesymbolic.jetcommand.protocol.core.Program
import link.thesymbolic.jetcommand.protocol.core.ProgramId
import link.thesymbolic.jetcommand.protocol.core.ProgramRevision
import link.thesymbolic.jetcommand.protocol.core.Slot
import link.thesymbolic.jetcommand.protocol.core.SlotState
import link.thesymbolic.jetcommand.protocol.ports.LibraryStore

class InMemoryLibraryStore : LibraryStore {
    private data class Data(
        val entries: Map<ProgramId, LibraryEntry> = emptyMap(),
        val revisions: Map<ProgramId, List<ProgramRevision>> = emptyMap(),
        val slots: Map<Pair<DeviceId, Slot>, SlotState> = emptyMap(),
    )

    private val data = MutableStateFlow(Data())

    override fun entries(): Flow<List<LibraryEntry>> = data.map { d ->
        d.entries.values.sortedByDescending { it.current.createdAt }
    }

    override fun allRevisions(): Flow<List<ProgramRevision>> = data.map { d ->
        d.revisions.values.flatten()
    }

    override fun slots(device: DeviceId): Flow<List<SlotState>> = data.map { d ->
        d.slots.values.filter { it.device == device }.sortedBy { it.slot.index }
    }

    override suspend fun entry(id: ProgramId) = data.value.entries[id]

    override suspend fun revisions(programId: ProgramId) =
        data.value.revisions[programId].orEmpty().sortedByDescending { it.rev }

    override suspend fun slot(device: DeviceId, slot: Slot) = data.value.slots[device to slot]

    override suspend fun createProgram(program: Program, origin: Origin, at: Long): LibraryEntry {
        val id = ProgramId(UUID.randomUUID().toString())
        val rev = ProgramRevision(id, 1, program, at, origin)
        val entry = LibraryEntry(id, rev)
        data.value =
            data.value.let {
                it.copy(
                    entries = it.entries + (id to entry),
                    revisions = it.revisions + (id to listOf(rev)),
                )
            }
        return entry
    }

    override suspend fun addRevision(
        programId: ProgramId,
        program: Program,
        origin: Origin,
        at: Long,
        makeCurrent: Boolean,
    ): ProgramRevision {
        val d = data.value
        val entry = requireNotNull(d.entries[programId]) { "No program $programId" }
        val revs = d.revisions[programId].orEmpty()
        val rev = ProgramRevision(programId, revs.maxOf { it.rev } + 1, program, at, origin)
        data.value =
            d.copy(
                entries =
                    if (makeCurrent) d.entries + (programId to entry.copy(current = rev))
                    else d.entries,
                revisions = d.revisions + (programId to revs + rev),
            )
        return rev
    }

    override suspend fun setCurrent(programId: ProgramId, rev: Int) {
        val d = data.value
        val entry = requireNotNull(d.entries[programId]) { "No program $programId" }
        val target =
            requireNotNull(d.revisions[programId]?.firstOrNull { it.rev == rev }) {
                "No revision $rev of $programId"
            }
        data.value = d.copy(entries = d.entries + (programId to entry.copy(current = target)))
    }

    override suspend fun setDeleted(programId: ProgramId, at: Long?) {
        val d = data.value
        val entry = requireNotNull(d.entries[programId]) { "No program $programId" }
        data.value = d.copy(entries = d.entries + (programId to entry.copy(deletedAt = at)))
    }

    override suspend fun putSlot(state: SlotState) {
        data.value =
            data.value.let { it.copy(slots = it.slots + ((state.device to state.slot) to state)) }
    }
}
