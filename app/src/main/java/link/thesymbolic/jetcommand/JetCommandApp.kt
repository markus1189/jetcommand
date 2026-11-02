package link.thesymbolic.jetcommand

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import androidx.core.content.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import link.thesymbolic.jetcommand.library.LibraryDatabase
import link.thesymbolic.jetcommand.library.RoomLibraryStore
import link.thesymbolic.jetcommand.night.NightLogDatabase
import link.thesymbolic.jetcommand.night.RoomNightLogStore
import link.thesymbolic.jetcommand.night.RoomNightsSource
import link.thesymbolic.jetcommand.protocol.NightEvent
import link.thesymbolic.jetcommand.protocol.NightLogStore
import link.thesymbolic.jetcommand.protocol.Origin
import link.thesymbolic.jetcommand.protocol.ProcessEvent
import link.thesymbolic.jetcommand.protocol.ProgramLibrary
import link.thesymbolic.jetcommand.protocol.unrecorded

class JetCommandApp : Application() {
    lateinit var bedJetController: BedJetController
        private set

    lateinit var nights: RoomNightsSource
        private set

    lateinit var programLibrary: ProgramLibrary
        private set

    override fun onCreate() {
        super.onCreate()
        val dao = NightLogDatabase.open(this).events()
        programLibrary = ProgramLibrary(RoomLibraryStore(LibraryDatabase.open(this)))
        val store = RoomNightLogStore(dao)
        bedJetController = BedJetController(this, store, programLibrary, PrefsDeviceMemory(this))
        dropSeededTemplates()
        nights = RoomNightsSource(dao)
        logStart(store)
    }

    // Builds from 2026-10-23 seeded the official app's suggestions; the user didn't want them.
    // Untouched ones go
    // (tombstoned, so a slot still holding one offers Restore); edited ones are the user's now and
    // stay.
    private fun dropSeededTemplates() {
        val prefs = getSharedPreferences("jetcommand", MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SEEDED, false)) return
        bedJetController.scope.launch {
            programLibrary
                .entries()
                .first()
                .filter {
                    it.deletedAt == null &&
                        it.current.rev == 1 &&
                        it.current.origin == Origin.TEMPLATE
                }
                .forEach { programLibrary.delete(it.id) }
            prefs.edit { remove(KEY_SEEDED) }
        }
    }

    // The system's record survives the process; logcat's kill lines rotate out within hours. One
    // coroutine, so the exits land before this start.
    private fun logStart(store: NightLogStore) {
        val startedAt = System.currentTimeMillis()
        val prefs = getSharedPreferences("jetcommand", MODE_PRIVATE)
        val seen = prefs.getLong(KEY_EXIT_SEEN, 0)
        val exits =
            getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 10)
                .filter { it.timestamp > seen }
                .sortedBy { it.timestamp }
                .map {
                    NightEvent.process(
                        it.timestamp,
                        // A null description is stored as "null", as it always was.
                        ProcessEvent.Exit(it.reason, it.importance, it.description.toString()),
                    )
                }
        bedJetController.scope.launch {
            val recorded =
                if (exits.isEmpty()) emptyList()
                else store.events(exits.first().at, exits.last().at + 1)
            unrecorded(exits, recorded).forEach { store.append(it) }
            store.append(NightEvent.process(startedAt, ProcessEvent.Started))
            // Only once they are stored: a process dying before would skip them for good.
            exits.lastOrNull()?.let { prefs.edit { putLong(KEY_EXIT_SEEN, it.at) } }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN fires on every trip to the home screen.
        if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            bedJetController.lifecycle(ProcessEvent.Note("trim memory $level"))
    }

    private companion object {
        const val KEY_EXIT_SEEN = "exit.seen"
        const val KEY_SEEDED = "library.seeded"
    }
}

val android.content.Context.controller
    get() = (applicationContext as JetCommandApp).bedJetController
val android.content.Context.library
    get() = (applicationContext as JetCommandApp).programLibrary
