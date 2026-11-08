package com.a11yland.hissi.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.a11yland.hissi.MainActivity
import com.a11yland.hissi.R
import com.a11yland.hissi.core.DisruptionAlert

// One channel, one notification id: the shade shows the latest transition,
// not a history — the app itself is the history. :core's German alert
// vocabulary maps onto string resources here so it follows the app language.
object AlertNotifications {
    private const val CHANNEL_ID = "disruption-alerts"
    private const val NOTIFICATION_ID = 1

    fun post(context: Context, alert: DisruptionAlert) {
        val manager = NotificationManagerCompat.from(context)
        // Covers the runtime permission (33+) and the app-level toggle alike;
        // the worker keeps evaluating either way, so the baseline stays
        // truthful for when notifications come back.
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)

        val title = alertTitle(context, alert)
        val body = alertBody(context, alert)

        val tapIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // The themed-icon layer doubles as the status-bar glyph — same
            // silhouette the launcher shows, already monochrome.
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        // The permission can be revoked between the check above and here.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    // Idempotent; re-registering also refreshes the localized channel name
    // after a language change.
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alerts_title),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
}

// DisruptionAlert's title and body over string resources (the :core literals
// are the German test vocabulary). Shared with the in-app announcement after a
// refresh, so the app says what the notification would.
internal fun alertTitle(context: Context, alert: DisruptionAlert): String = when (alert) {
    is DisruptionAlert.Broke -> context.getString(R.string.alert_broke_title)
    is DisruptionAlert.Repaired -> context.getString(R.string.alert_repaired_title)
}

internal fun alertBody(context: Context, alert: DisruptionAlert): String = when (alert) {
    is DisruptionAlert.Broke -> if (alert.count > 1) {
        context.getString(R.string.alert_and_more, alert.station, alert.count - 1)
    } else {
        alert.station
    }
    is DisruptionAlert.Repaired -> alert.station
}
