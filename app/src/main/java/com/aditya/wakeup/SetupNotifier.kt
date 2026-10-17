package com.aditya.wakeup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object SetupNotifier {
    const val CHANNEL_ID = "setup_alerts"
    const val NOTIFICATION_ID = 4242

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Setup & Permissions",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when critical alarm permissions are missing"
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    fun showSetupNotification(context: Context, exactAllowed: Boolean, fullAllowed: Boolean) {
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_SETTINGS", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val reasonText = when {
            !exactAllowed -> "Exact alarm permission is missing. Tap to enable exact alarm timing."
            !fullAllowed -> "Full-screen permission is missing. Tap to allow lock-screen alarms."
            else -> "Notification access is required so alarms ring and display controls."
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("⏰ Alarm Setup Incomplete")
            .setContentText(reasonText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reasonText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)

        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancelSetupNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
