package com.aditya.wakeup

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("wakeup")
class WakeUpApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store by lazy { AlarmStore(this) }
}
val Context.app: WakeUpApp get() = applicationContext as WakeUpApp

class AlarmStore(private val context: Context) {
    private val key = stringPreferencesKey("alarms_v1")
    private val themeKey = stringPreferencesKey("theme")
    private val mutex = Mutex()
    val alarms = context.dataStore.data.map { decode(it[key] ?: "[]") }
    val theme = context.dataStore.data.map { it[themeKey] ?: "System" }
    suspend fun setTheme(value: String) { context.dataStore.edit { it[themeKey] = value } }
    private suspend fun change(block: (List<Alarm>) -> List<Alarm>) = mutex.withLock {
        context.dataStore.edit { prefs -> prefs[key] = encode(block(decode(prefs[key] ?: "[]"))) }
        Scheduler(context).sync(alarms.first())
    }
    suspend fun save(alarm: Alarm) = change { list ->
        Scheduler(context).cancel(alarm.id)
        val updated = alarm.copy(nextAt = if (alarm.enabled) AlarmTime.next(alarm.hour, alarm.minute, alarm.days, System.currentTimeMillis()) else 0, snoozeAt = 0, ringing = false)
        list.filterNot { it.id == alarm.id } + updated
    }
    suspend fun delete(id: String) = change { list -> Scheduler(context).cancel(id); list.filterNot { it.id == id } }
    suspend fun fire(id: String, token: Long, snooze: Boolean): Boolean {
        var fired = false
        change { list -> list.map { a ->
            if (a.id != id) a else AlarmTransitions.fire(a, token, snooze, System.currentTimeMillis())?.also { fired = true } ?: a
        } }
        return fired
    }
    suspend fun finish(id: String, snooze: Boolean) = change { list -> list.map { a ->
        if (a.id != id) a else AlarmTransitions.finish(a, snooze, System.currentTimeMillis())
    } }
    suspend fun reconcile(clockChanged: Boolean = false, reboot: Boolean = false) = change { list ->
        val now = System.currentTimeMillis()
        list.map { a -> a.copy(
            nextAt = if (a.enabled && (clockChanged || a.nextAt <= now)) AlarmTime.next(a.hour, a.minute, a.days, now) else a.nextAt,
            snoozeAt = if (a.snoozeAt <= now) 0 else a.snoozeAt,
            ringing = a.ringing && !reboot && now - a.ringingSince in 0 until 600_000L)
        }
    }
    private fun encode(list: List<Alarm>): String = JSONArray().apply { list.forEach { a -> put(JSONObject().apply {
        put("id", a.id); put("hour", a.hour); put("minute", a.minute); put("label", a.label)
        put("days", JSONArray(a.days.sorted())); put("enabled", a.enabled); put("sound", a.sound); put("soundName", a.soundName)
        put("snoozeMinutes", a.snoozeMinutes); put("vibrate", a.vibrate); put("nextAt", a.nextAt); put("snoozeAt", a.snoozeAt); put("ringing", a.ringing); put("ringingSince", a.ringingSince)
    }) } }.toString()
    private fun decode(raw: String): List<Alarm> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val a = array.getJSONObject(index); val days = a.getJSONArray("days")
            Alarm(a.getString("id"), a.getInt("hour"), a.getInt("minute"), a.getString("label"),
                (0 until days.length()).map { days.getInt(it) }.toSet(), a.getBoolean("enabled"),
                a.getString("sound"), a.getString("soundName"), a.getInt("snoozeMinutes"), a.getBoolean("vibrate"),
                a.optLong("nextAt"), a.optLong("snoozeAt"), a.optBoolean("ringing"), a.optLong("ringingSince"))
        }
    }
}
