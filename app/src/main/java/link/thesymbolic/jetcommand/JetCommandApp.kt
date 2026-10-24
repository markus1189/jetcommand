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
import link.thesymbolic.jetcommand.protocol.Origin
import link.thesymbolic.jetcommand.protocol.ProcessEvent
import link.thesymbolic.jetcommand.protocol.ProgramLibrary

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
        bedJetController = BedJetController(this, RoomNightLogStore(dao), programLibrary)
        dropSeededTemplates()
        nights = RoomNightsSource(dao)
        logExits()
        bedJetController.lifecycle(ProcessEvent.Started)
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

    // The system's record survives the process; logcat's kill lines rotate out within hours.
    private fun logExits() {
        val prefs = getSharedPreferences("jetcommand", MODE_PRIVATE)
        val seen = prefs.getLong(KEY_EXIT_SEEN, 0)
        val exits =
            getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 10)
                .filter { it.timestamp > seen }
        exits
            .sortedBy { it.timestamp }
            .forEach {
                bedJetController.lifecycle(
                    // A null description is stored as "null", as it always was.
                    ProcessEvent.Exit(it.reason, it.importance, it.description.toString()),
                    it.timestamp,
                )
            }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit { putLong(KEY_EXIT_SEEN, it) } }
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
