// Program library and slot sync: ROADMAP.md "Program library". The library is the source of truth;
// the unit's three slots are a per-device cache of what is loaded.
package link.thesymbolic.jetcommand.protocol

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Hex SHA-256 over what the unit stores: the sanitized name bytes, then [encodeStep] of every step.
 */
fun Program.fingerprint(): Fingerprint {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(Command.sanitizeName(name).toByteArray(Charsets.US_ASCII))
    digest.update(0) // a name byte is never 0
    digest.update(encodeSteps(steps))
    return Fingerprint(digest.digest().joinToString("") { "%02x".format(it) })
}

/** A BLE address, or "demo" for the Demo BedJet. */
@JvmInline value class DeviceId(val value: String)

/** A library entry's UUID. */
@JvmInline value class ProgramId(val value: String)

/** [Program.fingerprint]: equal for programs the unit stores identically. */
@JvmInline value class Fingerprint(val hex: String)

/** Steps as the 5-byte wire encoding, the form the library stores. */
fun encodeSteps(steps: List<Step>): ByteArray =
    steps.fold(ByteArray(0)) { out, step -> out + encodeStep(step) }

/** Null unless [bytes] are whole steps of known modes; stored bytes may come from a newer build. */
fun decodeSteps(bytes: ByteArray): List<Step>? {
    if (bytes.size % 5 != 0) return null
    return (bytes.indices step 5).map { o -> decodeStep(bytes, o) ?: return null }
}

enum class Origin {
    BLANK,
    TEMPLATE,
    EDIT,
    KEPT_FROM_SLOT,
    OUTSIDE,
}

/** Immutable; [rev] counts from 1 per program. */
data class ProgramRevision(
    val programId: ProgramId,
    val rev: Int,
    val program: Program,
    val createdAt: Long,
    val origin: Origin,
    val note: String? = null,
)

data class LibraryEntry(
    val id: ProgramId,
    val current: ProgramRevision,
    val deletedAt: Long? = null,
)

/**
 * What the app wrote to a slot, by revision, with the fingerprint of the read-back; [at] orders the
 * run picker.
 */
data class PushRecord(
    val programId: ProgramId,
    val rev: Int,
    val fingerprint: Fingerprint,
    val at: Long = 0,
)

/**
 * One slot of one device. [program] null = empty; [readAt] null = never read. [pending] is set
 * before a write's first fragment and cleared once the read-back settles it.
 */
data class SlotState(
    val device: DeviceId,
    val slot: Slot,
    val program: Program?,
    val readAt: Long?,
    val pushed: PushRecord? = null,
    val pending: PushRecord? = null,
)

sealed interface SlotStatus {
    /** The slot holds the current revision of library entry [programId]. */
    fun holdsCurrent(programId: ProgramId) = this is InSync && entry.id == programId

    data object Unknown : SlotStatus

    data object Empty : SlotStatus

    data class InSync(val entry: LibraryEntry) : SlotStatus

    /** The slot holds [slotRev] of [entry], which has been edited since. */
    data class Older(val entry: LibraryEntry, val slotRev: Int) : SlotStatus

    /** Linked to [entry], but holds [program], saved as the non-current [outsideRev]. */
    data class ChangedOutside(val entry: LibraryEntry, val outsideRev: Int, val program: Program) :
        SlotStatus

    /** Linked to an entry that was deleted from the library. */
    data class Deleted(val entry: LibraryEntry) : SlotStatus

    data class NotInLibrary(val program: Program) : SlotStatus

    /** A write of [target] didn't settle: the slot holds something else. */
    data class Interrupted(val target: PushRecord) : SlotStatus
}

data class SlotView(val state: SlotState, val status: SlotStatus)

data class StartedSlot(val device: DeviceId, val slot: Slot)

enum class Resolution {
    TAKE_THEIRS,
    KEEP_MINE,
    SPLIT_OFF,
}

/** Why [ProgramLibrary] declined an action; the app words each one. */
enum class Refusal {
    /** Writing the slot of a running program is undefined on the unit (ROADMAP checklist 13). */
    PROGRAM_RUNNING,
    NOT_IN_LIBRARY,
    /** Tombstoned: restore it first. */
    DELETED,
    /** The unit held something other than the cache; reconciled now, for the user to check. */
    SLOT_CHANGED,
    /** The slot left the state the action was offered for, e.g. resolved meanwhile. */
    STALE,
    /** Written, but the unit didn't start it: [StartFailure]. */
    CLOCK_NOT_SET,
    STEP_TOO_LONG,
    START_UNCONFIRMED,
}

