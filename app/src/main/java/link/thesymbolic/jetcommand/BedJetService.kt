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
            startForeground(ID, build(controller.stack.value?.label ?: "JetCommand", "Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: RuntimeException) { // SecurityException, ForegroundServiceStartNotAllowedException
            controller.log(LogLevel.ERROR, "Service: ${e::class.simpleName} ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISCONNECT) controller.disconnect()
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
                    val link = when (l) {
                        LinkState.Ready -> "Ready"
                        is LinkState.Connecting -> if (l.reconnectAttempt > 0) "Reconnecting (${l.reconnectAttempt})" else "Connecting"
                        is LinkState.Disconnected -> if (l.gaveUp) "Gave up" else "Disconnected"
                    }
                    val st = u?.status?.takeIf { l == LinkState.Ready }
                    val text = if (st == null) link else {
                        val r = st.remainingSeconds
                        "$link · ${st.mode.label} · ${formatTemp(st.actualTemp)} → ${formatTemp(st.targetTemp)} · " +
                            "%d:%02d:%02d left".format(r / 3600, r / 60 % 60, r % 60)
                    }
                    getSystemService(NotificationManager::class.java).notify(ID, build(s.label, text))
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

    private fun build(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, BedJetService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build())
            .build()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "connection"
        private const val ACTION_DISCONNECT = "link.thesymbolic.jetcommand.DISCONNECT"
        private const val UPDATE_MS = 3_000L

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
                NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW),
            )
            try {
                context.startForegroundService(Intent(context, BedJetService::class.java))
            } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException when called from the background
                controller.log(LogLevel.ERROR, "Service not started: ${e.message}")
            }
        }
    }
}
