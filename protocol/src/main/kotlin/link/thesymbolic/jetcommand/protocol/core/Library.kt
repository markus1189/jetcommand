// Program library and slot sync: ROADMAP.md "Program library". The library is the source of truth;
// the unit's three slots are a per-device cache of what is loaded.
package link.thesymbolic.jetcommand.protocol.core

import java.security.MessageDigest

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

internal fun blankSlot(device: DeviceId, slot: Slot) =
    SlotState(device, slot, program = null, readAt = null)

/** A slot's new state after a read, and an outside revision to save first, if any. */
data class Reconciled(val state: SlotState, val outsideRevision: OutsideRevision? = null)

/** [program] seen in a slot linked to [programId]; saved as a non-current revision. */
data class OutsideRevision(val programId: ProgramId, val program: Program, val at: Long)

/** Links the [program] just read from [old]'s slot at [t] to the library entry it holds. */
fun reconcileSlot(old: SlotState, program: Program?, catalog: Catalog, t: Long): Reconciled =
    readBack(old, program, t).let {
        if (needsLink(it)) linkSlot(it, catalog, t) else Reconciled(it)
    }

/** [old] holding [program] as read at [t], a pending write settled if the read-back matches it. */
fun readBack(old: SlotState, program: Program?, t: Long): SlotState {
    val state = old.copy(program = program, readAt = t)
    val pending = state.pending ?: return state
    return if (program?.fingerprint() != pending.fingerprint) state // stays Interrupted
    else state.copy(pushed = pending.copy(at = t), pending = null)
}

/** Only these read-backs consult the catalog, so the others skip loading it. */
fun needsLink(state: SlotState): Boolean = state.pending == null && state.program != null

/** Links [state], a [readBack] that [needsLink], to the library entry it holds. */
fun linkSlot(state: SlotState, catalog: Catalog, t: Long): Reconciled {
    val fp = checkNotNull(state.program).fingerprint()
    val pushed = state.pushed
    return when {
        pushed == null ->
            Reconciled(
                catalog.currentMatch(fp)?.let {
                    state.copy(pushed = PushRecord(it.id, it.current.rev, fp, t))
                } ?: state
            )
        pushed.fingerprint == fp -> Reconciled(state)
        else -> relink(catalog, state, pushed, fp, t)
    }
}

/** The slot changed since [pushed] and now holds [fp]: find which program that is. */
private fun relink(
    catalog: Catalog,
    state: SlotState,
    pushed: PushRecord,
    fp: Fingerprint,
    t: Long,
): Reconciled {
    val program = checkNotNull(state.program)
    val linked = catalog.entry(pushed.programId)
    val own = linked?.let { catalog.revisions(it.id, fp).firstOrNull() }
    val other = catalog.currentMatch(fp, except = pushed.programId)
    return when {
        // A deleted program doesn't adopt new content: Restore would bring back
        // something not on the unit.
        linked == null || (linked.deletedAt != null && own == null) ->
            Reconciled(
                other?.let { state.copy(pushed = PushRecord(it.id, it.current.rev, fp, t)) }
                    ?: state.copy(pushed = null)
            )
        own != null && (own.rev == linked.current.rev || own.origin != Origin.OUTSIDE) ->
            Reconciled(state.copy(pushed = PushRecord(linked.id, own.rev, fp, t)))
        other != null ->
            Reconciled(state.copy(pushed = PushRecord(other.id, other.current.rev, fp, t)))
        own != null -> Reconciled(state) // already saved as an outside revision
        else -> Reconciled(state, OutsideRevision(linked.id, program, t))
    }
}
