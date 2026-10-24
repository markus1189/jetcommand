package link.thesymbolic.jetcommand

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import link.thesymbolic.jetcommand.protocol.Button as Btn
import link.thesymbolic.jetcommand.protocol.LinkState
import link.thesymbolic.jetcommand.protocol.LogLevel
import link.thesymbolic.jetcommand.protocol.ProcessEvent
import link.thesymbolic.jetcommand.protocol.StatusUpdate
import link.thesymbolic.jetcommand.protocol.formatTemp

/**
 * Keeps the process alive while [BedJetController] holds a connection, with an ongoing status
 * notification. Started only by the controller from a UI action; it stops itself once the
 * controller has no stack.
 */
class BedJetService : Service() {
    private var scope: CoroutineScope? = null
    // Every post takes a NotificationManagerService wake lock; reposting unchanged content each
    // second was ~1/3 of the screen-off drain.
    private var posted: ServiceNotification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = controller
        // startForeground first, every time: a startForegroundService() that isn't answered within
        // seconds crashes the app.
        if (!startForegroundOrStop(controller)) return START_NOT_STICKY
        ACTIONS.find { it.action == intent?.action }?.let { controller.press(it.label, it.button) }
        if (scope == null) observe(controller)
        // Not sticky: after the process dies the controller's stack is gone, and a restart from the
        // background may not start a connectedDevice service anyway.
        return START_NOT_STICKY
    }

    private fun startForegroundOrStop(controller: BedJetController): Boolean {
        // Re-shows the last posted content, so a press from the shade doesn't flash "Starting"
        // until the next change.
        val content =
            posted ?: ServiceNotification(controller.stack.value?.label ?: "JetCommand", "Starting")
        val failure =
            try {
                startForeground(
                    ID,
                    build(content),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                return true
            } catch (e: SecurityException) {
                e
            } catch (e: ForegroundServiceStartNotAllowedException) {
                e
            }
        controller.log(LogLevel.ERROR, "Service: ${failure::class.simpleName} ${failure.message}")
        stopSelf()
        return false
    }

    private fun observe(controller: BedJetController) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        scope.launch {
            controller.stack.collect { if (it == null) stop() }
        }
        scope.launch {
            combine(controller.stack, controller.linkState, controller.status) { s, l, u ->
                    Triple(s, l, u)
                }
                .conflate()
                .collect { (s, l, u) ->
                    if (s == null) return@collect
                    val content = serviceNotification(s.label, l, u)
                    if (content == posted) return@collect
                    posted = content
                    getSystemService(NotificationManager::class.java).notify(ID, build(content))
                    delay(RATE_MS)
                }
        }
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        controller.lifecycle("task removed")
    }

    override fun onDestroy() {
        controller.lifecycle(ProcessEvent.ServiceDestroyed)
        scope?.cancel()
        scope = null
    }

    private fun build(content: ServiceNotification): Notification {
        val (title, text, ready) = content
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val b =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        // Presses while the link is down would only land in the log.
        if (ready) {
            // No session token: SystemUI only moves MediaStyle notifications that carry one into
            // the media player,
            // so this stays a plain notification; its compact view shows icons only, the labels
            // serve accessibility and media/headset buttons are left alone.
            b.setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            ACTIONS.forEachIndexed { i, a ->
                val pi =
                    PendingIntent.getService(
                        this,
                        1 + i,
                        Intent(this, BedJetService::class.java).setAction(a.action),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                b.addAction(
                    Notification.Action.Builder(Icon.createWithResource(this, a.icon), a.label, pi)
                        .build()
                )
            }
        }
        return b.build()
    }

    private class Action(val label: String, val button: Int, val icon: Int) {
        val action = "link.thesymbolic.jetcommand.PRESS_$button"
    }

    companion object {
        private const val ID = 1
        // Was "connection" at IMPORTANCE_LOW: silent notifications are hidden on the lock screen by
        // default.
        private const val CHANNEL = "controls"
        // Three is the most a notification shows; the user picked these, Fan − stays in the app.
        private val ACTIONS =
            listOf(
                Action("Temp −", Btn.TEMP_DOWN_C, R.drawable.ic_temp_down),
                Action("Temp +", Btn.TEMP_UP_C, R.drawable.ic_temp_up),
                Action("Fan +", Btn.FAN_UP, R.drawable.ic_fan_up),
            )
        // Collapses bursts; short enough that a press from the shade shows its effect before the
        // user gives up on it.
        private const val RATE_MS = 1_000L

        /**
         * From the foreground only (Android 12+ refuses background FGS starts). A connectedDevice
         * service needs BLUETOOTH_CONNECT granted at start on Android 14+, so without it the demo
         * runs unserviced.
         */
        fun start(context: Context, controller: BedJetController) {
            if (!controller.hasConnectPermission()) {
                controller.log(
                    LogLevel.WARN,
                    "No BLUETOOTH_CONNECT: running without the background service",
                )
                return
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.deleteNotificationChannel("connection")
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Controls", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        setSound(null, null)
                        enableVibration(false)
                    }
            )
            try {
                context.startForegroundService(Intent(context, BedJetService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException when called from the background.
                controller.log(LogLevel.ERROR, "Service not started: ${e.message}")
            }
        }
    }
}

/** [ready]: a live status, so the controls can reach the unit. */
data class ServiceNotification(val title: String, val text: String, val ready: Boolean = false)

fun serviceNotification(
    label: String,
    link: LinkState,
    update: StatusUpdate?,
): ServiceNotification {
    val st = update?.status?.takeIf { link == LinkState.Ready }
    return if (st == null) ServiceNotification(label, linkText(link))
    else
        ServiceNotification(
            "${st.mode.label} · ${formatTemp(st.actualTemp)} → ${formatTemp(st.targetTemp)}",
            "${hoursMinutes(st.remainingSeconds)} left · ${linkText(link)}",
            ready = true,
        )
}

fun linkText(l: LinkState) =
    when (l) {
        LinkState.Ready -> "Ready"
        is LinkState.Connecting ->
            if (l.reconnectAttempt > 0) "Reconnecting (${l.reconnectAttempt})" else "Connecting"
        is LinkState.Disconnected -> if (l.gaveUp) "Gave up" else "Disconnected"
    }