/** A library rule's answer; transport failures still throw from [BedJetDevice]. */
sealed interface LibraryOutcome<out T> {
    data class Done<out T>(val value: T) : LibraryOutcome<T>

    data class Refused(val reason: Refusal) : LibraryOutcome<Nothing>
}

private val done = LibraryOutcome.Done(Unit)

/**
 * One snapshot of the library, tombstoned entries included, indexed by fingerprint: a slot's status
 * needs no store query and no hashing beyond the slot's own program.
 */
class Catalog(
    /** Most recently edited first. */
    val entries: List<LibraryEntry>,
    revisions: List<ProgramRevision>,
    fingerprint: (ProgramRevision) -> Fingerprint = { it.program.fingerprint() },
) {
    private val byId = entries.associateBy { it.id }
    // The current revisions too: the two store flows can be one write apart.
    private val known =
        (revisions.filter { it.programId in byId } + entries.map { it.current }).distinctBy {
            it.programId to it.rev
        }
    private val fingerprints = known.associate { (it.programId to it.rev) to fingerprint(it) }
    private val current = entries.associate { it.id to fingerprints[it.id to it.current.rev] }
    private val byFingerprint: Map<Fingerprint, List<ProgramRevision>> =
        known
            .sortedByDescending { it.rev }
            .groupBy { fingerprints.getValue(it.programId to it.rev) }

    fun entry(id: ProgramId): LibraryEntry? = byId[id]

    fun currentFingerprint(entry: LibraryEntry): Fingerprint? = current[entry.id]

    /** Any revision of any entry holds [fp]. */
    fun holds(fp: Fingerprint): Boolean = fp in byFingerprint

    /** Revisions of [programId] holding [fp], newest first. */
    fun revisions(programId: ProgramId, fp: Fingerprint): List<ProgramRevision> =
        byFingerprint[fp].orEmpty().filter { it.programId == programId }

    /** The most recently edited live entry other than [except] whose current revision is [fp]. */
    fun currentMatch(fp: Fingerprint, except: ProgramId? = null): LibraryEntry? =
        entries.firstOrNull {
            it.deletedAt == null && it.id != except && current[it.id] == fp
        }
}

fun slotStatus(state: SlotState, catalog: Catalog): SlotStatus {
    if (state.readAt == null) return SlotStatus.Unknown
    val program = state.program
    val fp = program?.fingerprint()
    state.pending?.let { if (it.fingerprint != fp) return SlotStatus.Interrupted(it) }
    if (program == null || fp == null) return SlotStatus.Empty
    val pushed = state.pushed ?: return SlotStatus.NotInLibrary(program)
    val entry = catalog.entry(pushed.programId) ?: return SlotStatus.NotInLibrary(program)
    if (entry.deletedAt != null) return SlotStatus.Deleted(entry)
    if (catalog.currentFingerprint(entry) == fp) return SlotStatus.InSync(entry)
    if (pushed.fingerprint == fp) return SlotStatus.Older(entry, pushed.rev)
    val outside = catalog.revisions(entry.id, fp).firstOrNull { it.origin == Origin.OUTSIDE }
    return if (outside != null) SlotStatus.ChangedOutside(entry, outside.rev, program)
    else SlotStatus.NotInLibrary(program)
}

/** Always three, slot order; a slot never stored reads as never read. */
fun slotViews(device: DeviceId, states: List<SlotState>, catalog: Catalog): List<SlotView> =
    Slot.all.map { slot ->
        val state = states.firstOrNull { it.slot == slot } ?: blankSlot(device, slot)
        SlotView(state, slotStatus(state, catalog))
    }

/**
 * Where to put [programId], best first: holds its current revision, an older one, empty, in sync
 * with another program (oldest push first), anything else.
 */
fun rankSlots(views: List<SlotView>, programId: ProgramId): List<Slot> {
    fun rank(v: SlotView): Pair<Int, Long> =
        when (val s = v.status) {
            is SlotStatus.InSync ->
                if (s.holdsCurrent(programId)) 0 to 0L else 3 to (v.state.pushed?.at ?: 0L)
            is SlotStatus.Older -> if (s.entry.id == programId) 1 to 0L else 4 to 0L
            SlotStatus.Empty -> 2 to 0L
            else -> 4 to 0L
        }
    return views
        .sortedWith(compareBy({ rank(it).first }, { rank(it).second }, { it.state.slot.index }))
        .map { it.state.slot }
}

