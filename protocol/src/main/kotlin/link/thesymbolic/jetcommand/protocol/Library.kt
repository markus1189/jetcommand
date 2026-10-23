// Program library and slot sync: ROADMAP.md "Program library". The library is the source of truth; the unit's
// three slots are a per-device cache of what is loaded.
package link.thesymbolic.jetcommand.protocol

import kotlinx.coroutines.flow.Flow

/** Hex SHA-256 over what the unit stores: the sanitized name bytes, then [Command.encodeStep] of every step. */
fun Program.fingerprint(): String = TODO()

/** Steps as the 5-byte wire encoding, the form the library stores. */
fun encodeSteps(steps: List<Step>): ByteArray = TODO()
fun decodeSteps(bytes: ByteArray): List<Step> = TODO()

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

/** What the app wrote to a slot, by revision, with the fingerprint of the read-back. */
data class PushRecord(val programId: String, val rev: Int, val fingerprint: String)

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

class InMemoryLibraryStore : LibraryStore {
    override fun entries(): Flow<List<LibraryEntry>> = TODO()
    override fun slots(device: String): Flow<List<SlotState>> = TODO()
    override suspend fun entry(id: String): LibraryEntry? = TODO()
    override suspend fun revisions(programId: String): List<ProgramRevision> = TODO()
    override suspend fun slot(device: String, slot: Int): SlotState? = TODO()
    override suspend fun createProgram(program: Program, origin: Origin, at: Long): LibraryEntry = TODO()
    override suspend fun addRevision(programId: String, program: Program, origin: Origin, at: Long, makeCurrent: Boolean): ProgramRevision = TODO()
    override suspend fun setCurrent(programId: String, rev: Int): Unit = TODO()
    override suspend fun setDeleted(programId: String, at: Long?): Unit = TODO()
    override suspend fun putSlot(state: SlotState): Unit = TODO()
}

/**
 * The rules: reconciling slot reads, the write pipeline, conflicts and the run picker. [device] strings are a
 * BLE address, or "demo" for the Demo BedJet.
 */
class ProgramLibrary(private val store: LibraryStore, private val now: () -> Long = System::currentTimeMillis) {
    fun entries(): Flow<List<LibraryEntry>> = TODO()
    /** Always three, slot order. */
    fun slots(device: String): Flow<List<SlotView>> = TODO()
    suspend fun revisions(programId: String): List<ProgramRevision> = TODO()

    /** Reads names and steps of all slots and reconciles them; call on every Ready. */
    suspend fun refresh(device: String, bedJet: BedJetDevice): Unit = TODO()

    /** Validate, re-read the slot and compare to the cache, save an orphan it holds, write, verify. */
    suspend fun push(device: String, bedJet: BedJetDevice, slot: Int, programId: String): Unit = TODO()

    /** Best slot first: holds the current revision, an older one, empty, in sync oldest push, anything else. */
    suspend fun rankSlots(device: String, programId: String): List<Int> = TODO()

    /** [push] unless the slot already holds the current revision, then [BedJetDevice.startProgram]. */
    suspend fun run(device: String, bedJet: BedJetDevice, slot: Int, programId: String): Unit = TODO()

    suspend fun resolve(device: String, slot: Int, resolution: Resolution): Unit = TODO()
    suspend fun keep(device: String, slot: Int): LibraryEntry = TODO()
    suspend fun create(program: Program, origin: Origin = Origin.BLANK): LibraryEntry = TODO()
    suspend fun delete(programId: String): Unit = TODO()
    suspend fun restore(programId: String): Unit = TODO()
}
