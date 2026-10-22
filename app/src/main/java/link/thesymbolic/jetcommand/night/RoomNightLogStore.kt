package link.thesymbolic.jetcommand.night

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import link.thesymbolic.jetcommand.protocol.NightEvent
import link.thesymbolic.jetcommand.protocol.NightLogStore

// kind as its enum name: an ordinal would silently change meaning when a kind is added in the middle.
@Entity(tableName = "night_event", indices = [Index("at")])
data class NightEventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: String,
    val detail: String,
    val value: Int?,
)

@Dao
interface NightEventDao {
    @Insert suspend fun insert(row: NightEventRow)

    @Query("SELECT * FROM night_event ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<NightEventRow>>
}

@Database(entities = [NightEventRow::class], version = 1)
abstract class NightLogDatabase : RoomDatabase() {
    abstract fun events(): NightEventDao

    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, NightLogDatabase::class.java, "night-log.db").build()
    }
}

class RoomNightLogStore(private val dao: NightEventDao) : NightLogStore {
    override suspend fun append(event: NightEvent) =
        dao.insert(NightEventRow(at = event.at, kind = event.kind.name, detail = event.detail, value = event.value))

    override fun recent(limit: Int): Flow<List<NightEvent>> = dao.recent(limit).map { rows ->
        rows.mapNotNull { r ->
            val kind = NightEvent.Kind.entries.firstOrNull { it.name == r.kind } ?: return@mapNotNull null
            NightEvent(r.at, kind, r.detail, r.value)
        }
    }
}
