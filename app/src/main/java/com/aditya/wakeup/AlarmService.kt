package com.aditya.wakeup

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileInputStream

data class SoundResult(
    val player: MediaPlayer,
    val soundId: String,
    val soundName: String,
    val isFallback: Boolean = false,
    val fallbackReason: String? = null
)

object Sounds {
    private const val TAG = "WakeUpSounds"
    val bundled = linkedMapOf("dawn" to "Soft dawn", "orbit" to "Orbit bells", "pulse" to "Bright pulse")

    fun isBundled(sound: String): Boolean = sound in bundled

    fun getAudioFile(context: Context, sound: String): File =
        File(File(context.filesDir, "audio"), sound)

    fun uri(context: Context, sound: String): Uri = when (sound) {
        "dawn" -> Uri.parse("android.resource://${context.packageName}/${R.raw.dawn}")
        "orbit" -> Uri.parse("android.resource://${context.packageName}/${R.raw.orbit}")
        "pulse" -> Uri.parse("android.resource://${context.packageName}/${R.raw.pulse}")
        else -> Uri.fromFile(getAudioFile(context, sound))
    }

    fun load(context: Context, sound: String, soundName: String, loop: Boolean): SoundResult {
        if (isBundled(sound)) {
            val resId = when (sound) {
                "orbit" -> R.raw.orbit
                "pulse" -> R.raw.pulse
                else -> R.raw.dawn
            }
            try {
                val mp = createRawPlayer(context, resId, loop)
                return SoundResult(mp, sound, bundled[sound] ?: soundName, isFallback = false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bundled sound '$sound'", e)
                return fallback(context, loop, "Bundled tone failed to load: ${e.message}")
            }
        }

        val file = getAudioFile(context, sound)
        if (!file.exists()) {
            val reason = "Audio file not found: ${file.name}"
            Log.w(TAG, reason)
            return fallback(context, loop, reason)
        }
        if (!file.canRead() || file.length() <= 0L) {
            val reason = "Audio file unreadable or empty (${file.length()} bytes): ${file.name}"
            Log.w(TAG, reason)
            return fallback(context, loop, reason)
        }

        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                isLooping = loop
                FileInputStream(file).use { fis ->
                    setDataSource(fis.fd, 0, file.length())
                }
                prepare()
            }
            Log.i(TAG, "Successfully loaded custom audio '${file.name}' (${file.length()} bytes, duration ${mp.duration}ms)")
            return SoundResult(mp, sound, soundName, isFallback = false)
        } catch (e: Exception) {
            val reason = "Failed to prepare custom audio '${file.name}': ${e.message}"
            Log.e(TAG, reason, e)
            return fallback(context, loop, reason)
        }
    }

    private fun createRawPlayer(context: Context, resId: Int, loop: Boolean): MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        isLooping = loop
        context.resources.openRawResourceFd(resId).use { afd ->
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
        prepare()
    }

    private fun fallback(context: Context, loop: Boolean, reason: String): SoundResult {
        Log.w(TAG, "Falling back to default sound. Reason: $reason")
        val dawnPlayer = runCatching {
            createRawPlayer(context, R.raw.dawn, loop)
        }.getOrNull()

        if (dawnPlayer != null) {
            return SoundResult(dawnPlayer, "dawn", "Soft dawn", isFallback = true, fallbackReason = reason)
        }

        val sysUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val sysPlayer = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            isLooping = loop
            setDataSource(context, sysUri)
            prepare()
        }
        return SoundResult(sysPlayer, "system", "Default alarm", isFallback = true, fallbackReason = reason)
    }

    fun player(context: Context, sound: String, loop: Boolean): MediaPlayer =
        load(context, sound, bundled[sound] ?: "Custom sound", loop).player
}

class AlarmService : Service() {
    companion object {
        val currentFallbackNotice = MutableStateFlow<String?>(null)
    }

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
        startForeground(41, notification(currentAlarm, false))
        if (!observing) {
            observing = true
            scope.launch {
                app.store.alarms.map { alarms -> alarms.firstOrNull { it.ringing } }.distinctUntilChanged().collect { alarm ->
                    currentAlarm = alarm
                    stopSound()
                    if (alarm == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                    else {
                        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeUp:ring").apply { acquire(11 * 60_000L) }
                        val soundResult = withContext(Dispatchers.IO) {
                            Sounds.load(this@AlarmService, alarm.sound, alarm.soundName, true)
                        }
                        currentFallbackNotice.value = if (soundResult.isFallback) soundResult.fallbackReason else null
                        player = soundResult.player
                        player?.start()
                        val notif = notification(alarm, soundResult.isFallback)
                        getSystemService(NotificationManager::class.java).notify(41, notif)

                        if (alarm.vibrate) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 600), 0),
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                        timeout = scope.launch { delay((alarm.ringingSince + 600_000L - System.currentTimeMillis()).coerceIn(0, 600_000L)); app.store.finish(alarm.id, false) }
                    }
                }
            }
        }
        return START_STICKY
    }
    private fun notification(a: Alarm?, isFallback: Boolean): Notification {
        val screen = PendingIntent.getActivity(this, 0, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (isFallback) "Custom audio unavailable · Playing Soft dawn" else "Time to start your day, Aditya"
        val builder = NotificationCompat.Builder(this, "ringing").setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(a?.label ?: "WakeUp").setContentText(text)
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
        currentFallbackNotice.value = null
        timeout?.cancel(); player?.release(); player = null; vibrator.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }
    override fun onDestroy() { stopSound(); scope.cancel(); super.onDestroy() }
}
