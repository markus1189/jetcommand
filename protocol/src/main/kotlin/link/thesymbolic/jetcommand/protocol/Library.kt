// Program library and slot sync: ROADMAP.md "Program library". The library is the source of truth; the unit's
// three slots are a per-device cache of what is loaded.
package link.thesymbolic.jetcommand.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.util.UUID

/** Hex SHA-256 over what the unit stores: the sanitized name bytes, then [Command.encodeStep] of every step. */
fun Program.fingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(Command.sanitizeName(name).toByteArray(Charsets.US_ASCII))
    digest.update(0) // a name byte is never 0
    digest.update(encodeSteps(steps))
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Steps as the 5-byte wire encoding, the form the library stores. */
fun encodeSteps(steps: List<Step>): ByteArray =
    steps.fold(ByteArray(0)) { out, step -> out + Command.encodeStep(step) }

fun decodeSteps(bytes: ByteArray): List<Step> {
    require(bytes.size % 5 == 0) { "Steps are 5 bytes each, got ${bytes.size} bytes" }
    return (bytes.indices step 5).map { o ->
        fun u(i: Int) = bytes[o + i].toInt() and 0xff
        Step(
            mode = requireNotNull(Mode.of(u(0))) { "Unknown mode ${u(0)}" },
            fan = u(1),
            temp = u(2),
            hours = u(3) and 0x7f,
            minutes = u(4),
            clock = u(3) and 0x80 != 0,
        )
    }
}

enum class Origin { BLANK, TEMPLATE, EDIT, KEPT_FROM_SLOT, OUTSIDE }

/** Immutable; [rev] counts from 1 per program. */
data class ProgramRevision(
    val programId: String,
    val rev: Int,
    val program: Program,
    val createdAt: Long,
    val origin: Origin,
    val note: String? = null,
)

data class LibraryEntry(val id: String, val current: ProgramRevision, val deletedAt: Long? = null)

/** What the app wrote to a slot, by revision, with the fingerprint of the read-back; [at] orders the run picker. */
data class PushRecord(val programId: String, val rev: Int, val fingerprint: String, val at: Long = 0)

/**
 * One slot of one device. [program] null = empty; [readAt] null = never read. [pending] is set before a write's
 * first fragment and cleared once the read-back settles it.
 */
data class SlotState(
    val device: String,
    val slot: Int,
    val program: Program?,
    val readAt: Long?,
    val pushed: PushRecord? = null,
    val pending: PushRecord? = null,
)

sealed interface SlotStatus {
    data object Unknown : SlotStatus
    data object Empty : SlotStatus
    data class InSync(val entry: LibraryEntry) : SlotStatus
    /** The slot holds [slotRev] of [entry], which has been edited since. */
    data class Older(val entry: LibraryEntry, val slotRev: Int) : SlotStatus
    /** Linked to [entry], but holds content saved as the non-current [outsideRev]. */
    data class ChangedOutside(val entry: LibraryEntry, val outsideRev: Int) : SlotStatus
    /** Linked to an entry that was deleted from the library. */
    data class Deleted(val entry: LibraryEntry) : SlotStatus
    data object NotInLibrary : SlotStatus
    /** A write of [target] didn't settle: the slot holds something else. */
    data class Interrupted(val target: PushRecord) : SlotStatus
}

data class SlotView(val state: SlotState, val status: SlotStatus)

enum class Resolution { TAKE_THEIRS, KEEP_MINE, SPLIT_OFF }

/** Persistence only; every rule lives in [ProgramLibrary]. Room in the app, [InMemoryLibraryStore] in tests. */
interface LibraryStore {
    /** Includes tombstoned entries; most recently edited first. */
    fun entries(): Flow<List<LibraryEntry>>
    fun slots(device: String): Flow<List<SlotState>>
    suspend fun entry(id: String): LibraryEntry?
    /** Newest first. */
    suspend fun revisions(programId: String): List<ProgramRevision>
    suspend fun slot(device: String, slot: Int): SlotState?
    suspend fun createProgram(program: Program, origin: Origin, at: Long): LibraryEntry
    suspend fun addRevision(programId: String, program: Program, origin: Origin, at: Long, makeCurrent: Boolean): ProgramRevision
    suspend fun setCurrent(programId: String, rev: Int)
    suspend fun setDeleted(programId: String, at: Long?)
    suspend fun putSlot(state: SlotState)
}

