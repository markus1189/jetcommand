package link.thesymbolic.jetcommand

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * A shortcut that failed while nobody watched: a toast is gone by morning, this stays until
 * dismissed. One at a time, the latest wins; the night log keeps them all.
 */
object ShortcutAlert {
    private const val ID = 2
    private const val CHANNEL = "shortcut_failures"

    fun post(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Shortcut failures",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        nm.notify(
            ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("BedJet shortcut failed")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build(),
        )
    }
}
