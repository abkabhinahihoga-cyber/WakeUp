package com.aditya.wakeup

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileInputStream

data class SoundResult(
    val player: MediaPlayer,
    val soundId: String,
    val soundName: String,
    val isFallback: Boolean = false,
    val fallbackReason: String? = null,
    val enhancer: LoudnessEnhancer? = null
) {
    fun release() {
        runCatching { enhancer?.release() }
        runCatching { player.release() }
    }
}

object Sounds {
    private const val TAG = "WakeUpSounds"
    val bundled: Map<String, String> get() = Ringtones.items.associate { it.id to it.title }

    fun isBundled(sound: String): Boolean = Ringtones.isBundled(sound)

    fun getAudioFile(context: Context, sound: String): File =
        File(File(context.filesDir, "audio"), sound)

    fun uri(context: Context, sound: String): Uri {
        val ringtone = Ringtones.get(sound)
        return if (ringtone != null) {
            Uri.parse("android.resource://${context.packageName}/${ringtone.resId}")
        } else {
            Uri.fromFile(getAudioFile(context, sound))
        }
    }

    fun load(
        context: Context,
        sound: String,
        soundName: String,
        loop: Boolean,
        boostPercentage: Int = 100
    ): SoundResult {
        if (isBundled(sound)) {
            val ringtone = Ringtones.get(sound)
            val resId = ringtone?.resId ?: R.raw.dawn
            try {
                val mp = createRawPlayer(context, resId, loop)
                val enhancer = AudioBoostManager.attachBoost(mp.audioSessionId, boostPercentage)
                return SoundResult(mp, sound, ringtone?.title ?: soundName, isFallback = false, enhancer = enhancer)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bundled sound '$sound'", e)
                return fallback(context, loop, "Bundled tone failed to load: ${e.message}", boostPercentage)
            }
        }

        val file = getAudioFile(context, sound)
        if (!file.exists()) {
            val reason = "Audio file not found: ${file.name}"
            Log.w(TAG, reason)
            return fallback(context, loop, reason, boostPercentage)
        }
        if (!file.canRead() || file.length() <= 0L) {
            val reason = "Audio file unreadable or empty (${file.length()} bytes): ${file.name}"
            Log.w(TAG, reason)
            return fallback(context, loop, reason, boostPercentage)
        }

        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = loop
                setVolume(1.0f, 1.0f)
                FileInputStream(file).use { fis ->
                    setDataSource(fis.fd, 0, file.length())
                }
                prepare()
            }
            val enhancer = AudioBoostManager.attachBoost(mp.audioSessionId, boostPercentage)
            Log.i(TAG, "Successfully loaded custom audio '${file.name}' (${file.length()} bytes, duration ${mp.duration}ms, boost $boostPercentage%)")
            return SoundResult(mp, sound, soundName, isFallback = false, enhancer = enhancer)
        } catch (e: Exception) {
            val reason = "Failed to prepare custom audio '${file.name}': ${e.message}"
            Log.e(TAG, reason, e)
            return fallback(context, loop, reason, boostPercentage)
        }
    }

    private fun createRawPlayer(context: Context, resId: Int, loop: Boolean): MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        isLooping = loop
        setVolume(1.0f, 1.0f)
        context.resources.openRawResourceFd(resId).use { afd ->
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
        prepare()
    }

    private fun fallback(context: Context, loop: Boolean, reason: String, boostPercentage: Int = 100): SoundResult {
        Log.w(TAG, "Falling back to default sound. Reason: $reason")
        val dawnPlayer = runCatching {
            createRawPlayer(context, R.raw.dawn, loop)
        }.getOrNull()

        if (dawnPlayer != null) {
            val enhancer = AudioBoostManager.attachBoost(dawnPlayer.audioSessionId, boostPercentage)
            return SoundResult(dawnPlayer, "dawn", "Soft Dawn", isFallback = true, fallbackReason = reason, enhancer = enhancer)
        }

        val sysUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val sysPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = loop
            setVolume(1.0f, 1.0f)
            setDataSource(context, sysUri)
            prepare()
        }
        val enhancer = AudioBoostManager.attachBoost(sysPlayer.audioSessionId, boostPercentage)
        return SoundResult(sysPlayer, "system", "Default alarm", isFallback = true, fallbackReason = reason, enhancer = enhancer)
    }

    fun player(context: Context, sound: String, loop: Boolean, boostPercentage: Int = 100): MediaPlayer =
        load(context, sound, Ringtones.titleOf(sound), loop, boostPercentage).player
}

class AlarmService : Service() {
    companion object {
        val currentFallbackNotice = MutableStateFlow<String?>(null)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentSoundResult: SoundResult? = null
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var timeout: Job? = null
    private var observing = false
    private var currentAlarm: Alarm? = null
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var focusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("ringing", "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "WakeUp alarm controls and lock-screen alerts"
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun requestAlarmAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* maintain alarm focus */ }
                .build()
            focusRequest = req
            val res = audioManager.requestAudioFocus(req)
            Log.i("WakeUpSounds", "Alarm audio focus requested (API >= 26), result=$res")
        } else {
            @Suppress("DEPRECATION")
            val res = audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            Log.i("WakeUpSounds", "Alarm audio focus requested (legacy), result=$res")
        }
    }

    private fun abandonAlarmAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                focusRequest = null
                Log.i("WakeUpSounds", "Alarm audio focus abandoned")
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
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
                        requestAlarmAudioFocus()

                        val boost = runCatching { app.store.audioBoost.first() }.getOrDefault(100)
                        val soundResult = withContext(Dispatchers.IO) {
                            Sounds.load(this@AlarmService, alarm.sound, alarm.soundName, true, boost)
                        }
                        currentSoundResult = soundResult
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
        val text = if (isFallback) "Custom audio unavailable · Playing Soft Dawn" else "Time to start your day, Aditya"
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
        abandonAlarmAudioFocus()
        timeout?.cancel()
        currentSoundResult?.release()
        currentSoundResult = null
        player = null
        vibrator.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() { stopSound(); scope.cancel(); super.onDestroy() }
}