private fun blankSlot(device: DeviceId, slot: Slot) =
    SlotState(device, slot, program = null, readAt = null)

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

/** The rules: reconciling slot reads, the write pipeline, conflicts and the run picker. */
class ProgramLibrary(
    private val store: LibraryStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _started = MutableStateFlow<StartedSlot?>(null)
    /**
     * The slot this app last started, until [clearStarted]: the status names only the step, not the
     * slot.
     */
    val started: StateFlow<StartedSlot?> = _started.asStateFlow()

    // Revisions are immutable, so (program, rev) keys the SHA-256 for good.
    private val fingerprints = ConcurrentHashMap<Pair<ProgramId, Int>, Fingerprint>()

    private fun fingerprintOf(r: ProgramRevision): Fingerprint =
        fingerprints.getOrPut(r.programId to r.rev) { r.program.fingerprint() }

    fun noteStarted(device: DeviceId, slot: Slot) {
        _started.value = StartedSlot(device, slot)
    }

    fun clearStarted() {
        _started.value = null
    }

    fun entries(): Flow<List<LibraryEntry>> = store.entries()

    fun catalog(): Flow<Catalog> =
        combine(store.entries(), store.allRevisions()) { entries, revisions ->
            Catalog(entries, revisions, ::fingerprintOf)
        }

    /** Always three, slot order. */
    fun slots(device: DeviceId): Flow<List<SlotView>> =
        combine(store.slots(device), catalog()) { states, catalog ->
            slotViews(device, states, catalog)
        }

    suspend fun revisions(programId: ProgramId): List<ProgramRevision> = store.revisions(programId)

    /** Reads names and steps of all slots and reconciles them; call on every Ready. */
    suspend fun refresh(device: DeviceId, bedJet: BedJetDevice) {
        // Each query takes the link's queue on its own, so a user command waits behind one query,
        // not the refresh.
        val programs = bedJet.storedPrograms()
        for ((slot, program) in Slot.all.zip(programs)) reconcile(device, slot, program)
    }

    /**
     * Validate, re-read the slot and compare to the cache, save an orphan it holds, write, verify.
     */
    suspend fun push(
        device: DeviceId,
        bedJet: BedJetDevice,
        slot: Slot,
        programId: ProgramId,
    ): LibraryOutcome<Unit> {
        if ((bedJet.status?.status?.extra?.programStep ?: 0) > 0)
            return LibraryOutcome.Refused(Refusal.PROGRAM_RUNNING)
        val entry = store.entry(programId) ?: return LibraryOutcome.Refused(Refusal.NOT_IN_LIBRARY)
        if (entry.deletedAt != null) return LibraryOutcome.Refused(Refusal.DELETED)

        val cached = store.slot(device, slot)
        val onUnit = readSlot(bedJet, slot)
        if (cached?.readAt == null || onUnit != cached.program) {
            val hadCache = cached?.readAt != null
            reconcile(device, slot, onUnit)
            if (hadCache) return LibraryOutcome.Refused(Refusal.SLOT_CHANGED)
        }
        if (onUnit != null && !snapshot().holds(onUnit.fingerprint())) {
            store.createProgram(onUnit, Origin.KEPT_FROM_SLOT, now())
        }

        val target = entry.current
        val state = store.slot(device, slot) ?: blankSlot(device, slot)
        store.putSlot(
            state.copy(pending = PushRecord(programId, target.rev, fingerprintOf(target), now()))
        )
        val stored = bedJet.store(target.program, slot)
        store.putSlot(
            state.copy(
                program = stored,
                readAt = now(),
                pushed = PushRecord(programId, target.rev, stored.fingerprint(), now()),
                pending = null,
            )
        )
        return done
    }

    /**
     * [push] unless the slot already holds the current revision, then [BedJetDevice.startProgram].
     */
    suspend fun run(
        device: DeviceId,
        bedJet: BedJetDevice,
        slot: Slot,
        programId: ProgramId,
    ): LibraryOutcome<Unit> {
        val status = slotStatus(store.slot(device, slot) ?: blankSlot(device, slot), snapshot())
        if (!status.holdsCurrent(programId)) {
            val pushed = push(device, bedJet, slot, programId)
            if (pushed is LibraryOutcome.Refused) return pushed
        }
        val started = bedJet.startProgram(slot)
        if (started is StartOutcome.Failed)
            return LibraryOutcome.Refused(
                when (started.reason) {
                    StartFailure.CLOCK_NOT_SET -> Refusal.CLOCK_NOT_SET
                    StartFailure.STEP_TOO_LONG -> Refusal.STEP_TOO_LONG
                    StartFailure.UNCONFIRMED -> Refusal.START_UNCONFIRMED
                }
            )
        noteStarted(device, slot)
        return done
    }

    suspend fun resolve(
        device: DeviceId,
        slot: Slot,
        resolution: Resolution,
    ): LibraryOutcome<Unit> {
        val state = store.slot(device, slot) ?: blankSlot(device, slot)
        val status =
            slotStatus(state, snapshot()) as? SlotStatus.ChangedOutside
                ?: return LibraryOutcome.Refused(Refusal.STALE)
        val fp = status.program.fingerprint()
        val id = status.entry.id
        val pushed =
            when (resolution) {
                Resolution.TAKE_THEIRS -> {
                    store.setCurrent(id, status.outsideRev)
                    PushRecord(id, status.outsideRev, fp, now())
                }
                // The outside revision then counts as an older revision of the entry.
                Resolution.KEEP_MINE -> PushRecord(id, status.outsideRev, fp, now())
                Resolution.SPLIT_OFF -> {
                    val split = store.createProgram(status.program, Origin.OUTSIDE, now())
                    PushRecord(split.id, split.current.rev, fp, now())
                }
            }
        store.putSlot(state.copy(pushed = pushed))
        return done
    }

    /** Saves the unknown program a slot holds to the library and links the slot to it. */
    suspend fun keep(device: DeviceId, slot: Slot): LibraryOutcome<LibraryEntry> {
        val state = store.slot(device, slot) ?: blankSlot(device, slot)
        val status =
            slotStatus(state, snapshot()) as? SlotStatus.NotInLibrary
                ?: return LibraryOutcome.Refused(Refusal.STALE)
        val entry = store.createProgram(status.program, Origin.KEPT_FROM_SLOT, now())
        store.putSlot(
            state.copy(
                pushed =
                    PushRecord(entry.id, entry.current.rev, status.program.fingerprint(), now())
            )
        )
        return LibraryOutcome.Done(entry)
    }

    suspend fun create(program: Program, origin: Origin = Origin.BLANK): LibraryEntry =
        store.createProgram(program, origin, now())

    suspend fun delete(programId: ProgramId) = store.setDeleted(programId, now())

    suspend fun restore(programId: ProgramId) = store.setDeleted(programId, null)

    private suspend fun snapshot(): Catalog = catalog().first()

    private suspend fun readSlot(bedJet: BedJetDevice, slot: Slot): Program? {
        val name = bedJet.programNames().getOrNull(slot.index)
        val steps = bedJet.programSteps(slot)
        return if (name == null || steps.isEmpty()) null else Program(name, steps)
    }

    private suspend fun reconcile(device: DeviceId, slot: Slot, program: Program?) {
        val old = store.slot(device, slot) ?: blankSlot(device, slot)
        val t = now()
        var state = old.copy(program = program, readAt = t)
        val fp = program?.fingerprint()
        val pending = state.pending
        if (pending != null) {
            if (fp != pending.fingerprint) return store.putSlot(state) // stays Interrupted
            state = state.copy(pushed = pending.copy(at = t), pending = null)
        }
        if (program == null || fp == null) return store.putSlot(state)
        val catalog = snapshot()
        val pushed = state.pushed
        state =
            when {
                pushed == null ->
                    catalog.currentMatch(fp)?.let {
                        state.copy(pushed = PushRecord(it.id, it.current.rev, fp, t))
                    } ?: state
                pushed.fingerprint == fp -> state
                else -> relink(catalog, state, pushed, program, fp, t)
            }
        store.putSlot(state)
    }

    /** The slot changed since [pushed]: find which program it now holds. */
    private suspend fun relink(
        catalog: Catalog,
        state: SlotState,
        pushed: PushRecord,
        program: Program,
        fp: Fingerprint,
        t: Long,
    ): SlotState {
        val linked = catalog.entry(pushed.programId)
        val own = linked?.let { catalog.revisions(it.id, fp).firstOrNull() }
        val other = catalog.currentMatch(fp, except = pushed.programId)
        return when {
            // A deleted program doesn't adopt new content: Restore would bring back
            // something not on the unit.
            linked == null || (linked.deletedAt != null && own == null) ->
                other?.let { state.copy(pushed = PushRecord(it.id, it.current.rev, fp, t)) }
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
