package link.thesymbolic.jetcommand.night

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.NightEvent
import link.thesymbolic.jetcommand.protocol.NightLogStore
import link.thesymbolic.jetcommand.protocol.NightSample

// kind as its enum name: an ordinal would silently change meaning when a kind is added in the
// middle.
@Entity(tableName = "night_event", indices = [Index("at")])
data class NightEventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: String,
    val detail: String,
    val value: Int?,
)

@Entity(tableName = "night_sample", indices = [Index("at")])
data class NightSampleRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val room: Int,
    val air: Int,
    val target: Int,
    val fanStep: Int,
    val mode: String,
)

@Dao
interface NightEventDao {
    @Insert suspend fun insert(row: NightEventRow)

    @Insert suspend fun insert(row: NightSampleRow)

    @Query("SELECT * FROM night_event ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<NightEventRow>>

    @Query("SELECT * FROM night_event ORDER BY at, id") fun allEvents(): Flow<List<NightEventRow>>

    // Times only: the summaries need a sample's time, not its values.
    @Query("SELECT at FROM night_sample ORDER BY at") fun allSampleTimes(): Flow<List<Long>>

    @Query("SELECT * FROM night_event WHERE at >= :from AND at < :to ORDER BY at, id")
    suspend fun events(from: Long, to: Long): List<NightEventRow>

    @Query("SELECT * FROM night_sample WHERE at >= :from AND at < :to ORDER BY at, id")
    suspend fun samples(from: Long, to: Long): List<NightSampleRow>
}

@Database(
    entities = [NightEventRow::class, NightSampleRow::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class NightLogDatabase : RoomDatabase() {
    abstract fun events(): NightEventDao

    companion object {
        fun open(context: Context) =
            Room.databaseBuilder(context, NightLogDatabase::class.java, "night-log.db").build()
    }
}

class RoomNightLogStore(private val dao: NightEventDao) : NightLogStore {
    override suspend fun append(event: NightEvent) =
        dao.insert(
            NightEventRow(
                at = event.at,
                kind = event.kind.name,
                detail = event.detail,
                value = event.value,
            )
        )

    override suspend fun appendSample(sample: NightSample) =
        with(sample) {
            dao.insert(
                NightSampleRow(
                    at = at,
                    room = room,
                    air = air,
                    target = target,
                    fanStep = fanStep,
                    mode = mode.name,
                )
            )
        }

    override suspend fun events(from: Long, to: Long): List<NightEvent> =
        dao.events(from, to).mapNotNull(NightEventRow::toEvent)

    override fun recent(limit: Int): Flow<List<NightEvent>> =
        dao.recent(limit).map { rows ->
            rows.mapNotNull(NightEventRow::toEvent)
        }
}

internal fun NightEventRow.toEvent(): NightEvent? =
    NightEvent.Kind.entries
        .firstOrNull { it.name == kind }
        ?.let { NightEvent(at, it, detail, value) }

internal fun NightSampleRow.toSample(): NightSample? =
    Mode.entries
        .firstOrNull { it.name == mode }
        ?.let { NightSample(at, room, air, target, fanStep, it) }