/**
 * The rules: reconciling slot reads, the write pipeline, conflicts and the run picker. [device] strings are a
 * BLE address, or "demo" for the Demo BedJet.
 */
class ProgramLibrary(private val store: LibraryStore, private val now: () -> Long = System::currentTimeMillis) {
    fun entries(): Flow<List<LibraryEntry>> = store.entries()

    /** Always three, slot order. */
    fun slots(device: String): Flow<List<SlotView>> =
        combine(store.slots(device), store.entries()) { states, _ -> states }
            .map { states -> (0 until Limits.SLOTS).map { view(states.firstOrNull { s -> s.slot == it } ?: blank(device, it)) } }

    suspend fun revisions(programId: String): List<ProgramRevision> = store.revisions(programId)

    /** Reads names and steps of all slots and reconciles them; call on every Ready. */
    suspend fun refresh(device: String, bedJet: BedJetDevice) {
        // Each query takes the link's queue on its own, so a user command waits behind one query, not the refresh.
        val programs = bedJet.storedPrograms()
        programs.forEachIndexed { slot, program -> reconcile(device, slot, program) }
    }

    /** Validate, re-read the slot and compare to the cache, save an orphan it holds, write, verify. */
    suspend fun push(device: String, bedJet: BedJetDevice, slot: Int, programId: String) {
        if (slot !in 0 until Limits.SLOTS) throw BedJetException(ErrorCode.INVALID_PROGRAM, "Choose program slot 1, 2 or 3.")
        if ((bedJet.status?.status?.extra?.programStep ?: 0) > 0) {
            throw BedJetException(ErrorCode.INVALID_PROGRAM, "Stop the program first: a running program's slot can't be written.")
        }
        val entry = store.entry(programId) ?: throw BedJetException(ErrorCode.INVALID_PROGRAM, "This program is not in the library.")
        if (entry.deletedAt != null) throw BedJetException(ErrorCode.INVALID_PROGRAM, "This program was deleted; restore it first.")

        val cached = store.slot(device, slot)
        val onUnit = readSlot(bedJet, slot)
        if (cached?.readAt == null || onUnit != cached.program) {
            val hadCache = cached?.readAt != null
            reconcile(device, slot, onUnit)
            if (hadCache) throw BedJetException(ErrorCode.SLOT_CHANGED)
        }
        if (onUnit != null && !inLibrary(onUnit.fingerprint())) {
            store.createProgram(onUnit, Origin.KEPT_FROM_SLOT, now())
        }

        val target = entry.current
        val state = store.slot(device, slot) ?: blank(device, slot)
        store.putSlot(state.copy(pending = PushRecord(programId, target.rev, target.program.fingerprint(), now())))
        val stored = bedJet.store(target.program, slot)
        store.putSlot(
            state.copy(
                program = stored,
                readAt = now(),
                pushed = PushRecord(programId, target.rev, stored.fingerprint(), now()),
                pending = null,
            ),
        )
    }

    /** Best slot first: holds the current revision, an older one, empty, in sync oldest push, anything else. */
    suspend fun rankSlots(device: String, programId: String): List<Int> {
        val views = slots(device).first()
        fun rank(v: SlotView): Pair<Int, Long> = when (val s = v.status) {
            is SlotStatus.InSync -> if (s.entry.id == programId) 0 to 0L else 3 to (v.state.pushed?.at ?: 0L)
            is SlotStatus.Older -> if (s.entry.id == programId) 1 to 0L else 4 to 0L
            SlotStatus.Empty -> 2 to 0L
            else -> 4 to 0L
        }
        return views.sortedWith(compareBy({ rank(it).first }, { rank(it).second }, { it.state.slot })).map { it.state.slot }
    }

    /** [push] unless the slot already holds the current revision, then [BedJetDevice.startProgram]. */
    suspend fun run(device: String, bedJet: BedJetDevice, slot: Int, programId: String) {
        val status = view(store.slot(device, slot) ?: blank(device, slot)).status
        if (!(status is SlotStatus.InSync && status.entry.id == programId)) push(device, bedJet, slot, programId)
        bedJet.startProgram(slot)
    }

