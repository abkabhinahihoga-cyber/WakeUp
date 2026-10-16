package com.aditya.wakeup

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object Sounds {
    val bundled = linkedMapOf("dawn" to "Soft dawn", "orbit" to "Orbit bells", "pulse" to "Bright pulse")
    fun uri(context: android.content.Context, sound: String): Uri = when (sound) {
        "dawn" -> Uri.parse("android.resource://${context.packageName}/${R.raw.dawn}")
        "orbit" -> Uri.parse("android.resource://${context.packageName}/${R.raw.orbit}")
        "pulse" -> Uri.parse("android.resource://${context.packageName}/${R.raw.pulse}")
        else -> Uri.fromFile(java.io.File(context.filesDir, "audio/$sound"))
    }
    fun player(context: android.content.Context, sound: String, loop: Boolean): MediaPlayer {
        fun prepare(uri: Uri) = MediaPlayer().apply {
            try {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(context, uri); isLooping = loop; prepare()
            } catch (e: Exception) { release(); throw e }
        }
        return runCatching { prepare(uri(context, sound)) }.getOrElse {
            runCatching { prepare(uri(context, "dawn")) }.getOrElse { prepare(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) }
        }
    }
}

class AlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var timeout: Job? = null
    private var observing = false
    private var currentAlarm: Alarm? = null
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("ringing", "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "WakeUp alarm controls and lock-screen alerts"
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(41, notification(currentAlarm))
        if (!observing) {
            observing = true
            scope.launch {
                app.store.alarms.map { alarms -> alarms.firstOrNull { it.ringing } }.distinctUntilChanged().collect { alarm ->
                    currentAlarm = alarm
                    stopSound()
                    if (alarm == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                    else {
                        getSystemService(NotificationManager::class.java).notify(41, notification(alarm))
                        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeUp:ring").apply { acquire(11 * 60_000L) }
                        player = runCatching { withContext(Dispatchers.IO) { Sounds.player(this@AlarmService, alarm.sound, true) } }.getOrNull()
                        player?.start()
                        if (alarm.vibrate) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 600), 0),
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                        timeout = scope.launch { delay((alarm.ringingSince + 600_000L - System.currentTimeMillis()).coerceIn(0, 600_000L)); app.store.finish(alarm.id, false) }
                    }
                }
            }
        }
        return START_STICKY
    }
    private fun notification(a: Alarm?): Notification {
        val screen = PendingIntent.getActivity(this, 0, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, "ringing").setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(a?.label ?: "WakeUp").setContentText("Time to start your day, Aditya")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOngoing(true).setContentIntent(screen)
            .setFullScreenIntent(screen, true)
        if (a != null) {
            fun action(name: String): PendingIntent = PendingIntent.getBroadcast(this, 0,
                Intent(this, AlarmReceiver::class.java).setAction(name).setData(Uri.parse("wakeup://action/${a.id}/$name")).putExtra("id", a.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (Scheduler(this).allowed()) builder.addAction(0, "Snooze ${a.snoozeMinutes} min", action("SNOOZE"))
            builder.addAction(0, "Dismiss", action("DISMISS"))
        }
        return builder.build()
    }
    private fun stopSound() {
        timeout?.cancel(); player?.release(); player = null; vibrator.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }
    override fun onDestroy() { stopSound(); scope.cancel(); super.onDestroy() }
}
