package link.thesymbolic.jetcommand

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import link.thesymbolic.jetcommand.night.NightLogDatabase
import link.thesymbolic.jetcommand.night.RoomNightLogStore

class JetCommandApp : Application() {
    lateinit var controller: BedJetController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = BedJetController(this, RoomNightLogStore(NightLogDatabase.open(this).events()))
        logExits()
        controller.lifecycle("process started")
    }

    // The system's record survives the process; logcat's kill lines rotate out within hours.
    private fun logExits() {
        val prefs = getSharedPreferences("jetcommand", MODE_PRIVATE)
        val seen = prefs.getLong(KEY_EXIT_SEEN, 0)
        val exits = getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 10)
            .filter { it.timestamp > seen }
        exits.sortedBy { it.timestamp }.forEach {
            controller.lifecycle("exit ${reasonName(it.reason)} importance ${it.importance}: ${it.description}", it.timestamp)
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(KEY_EXIT_SEEN, it).apply() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN fires on every trip to the home screen.
        if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) controller.lifecycle("trim memory $level")
    }

    private companion object {
        const val KEY_EXIT_SEEN = "exit.seen"

        fun reasonName(reason: Int) = when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "self"
            ApplicationExitInfo.REASON_SIGNALED -> "signaled"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init failure"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
            ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
            ApplicationExitInfo.REASON_OTHER -> "other"
            ApplicationExitInfo.REASON_FREEZER -> "freezer"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state change"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package updated"
            else -> "reason $reason"
        }
    }
}

val android.content.Context.controller get() = (applicationContext as JetCommandApp).controller