    suspend fun resolve(device: String, slot: Int, resolution: Resolution) {
        val state = store.slot(device, slot) ?: blank(device, slot)
        val status = view(state).status
        check(status is SlotStatus.ChangedOutside) { "Slot $slot has no outside change to resolve: $status" }
        val program = state.program!!
        val fp = program.fingerprint()
        val id = status.entry.id
        val pushed = when (resolution) {
            Resolution.TAKE_THEIRS -> {
                store.setCurrent(id, status.outsideRev)
                PushRecord(id, status.outsideRev, fp, now())
            }
            // The outside revision then counts as an older revision of the entry.
            Resolution.KEEP_MINE -> PushRecord(id, status.outsideRev, fp, now())
            Resolution.SPLIT_OFF -> {
                val split = store.createProgram(program, Origin.OUTSIDE, now())
                PushRecord(split.id, split.current.rev, fp, now())
            }
        }
        store.putSlot(state.copy(pushed = pushed))
    }

    suspend fun keep(device: String, slot: Int): LibraryEntry {
        val state = store.slot(device, slot) ?: blank(device, slot)
        val status = view(state).status
        check(status == SlotStatus.NotInLibrary) { "Slot $slot is not an unknown program: $status" }
        val entry = store.createProgram(state.program!!, Origin.KEPT_FROM_SLOT, now())
        store.putSlot(state.copy(pushed = PushRecord(entry.id, entry.current.rev, state.program.fingerprint(), now())))
        return entry
    }

    suspend fun create(program: Program, origin: Origin = Origin.BLANK): LibraryEntry = store.createProgram(program, origin, now())
    suspend fun delete(programId: String) = store.setDeleted(programId, now())
    suspend fun restore(programId: String) = store.setDeleted(programId, null)

    private fun blank(device: String, slot: Int) = SlotState(device, slot, program = null, readAt = null)

    private suspend fun readSlot(bedJet: BedJetDevice, slot: Int): Program? {
        val name = bedJet.programNames().getOrNull(slot)
        val steps = bedJet.programSteps(slot)
        return if (name == null || steps.isEmpty()) null else Program(name, steps)
    }

    private suspend fun inLibrary(fp: String) =
        store.entries().first().any { e -> store.revisions(e.id).any { it.program.fingerprint() == fp } }

    /** Live entries whose current revision holds [fp], most recently edited first. */
    private suspend fun currentMatch(fp: String, except: String? = null) =
        store.entries().first().firstOrNull { it.deletedAt == null && it.id != except && it.current.program.fingerprint() == fp }

    private suspend fun reconcile(device: String, slot: Int, program: Program?) {
        val old = store.slot(device, slot) ?: blank(device, slot)
        val t = now()
        var state = old.copy(program = program, readAt = t)
        val fp = program?.fingerprint()
        val pending = state.pending
        if (pending != null) {
            if (fp != pending.fingerprint) return store.putSlot(state) // stays Interrupted
            state = state.copy(pushed = pending.copy(at = t), pending = null)
        }
        if (program == null || fp == null) return store.putSlot(state)
        val pushed = state.pushed
        state = when {
            pushed == null -> currentMatch(fp)?.let { state.copy(pushed = PushRecord(it.id, it.current.rev, fp, t)) } ?: state
            pushed.fingerprint == fp -> state
            else -> {
                val linked = store.entry(pushed.programId)
                val own = linked?.let { e -> store.revisions(e.id).firstOrNull { it.program.fingerprint() == fp } }
                val other = currentMatch(fp, except = pushed.programId)
                when {
                    linked == null -> other?.let { state.copy(pushed = PushRecord(it.id, it.current.rev, fp, t)) }
                        ?: state.copy(pushed = null)
                    own != null && (own.rev == linked.current.rev || own.origin != Origin.OUTSIDE) ->
                        state.copy(pushed = PushRecord(linked.id, own.rev, fp, t))
                    other != null -> state.copy(pushed = PushRecord(other.id, other.current.rev, fp, t))
                    own != null -> state // already saved as an outside revision
                    else -> {
                        store.addRevision(linked.id, program, Origin.OUTSIDE, t, makeCurrent = false)
                        state
                    }
                }
            }
        }
        store.putSlot(state)
    }

