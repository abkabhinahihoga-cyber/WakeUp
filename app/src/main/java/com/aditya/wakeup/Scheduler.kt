package com.aditya.wakeup

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class Scheduler(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    fun allowed(): Boolean = Build.VERSION.SDK_INT < 31 || (manager?.canScheduleExactAlarms() == true)

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.onFailure {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(fallback) }
            }
        }
    }

    private fun pending(id: String, at: Long, snooze: Boolean): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, AlarmReceiver::class.java).setAction("FIRE").setData(Uri.parse("wakeup://alarm/$id/$snooze"))
            .putExtra("id", id).putExtra("at", at).putExtra("snooze", snooze),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(id: String) { manager?.cancel(pending(id, 0, false)); manager?.cancel(pending(id, 0, true)) }
    fun sync(alarms: List<Alarm>): Boolean {
        if (!allowed()) {
            alarms.forEach { cancel(it.id) }
            return false
        }
        var allSuccess = true
        alarms.forEach { a ->
            cancel(a.id)
            if (a.enabled && a.nextAt > 0) {
                if (!schedule(a.id, a.nextAt, false)) allSuccess = false
            }
            if (a.snoozeAt > 0) {
                if (!schedule(a.id, a.snoozeAt, true)) allSuccess = false
            }
        }
        return allSuccess
    }
    private fun schedule(id: String, at: Long, snooze: Boolean): Boolean {
        if (!allowed()) return false
        val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending(id, at, snooze))
            true
        } catch (_: SecurityException) {
            false
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val pending = goAsync()
        context.app.scope.launch {
            try {
                when (intent.action) {
                    "FIRE" -> if (context.app.store.fire(id, intent.getLongExtra("at", 0), intent.getBooleanExtra("snooze", false))) {
                        ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java))
                    }
                    "DISMISS" -> {
                        val alarm = context.app.store.alarms.first().firstOrNull { it.id == id }
                        if (alarm?.challengeType == "math") {
                            val open = Intent(context, AlarmActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra("START_SOLVING", true)
                            }
                            context.startActivity(open)
                        } else {
                            context.app.store.finish(id, false)
                        }
                    }
                    "SNOOZE" -> context.app.store.finish(id, Scheduler(context).allowed())
                }
            } finally { pending.finish() }
        }
    }
}
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        context.app.scope.launch {
            try { context.app.store.reconcile(intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED, intent.action == Intent.ACTION_BOOT_COMPLETED) }
            finally { pending.finish() }
        }
    }
}
