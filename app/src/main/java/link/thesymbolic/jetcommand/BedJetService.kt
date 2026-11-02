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
import androidx.core.net.toUri
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import link.thesymbolic.jetcommand.protocol.core.CommandOrigin
import link.thesymbolic.jetcommand.protocol.core.Key
import link.thesymbolic.jetcommand.protocol.core.LinkState
import link.thesymbolic.jetcommand.protocol.core.LogLevel
import link.thesymbolic.jetcommand.protocol.core.ProcessEvent
import link.thesymbolic.jetcommand.protocol.core.StatusUpdate
import link.thesymbolic.jetcommand.protocol.core.UserCommand
import link.thesymbolic.jetcommand.protocol.core.formatTemp

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
        if (intent?.action == CommandUri.ACTION) {
            CommandUri.parse(intent.dataString)?.let {
                controller.execute(it, CommandOrigin.NOTIFICATION)
            } ?: controller.log(LogLevel.WARN, "Service: bad command ${intent.dataString}")
        }
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
                    val content = serviceNotification(s.label, l, u, System.currentTimeMillis())
                    if (content.sameAs(posted)) return@collect
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
        // CommandActivity runs in a task of its own that ends with every shortcut; only the user
        // swiping the app away is worth a night-log entry.
        if (rootIntent?.component?.className == CommandActivity::class.java.name) return
        controller.lifecycle("task removed")
    }

    override fun onDestroy() {
        controller.lifecycle(ProcessEvent.ServiceDestroyed)
        scope?.cancel()
        scope = null
    }

    private fun build(content: ServiceNotification): Notification {
        val (title, text, ready) = content
        val endsAt = content.endsAt
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val b =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (endsAt != null)
            b.setWhen(endsAt)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
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
                        Intent(this, BedJetService::class.java)
                            .setAction(CommandUri.ACTION)
                            .setData(CommandUri.of(UserCommand.Press(a.key)).toUri()),
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

    private class Action(val label: String, val key: Key, val icon: Int)

    companion object {
        private const val ID = 1
        // Was "connection" at IMPORTANCE_LOW: silent notifications are hidden on the lock screen by
        // default.
        private const val CHANNEL = "controls"
        // The expanded view shows up to five; the compact view the first three.
        private val ACTIONS =
            listOf(
                Action("Temp −", Key.TEMP_DOWN_C, R.drawable.ic_temp_down),
                Action("Temp +", Key.TEMP_UP_C, R.drawable.ic_temp_up),
                Action("Fan +", Key.FAN_UP, R.drawable.ic_fan_up),
                Action("Fan −", Key.FAN_DOWN, R.drawable.ic_fan_down),
                Action("Off", Key.OFF, R.drawable.ic_off),
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
/** [endsAt] (epoch ms) drives a countdown chronometer, so the seconds tick without reposting. */
data class ServiceNotification(
    val title: String,
    val text: String,
    val ready: Boolean = false,
    val endsAt: Long? = null,
) {
    /**
     * Statuses arrive with whole seconds and some latency, so an unchanged timer's end wobbles by
     * about a second.
     */
    fun sameAs(other: ServiceNotification?): Boolean {
        if (other == null || copy(endsAt = other.endsAt) != other) return false
        val (a, b) = endsAt to other.endsAt
        return a == b || (a != null && b != null && abs(a - b) < END_TOLERANCE_MS)
    }
}

private const val END_TOLERANCE_MS = 2_000L

fun serviceNotification(
    label: String,
    link: LinkState,
    update: StatusUpdate?,
    nowMs: Long,
): ServiceNotification {
    val st = update?.status?.takeIf { link == LinkState.Ready }
    return if (st == null) ServiceNotification(label, linkText(link))
    else
        ServiceNotification(
            "${st.mode.label} · ${formatTemp(st.actualTemp)} → ${formatTemp(st.targetTemp)}",
            linkText(link),
            ready = true,
            endsAt = (nowMs + st.remainingSeconds * 1_000L).takeIf { st.remainingSeconds > 0 },
        )
}

fun linkText(l: LinkState) =
    when (l) {
        LinkState.Ready -> "Ready"
        is LinkState.Connecting ->
            if (l.reconnectAttempt > 0) "Reconnecting (${l.reconnectAttempt})" else "Connecting"
        is LinkState.Disconnected -> if (l.gaveUp) "Gave up" else "Disconnected"
    }
