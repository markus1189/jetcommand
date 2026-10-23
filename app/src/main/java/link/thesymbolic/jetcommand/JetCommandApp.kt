package link.thesymbolic.jetcommand

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
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
import link.thesymbolic.jetcommand.protocol.ProcessLog
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
        bedJetController.lifecycle(ProcessLog.STARTED)
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
                    "${ProcessLog.EXIT_PREFIX}${reasonName(it.reason)} importance ${it.importance}: ${it.description}",
                    it.timestamp,
                )
            }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit { putLong(KEY_EXIT_SEEN, it) } }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN fires on every trip to the home screen.
        if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            bedJetController.lifecycle("trim memory $level")
    }

    private companion object {
        const val KEY_EXIT_SEEN = "exit.seen"
        const val KEY_SEEDED = "library.seeded"

        // Inlined int constants used as keys: newer reasons simply never occur on older releases.
        @SuppressLint("InlinedApi")
        val REASONS =
            mapOf(
                ApplicationExitInfo.REASON_EXIT_SELF to "self",
                ApplicationExitInfo.REASON_SIGNALED to "signaled",
                ApplicationExitInfo.REASON_LOW_MEMORY to "low memory",
                ApplicationExitInfo.REASON_CRASH to "crash",
                ApplicationExitInfo.REASON_CRASH_NATIVE to "native crash",
                ApplicationExitInfo.REASON_ANR to "anr",
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "init failure",
                ApplicationExitInfo.REASON_PERMISSION_CHANGE to "permission change",
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "excessive resource usage",
                ApplicationExitInfo.REASON_USER_REQUESTED to "user requested",
                ApplicationExitInfo.REASON_USER_STOPPED to "user stopped",
                ApplicationExitInfo.REASON_DEPENDENCY_DIED to "dependency died",
                ApplicationExitInfo.REASON_OTHER to "other",
                ApplicationExitInfo.REASON_FREEZER to "freezer",
                ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE to "package state change",
                ApplicationExitInfo.REASON_PACKAGE_UPDATED to "package updated",
            )

        fun reasonName(reason: Int) = REASONS[reason] ?: "reason $reason"
    }
}

val android.content.Context.controller
    get() = (applicationContext as JetCommandApp).bedJetController
val android.content.Context.library
    get() = (applicationContext as JetCommandApp).programLibrary