    private suspend fun view(state: SlotState): SlotView = SlotView(state, status(state))

    private suspend fun status(state: SlotState): SlotStatus {
        if (state.readAt == null) return SlotStatus.Unknown
        val fp = state.program?.fingerprint()
        state.pending?.let { if (it.fingerprint != fp) return SlotStatus.Interrupted(it) }
        if (fp == null) return SlotStatus.Empty
        val pushed = state.pushed ?: return SlotStatus.NotInLibrary
        val entry = store.entry(pushed.programId) ?: return SlotStatus.NotInLibrary
        if (entry.deletedAt != null) return SlotStatus.Deleted(entry)
        if (entry.current.program.fingerprint() == fp) return SlotStatus.InSync(entry)
        if (pushed.fingerprint == fp) return SlotStatus.Older(entry, pushed.rev)
        val outside = store.revisions(entry.id).firstOrNull { it.origin == Origin.OUTSIDE && it.program.fingerprint() == fp }
        return if (outside != null) SlotStatus.ChangedOutside(entry, outside.rev) else SlotStatus.NotInLibrary
    }
}

class InMemoryLibraryStore : LibraryStore {
    private data class Data(
        val entries: Map<String, LibraryEntry> = emptyMap(),
        val revisions: Map<String, List<ProgramRevision>> = emptyMap(),
        val slots: Map<Pair<String, Int>, SlotState> = emptyMap(),
    )

    private val data = MutableStateFlow(Data())

    override fun entries(): Flow<List<LibraryEntry>> =
        data.map { d -> d.entries.values.sortedByDescending { it.current.createdAt } }

    override fun slots(device: String): Flow<List<SlotState>> =
        data.map { d -> d.slots.values.filter { it.device == device }.sortedBy { it.slot } }

    override suspend fun entry(id: String) = data.value.entries[id]
    override suspend fun revisions(programId: String) = data.value.revisions[programId].orEmpty().sortedByDescending { it.rev }
    override suspend fun slot(device: String, slot: Int) = data.value.slots[device to slot]

    override suspend fun createProgram(program: Program, origin: Origin, at: Long): LibraryEntry {
        val id = UUID.randomUUID().toString()
        val rev = ProgramRevision(id, 1, program, at, origin)
        val entry = LibraryEntry(id, rev)
        data.value = data.value.let { it.copy(entries = it.entries + (id to entry), revisions = it.revisions + (id to listOf(rev))) }
        return entry
    }

    override suspend fun addRevision(programId: String, program: Program, origin: Origin, at: Long, makeCurrent: Boolean): ProgramRevision {
        val d = data.value
        val entry = requireNotNull(d.entries[programId]) { "No program $programId" }
        val revs = d.revisions[programId].orEmpty()
        val rev = ProgramRevision(programId, revs.maxOf { it.rev } + 1, program, at, origin)
        data.value = d.copy(
            entries = if (makeCurrent) d.entries + (programId to entry.copy(current = rev)) else d.entries,
            revisions = d.revisions + (programId to revs + rev),
        )
        return rev
    }

    override suspend fun setCurrent(programId: String, rev: Int) {
        val d = data.value
        val entry = requireNotNull(d.entries[programId]) { "No program $programId" }
        val target = requireNotNull(d.revisions[programId]?.firstOrNull { it.rev == rev }) { "No revision $rev of $programId" }
        data.value = d.copy(entries = d.entries + (programId to entry.copy(current = target)))
    }

    override suspend fun setDeleted(programId: String, at: Long?) {
        val d = data.value
        val entry = requireNotNull(d.entries[programId]) { "No program $programId" }
        data.value = d.copy(entries = d.entries + (programId to entry.copy(deletedAt = at)))
    }

    override suspend fun putSlot(state: SlotState) {
        data.value = data.value.let { it.copy(slots = it.slots + ((state.device to state.slot) to state)) }
    }
}
