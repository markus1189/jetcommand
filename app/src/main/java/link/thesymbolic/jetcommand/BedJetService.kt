package link.thesymbolic.jetcommand

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import link.thesymbolic.jetcommand.protocol.Button as Btn
import link.thesymbolic.jetcommand.protocol.LinkState
import link.thesymbolic.jetcommand.protocol.LogLevel
import link.thesymbolic.jetcommand.protocol.formatTemp

/**
 * Keeps the process alive while [BedJetController] holds a connection, with an ongoing status notification.
 * Started only by the controller from a UI action; it stops itself once the controller has no stack.
 */
class BedJetService : Service() {
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = controller
        // startForeground first, every time: a startForegroundService() that isn't answered within seconds crashes the app.
        try {
            startForeground(ID, build(controller.stack.value?.label ?: "JetCommand", "Starting", false), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: RuntimeException) { // SecurityException, ForegroundServiceStartNotAllowedException
            controller.log(LogLevel.ERROR, "Service: ${e::class.simpleName} ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        ACTIONS.find { it.action == intent?.action }?.let { controller.press(it.label, it.button) }
        if (scope == null) observe(controller)
        // Not sticky: after the process dies the controller's stack is gone, and a restart from the background
        // may not start a connectedDevice service anyway.
        return START_NOT_STICKY
    }

    private fun observe(controller: BedJetController) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        scope.launch {
            controller.stack.collect { if (it == null) stop() }
        }
        scope.launch {
            combine(controller.stack, controller.linkState, controller.status) { s, l, u -> Triple(s, l, u) }
                .conflate()
                .collect { (s, l, u) ->
                    if (s == null) return@collect
                    val link = linkText(l)
                    val st = u?.status?.takeIf { l == LinkState.Ready }
                    val title = if (st == null) s.label else "${st.mode.label} · ${formatTemp(st.actualTemp)} → ${formatTemp(st.targetTemp)}"
                    val text = if (st == null) link else {
                        val r = st.remainingSeconds
                        "%d:%02d left · $link".format(r / 3600, r / 60 % 60)
                    }
                    getSystemService(NotificationManager::class.java).notify(ID, build(title, text, st != null))
                    delay(UPDATE_MS)
                }
        }
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
    }

    private fun build(title: String, text: String, ready: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        // Presses while the link is down would only land in the log.
        if (ready) ACTIONS.forEachIndexed { i, a ->
            val pi = PendingIntent.getService(this, 1 + i, Intent(this, BedJetService::class.java).setAction(a.action), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, a.label, pi).build())
        }
        return b.build()
    }

    private class Action(val label: String, val button: Int) {
        val action = "link.thesymbolic.jetcommand.PRESS_$button"
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "connection"
        // Three is the most a notification shows; the user picked these, Fan − stays in the app.
        private val ACTIONS = listOf(Action("Temp −", Btn.TEMP_DOWN_C), Action("Temp +", Btn.TEMP_UP_C), Action("Fan +", Btn.FAN_UP))
        // Short enough that a press from the shade shows its effect before the user gives up on it.
        private const val UPDATE_MS = 1_000L

        /**
         * From the foreground only (Android 12+ refuses background FGS starts). A connectedDevice service needs
         * BLUETOOTH_CONNECT granted at start on Android 14+, so without it the demo runs unserviced.
         */
        fun start(context: Context, controller: BedJetController) {
            if (!controller.hasConnectPermission()) {
                controller.log(LogLevel.WARN, "No BLUETOOTH_CONNECT: running without the background service")
                return
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW)
                    .apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC },
            )
            try {
                context.startForegroundService(Intent(context, BedJetService::class.java))
            } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException when called from the background
                controller.log(LogLevel.ERROR, "Service not started: ${e.message}")
            }
        }
    }
}

fun linkText(l: LinkState) = when (l) {
    LinkState.Ready -> "Ready"
    is LinkState.Connecting -> if (l.reconnectAttempt > 0) "Reconnecting (${l.reconnectAttempt})" else "Connecting"
    is LinkState.Disconnected -> if (l.gaveUp) "Gave up" else "Disconnected"
}
