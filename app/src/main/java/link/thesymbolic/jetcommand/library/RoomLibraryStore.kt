package link.thesymbolic.jetcommand.library

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import link.thesymbolic.jetcommand.protocol.LibraryEntry
import link.thesymbolic.jetcommand.protocol.LibraryStore
import link.thesymbolic.jetcommand.protocol.Origin
import link.thesymbolic.jetcommand.protocol.Program
import link.thesymbolic.jetcommand.protocol.ProgramRevision
import link.thesymbolic.jetcommand.protocol.PushRecord
import link.thesymbolic.jetcommand.protocol.Slot
import link.thesymbolic.jetcommand.protocol.SlotState
import link.thesymbolic.jetcommand.protocol.decodeSteps
import link.thesymbolic.jetcommand.protocol.encodeSteps

@Entity(tableName = "program", indices = [Index("updatedAt")])
data class ProgramRow(
    @PrimaryKey val id: String,
    val currentRev: Int,
    val deletedAt: Long?,
    val updatedAt: Long,
)

// origin as its enum name, like the night log's kind. Steps in the 5-byte wire form, so a
// fingerprint can be
// recomputed from exactly what the unit stores.
@Entity(tableName = "program_revision", primaryKeys = ["programId", "rev"])
data class ProgramRevisionRow(
    val programId: String,
    val rev: Int,
    val name: String,
    val steps: ByteArray,
    val createdAt: Long,
    val origin: String,
    val note: String?,
)

@Entity(tableName = "slot_state", primaryKeys = ["device", "slot"])
data class SlotStateRow(
    val device: String,
    val slot: Int,
    val name: String?,
    val steps: ByteArray?,
    val readAt: Long?,
    val pushedProgramId: String?,
    val pushedRev: Int?,
    val pushedFingerprint: String?,
    val pushedAt: Long?,
    val pendingProgramId: String?,
    val pendingRev: Int?,
    val pendingFingerprint: String?,
)

/** A program row joined with its current revision. */
data class EntryRow(
    val id: String,
    val deletedAt: Long?,
    val rev: Int,
    val name: String,
    val steps: ByteArray,
    val createdAt: Long,
    val origin: String,
    val note: String?,
)

@Dao
interface LibraryDao {
    @Query(
        """SELECT p.id, p.deletedAt, r.rev, r.name, r.steps, r.createdAt, r.origin, r.note
           FROM program p JOIN program_revision r ON r.programId = p.id AND r.rev = p.currentRev
           ORDER BY p.updatedAt DESC, p.id"""
    )
    fun entries(): Flow<List<EntryRow>>

    @Query(
        """SELECT p.id, p.deletedAt, r.rev, r.name, r.steps, r.createdAt, r.origin, r.note
           FROM program p JOIN program_revision r ON r.programId = p.id AND r.rev = p.currentRev WHERE p.id = :id"""
    )
    suspend fun entry(id: String): EntryRow?

    @Query("SELECT * FROM program_revision WHERE programId = :programId ORDER BY rev DESC")
    suspend fun revisions(programId: String): List<ProgramRevisionRow>

    @Query("SELECT * FROM program_revision") fun allRevisions(): Flow<List<ProgramRevisionRow>>

    @Query("SELECT MAX(rev) FROM program_revision WHERE programId = :programId")
    suspend fun maxRev(programId: String): Int?

    @Insert suspend fun insert(row: ProgramRow)

    @Insert suspend fun insert(row: ProgramRevisionRow)

    @Query("UPDATE program SET currentRev = :rev, updatedAt = :at WHERE id = :id")
    suspend fun setCurrent(id: String, rev: Int, at: Long): Int

    @Query("UPDATE program SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun setDeleted(id: String, deletedAt: Long?): Int

    @Query("SELECT * FROM slot_state WHERE device = :device ORDER BY slot")
    fun slots(device: String): Flow<List<SlotStateRow>>

    @Query("SELECT * FROM slot_state WHERE device = :device AND slot = :slot")
    suspend fun slot(device: String, slot: Int): SlotStateRow?

    @Upsert suspend fun put(row: SlotStateRow)
}

@Database(
    entities = [ProgramRow::class, ProgramRevisionRow::class, SlotStateRow::class],
    version = 1,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        fun open(context: Context) =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
    }
}

