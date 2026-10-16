package com.aditya.wakeup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class Scheduler(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    fun allowed() = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    private fun pending(id: String, at: Long, snooze: Boolean): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, AlarmReceiver::class.java).setAction("FIRE").setData(Uri.parse("wakeup://alarm/$id/$snooze"))
            .putExtra("id", id).putExtra("at", at).putExtra("snooze", snooze),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(id: String) { manager.cancel(pending(id, 0, false)); manager.cancel(pending(id, 0, true)) }
    fun sync(alarms: List<Alarm>) {
        alarms.forEach { a ->
            cancel(a.id)
            if (allowed()) {
                if (a.enabled && a.nextAt > 0) schedule(a.id, a.nextAt, false)
                if (a.snoozeAt > 0) schedule(a.id, a.snoozeAt, true)
            }
        }
    }
    private fun schedule(id: String, at: Long, snooze: Boolean) {
        val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending(id, at, snooze)) }
        catch (_: SecurityException) { /* Permission revoked during scheduling; setup panel reflects it. */ }
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
                    "DISMISS" -> context.app.store.finish(id, false)
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