class RoomLibraryStore(private val db: LibraryDatabase) : LibraryStore {
    private val dao = db.library()

    override fun entries(): Flow<List<LibraryEntry>> =
        dao.entries().map { rows -> rows.map(EntryRow::toEntry) }

    override fun allRevisions(): Flow<List<ProgramRevision>> =
        dao.allRevisions().map { rows -> rows.map(ProgramRevisionRow::toRevision) }

    override fun slots(device: String): Flow<List<SlotState>> =
        dao.slots(device).map { rows -> rows.mapNotNull(SlotStateRow::toState) }

    override suspend fun entry(id: String): LibraryEntry? = dao.entry(id)?.toEntry()

    override suspend fun revisions(programId: String): List<ProgramRevision> =
        dao.revisions(programId).map(ProgramRevisionRow::toRevision)

    override suspend fun slot(device: String, slot: Slot): SlotState? =
        dao.slot(device, slot.index)?.toState()

    override suspend fun createProgram(program: Program, origin: Origin, at: Long): LibraryEntry =
        db.withTransaction {
            val id = UUID.randomUUID().toString()
            val revision = ProgramRevision(id, 1, program, at, origin)
            dao.insert(ProgramRow(id, currentRev = 1, deletedAt = null, updatedAt = at))
            dao.insert(revision.toRow())
            LibraryEntry(id, revision)
        }

    override suspend fun addRevision(
        programId: String,
        program: Program,
        origin: Origin,
        at: Long,
        makeCurrent: Boolean,
    ): ProgramRevision = db.withTransaction {
        val rev = (dao.maxRev(programId) ?: error("no program $programId")) + 1
        val revision = ProgramRevision(programId, rev, program, at, origin)
        dao.insert(revision.toRow())
        if (makeCurrent) dao.setCurrent(programId, rev, at)
        revision
    }

    // Reverting to an older revision counts as an edit for "most recently edited first".
    override suspend fun setCurrent(programId: String, rev: Int) {
        check(dao.setCurrent(programId, rev, System.currentTimeMillis()) == 1) {
            "no program $programId"
        }
    }

    override suspend fun setDeleted(programId: String, at: Long?) {
        check(dao.setDeleted(programId, at) == 1) { "no program $programId" }
    }

    override suspend fun putSlot(state: SlotState) = dao.put(state.toRow())
}

private fun ProgramRevision.toRow() =
    ProgramRevisionRow(
        programId,
        rev,
        program.name,
        encodeSteps(program.steps),
        createdAt,
        origin.name,
        note,
    )

// An unknown origin name can only come from a newer build; OUTSIDE is the least presumptuous
// reading.
private fun originOf(name: String) =
    Origin.entries.firstOrNull { it.name == name } ?: Origin.OUTSIDE

private fun ProgramRevisionRow.toRevision() =
    ProgramRevision(
        programId,
        rev,
        Program(name, decodeSteps(steps)),
        createdAt,
        originOf(origin),
        note,
    )

private fun EntryRow.toEntry() =
    LibraryEntry(
        id,
        ProgramRevision(
            id,
            rev,
            Program(name, decodeSteps(steps)),
            createdAt,
            originOf(origin),
            note,
        ),
        deletedAt,
    )

private fun record(id: String?, rev: Int?, fingerprint: String?, at: Long? = null) =
    if (id != null && rev != null && fingerprint != null) PushRecord(id, rev, fingerprint, at ?: 0)
    else null

// Rows are only written from a Slot; one outside it would be a row no slot view ever showed.
private fun SlotStateRow.toState() =
    Slot.of(slot)?.let { slot ->
        SlotState(
            device,
            slot,
            program =
                if (name != null && steps != null) Program(name, decodeSteps(steps)) else null,
            readAt = readAt,
            pushed = record(pushedProgramId, pushedRev, pushedFingerprint, pushedAt),
            pending = record(pendingProgramId, pendingRev, pendingFingerprint),
        )
    }

private fun SlotState.toRow() =
    SlotStateRow(
        device,
        slot.index,
        program?.name,
        program?.steps?.let(::encodeSteps),
        readAt,
        pushed?.programId,
        pushed?.rev,
        pushed?.fingerprint,
        pushed?.at,
        pending?.programId,
        pending?.rev,
        pending?.fingerprint,
    )
