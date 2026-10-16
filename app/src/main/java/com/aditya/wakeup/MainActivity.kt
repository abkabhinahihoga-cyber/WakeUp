package com.aditya.wakeup

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileInputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_ALARM
        enableEdgeToEdge()
        setContent {
            val theme by app.store.theme.collectAsStateWithLifecycle("System")
            var currentScreen by rememberSaveable { mutableStateOf("home") }
            WakeUpTheme(theme) {
                AnimatedContent(
                    targetState = currentScreen,
                    label = "screenTransition",
                    transitionSpec = { fadeIn() togetherWith fadeOut() }
                ) { screen ->
                    when (screen) {
                        "settings" -> SettingsScreen(
                            activity = this@MainActivity,
                            onNavigateBack = { currentScreen = "home" }
                        )
                        else -> Home(
                            activity = this@MainActivity,
                            theme = theme,
                            onNavigateToSettings = { currentScreen = "settings" }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.scope.launch {
            app.store.reconcile()
            if (app.store.alarms.first().any { it.ringing }) {
                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, AlarmService::class.java))
            }
        }
    }
}

@Composable
private fun rememberNow(): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = System.currentTimeMillis(); delay(1000) }
        }
    }.value
}

@Composable
private fun Home(
    activity: MainActivity,
    theme: String,
    onNavigateToSettings: () -> Unit
) {
    val alarms by activity.app.store.alarms.collectAsStateWithLifecycle(emptyList())
    val audioBoost by activity.app.store.audioBoost.collectAsStateWithLifecycle(100)
    val defaultSound by activity.app.store.defaultSound.collectAsStateWithLifecycle("dawn")
    val defaultSoundName by activity.app.store.defaultSoundName.collectAsStateWithLifecycle("Soft Dawn")
    val defaultSnooze by activity.app.store.defaultSnoozeMinutes.collectAsStateWithLifecycle(5)
    val defaultVibrate by activity.app.store.defaultVibrate.collectAsStateWithLifecycle(true)
    val defaultChallengeType by activity.app.store.defaultChallengeType.collectAsStateWithLifecycle("none")
    val defaultChallengeDifficulty by activity.app.store.defaultChallengeDifficulty.collectAsStateWithLifecycle("easy")
    val defaultChallengeQuestions by activity.app.store.defaultChallengeQuestions.collectAsStateWithLifecycle(3)

    var editing by remember { mutableStateOf<Alarm?>(null) }
    var deleting by remember { mutableStateOf<Alarm?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    val snackbar = remember { SnackbarHostState() }

    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }

    val exactAllowed = remember(refresh) { Scheduler(activity).allowed() }
    val notifications = remember(refresh) { NotificationManagerCompat.from(activity).areNotificationsEnabled() }
    val channelReady = remember(refresh) { (activity.getSystemService(NotificationManager::class.java).getNotificationChannel("ringing")?.importance ?: NotificationManager.IMPORTANCE_HIGH) >= NotificationManager.IMPORTANCE_HIGH }
    val full = remember(refresh) { Build.VERSION.SDK_INT < 34 || activity.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() }
    val setupNeeded = !exactAllowed || !notifications || !full || !channelReady

    val next = alarms.flatMap { listOf(if (it.enabled) it.nextAt else 0L, it.snoozeAt) }.filter { it > now }.minOrNull()
    fun perform(block: suspend () -> Unit) { scope.launch { runCatching { block() }.onFailure { error = it.message ?: "Could not save. Please try again." } } }

    val currentHour = remember(now) {
        ZonedDateTime.now(ZoneId.systemDefault()).hour
    }
    val greeting = when (currentHour) {
        in 5..11 -> "Good morning, Aditya"
        in 12..16 -> "Good afternoon, Aditya"
        in 17..21 -> "Good evening, Aditya"
        else -> "Good night, Aditya"
    }
    val dateText = remember(now) {
        runCatching {
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
        }.getOrDefault("Today")
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val soon = LocalTime.now().plusMinutes(5)
                    editing = Alarm(
                        hour = soon.hour,
                        minute = soon.minute,
                        sound = defaultSound,
                        soundName = defaultSoundName,
                        snoozeMinutes = defaultSnooze,
                        vibrate = defaultVibrate,
                        challengeType = defaultChallengeType,
                        challengeDifficulty = defaultChallengeDifficulty,
                        challengeQuestions = defaultChallengeQuestions
                    )
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
                modifier = Modifier.navigationBarsPadding()
            ) {
                Text("+  New alarm", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    ) { inset ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = inset.calculateTopPadding(),
                    start = inset.calculateStartPadding(layoutDirection),
                    end = inset.calculateEndPadding(layoutDirection)
                ),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Compact Header: Logo, Greeting & Settings
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("⏰", fontSize = 16.sp)
                            Text(
                                "WAKEUP",
                                letterSpacing = 3.sp,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            greeting,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            dateText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }

                    FilterChip(
                        selected = false,
                        onClick = onNavigateToSettings,
                        label = { Text("Settings", fontSize = 12.sp) },
                        leadingIcon = { Text("⚙️", fontSize = 12.sp) },
                        trailingIcon = {
                            if (setupNeeded) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error)
                                )
                            }
                        }
                    )
                }
            }

            // Compact Next-Alarm Card
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "NEXT ALARM",
                                    fontSize = 10.sp,
                                    letterSpacing = 1.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                )
                                if (audioBoost > 100) {
                                    Text(
                                        "⚡ ${audioBoost}%",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                next?.let { AlarmDisplay.time(it) } ?: "No alarms set",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (next != null) {
                                if (exactAllowed) {
                                    Text(
                                        "${AlarmDisplay.day(next, now)} · ${AlarmDisplay.remaining(next, now)}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                                    )
                                } else {
                                    Text(
                                        "Exact alarms disabled · Tap to resolve",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.clickable { onNavigateToSettings() }
                                    )
                                }
                            } else {
                                Text(
                                    "Tap '+ New alarm' below to schedule.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                }
            }

            // Slim Readiness Notice (Shown only if permissions are missing)
            if (setupNeeded) {
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToSettings() }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("⚠️", fontSize = 16.sp)
                            Text(
                                "Setup required: tap here to grant alarm permissions.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            Text("Fix →", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Alarms Section Header
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Your alarms", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            "${alarms.count { it.enabled }} active",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Empty state
            if (alarms.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    ) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("No alarms yet. Tap '+ New alarm' below to create one.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    }
                }
            }

            // Streamlined Alarm Cards
            items(alarms.sortedWith(compareBy<Alarm> { it.hour }.thenBy { it.minute }), key = { it.id }) { alarm ->
                val isRinging = alarm.ringing
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isRinging) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        else if (alarm.enabled || alarm.snoozeAt > 0) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = alarm }
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    AlarmDisplay.time(alarm.hour, alarm.minute),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (alarm.enabled || alarm.snoozeAt > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                )
                                Text(
                                    alarm.label,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }

                            Switch(
                                checked = alarm.enabled,
                                onCheckedChange = { checked ->
                                    perform {
                                        activity.app.store.save(alarm.copy(enabled = checked))
                                        if (checked && !Scheduler(activity).allowed()) {
                                            val res = snackbar.showSnackbar("Alarm enabled, but exact alarms are disabled.", actionLabel = "Allow", duration = SnackbarDuration.Long)
                                            if (res == SnackbarResult.ActionPerformed) Scheduler(activity).openExactAlarmSettings()
                                        }
                                    }
                                }
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        // Single concise metadata line
                        val challengeBadge = if (alarm.challengeType == "math") " · 🧠 Math" else ""
                        val boostBadge = if (audioBoost > 100) " · ⚡ ${audioBoost}%" else ""
                        val occurrence = listOf(if (alarm.enabled) alarm.nextAt else 0L, alarm.snoozeAt).filter { it > 0 }.minOrNull()
                        val timingInfo = if (alarm.enabled && occurrence != null) {
                            if (exactAllowed) "${AlarmDisplay.day(occurrence, now)} · ${AlarmDisplay.remaining(occurrence, now)}"
                            else "Exact alarms disabled"
                        } else "Off"

                        Text(
                            "${dayLabel(alarm.days)} · ${alarm.soundName}$challengeBadge$boostBadge · $timingInfo",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )

                        if (alarm.snoozeAt > 0) {
                            Text(
                                "Snoozed until " + AlarmDisplay.time(alarm.snoozeAt),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        // Subtle bottom actions
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = { editing = alarm }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                                    Text("Edit", fontSize = 13.sp)
                                }
                                TextButton(onClick = { deleting = alarm }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                                    Text("Delete", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (alarm.ringing) {
                                FilledTonalButton(
                                    onClick = { activity.startActivity(Intent(activity, AlarmActivity::class.java)) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("🔔 Ringing · Open", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { alarm ->
        Editor(
            activity = activity,
            initial = alarm,
            onClose = { editing = null },
            onSave = { updated ->
                perform {
                    activity.app.store.save(updated)
                    editing = null
                    val saved = activity.app.store.alarms.first().first { it.id == updated.id }
                    if (saved.enabled) {
                        if (Scheduler(activity).allowed()) {
                            snackbar.showSnackbar("${AlarmDisplay.day(saved.nextAt, System.currentTimeMillis())}, ${AlarmDisplay.time(saved.nextAt)} · ${AlarmDisplay.remaining(saved.nextAt, System.currentTimeMillis())}")
                        } else {
                            val res = snackbar.showSnackbar(
                                "Alarm saved for ${AlarmDisplay.day(saved.nextAt, System.currentTimeMillis())}, but exact alarms are disabled.",
                                actionLabel = "Allow",
                                duration = SnackbarDuration.Long
                            )
                            if (res == SnackbarResult.ActionPerformed) Scheduler(activity).openExactAlarmSettings()
                        }
                    } else {
                        snackbar.showSnackbar("Alarm saved · Off")
                    }
                }
            }
        )
    }

    deleting?.let { alarm ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this alarm?") },
            text = { Text("This will permanently remove '${alarm.label}'.") },
            confirmButton = { TextButton(onClick = { perform { activity.app.store.delete(alarm.id); deleting = null } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Notice") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }
        )
    }
}

private fun dayLabel(days: Set<Int>): String = when {
    days.isEmpty() -> "Once"
    days.size == 7 -> "Daily"
    days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
    else -> days.sorted().joinToString("·") { listOf("M", "T", "W", "T", "F", "S", "S")[it - 1] }
}

private sealed interface VoiceState {
    data object Idle : VoiceState
    data class Recording(val elapsedSeconds: Int) : VoiceState
    data class Recorded(val file: File, val durationSeconds: Int, val isPlaying: Boolean = false) : VoiceState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Editor(activity: ComponentActivity, initial: Alarm, onClose: () -> Unit, onSave: (Alarm) -> Unit) {
    var alarm by remember { mutableStateOf(initial) }
    val audioBoost by activity.app.store.audioBoost.collectAsStateWithLifecycle(100)
    val now = rememberNow()
    val nextAt = AlarmTime.next(alarm.hour, alarm.minute, alarm.days, now)
    var confirmTomorrow by remember { mutableStateOf(false) }
    val timeState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = false)
    LaunchedEffect(timeState.hour, timeState.minute) { alarm = alarm.copy(hour = timeState.hour, minute = timeState.minute) }

    var voiceState by remember { mutableStateOf<VoiceState>(VoiceState.Idle) }
    var recordingTimerJob by remember { mutableStateOf<Job?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }

    var previewResult by remember { mutableStateOf<SoundResult?>(null) }
    var isPreviewingCurrentSound by remember { mutableStateOf(false) }

    var selectedRingtoneCategory by remember { mutableStateOf("All") }

    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun stopPreview() {
        previewResult?.release()
        previewResult = null
        isPreviewingCurrentSound = false
        if (voiceState is VoiceState.Recorded) {
            voiceState = (voiceState as VoiceState.Recorded).copy(isPlaying = false)
        }
    }

    fun stopRecording(keep: Boolean) {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        val current = recorder ?: return
        val file = recordingFile
        val elapsed = (voiceState as? VoiceState.Recording)?.elapsedSeconds ?: 0
        val success = runCatching { current.stop() }.isSuccess
        current.release()
        recorder = null

        if (keep && success && file != null && file.exists() && file.length() > 512L) {
            file.setReadable(true, false)
            val isValid = runCatching {
                val testPlayer = MediaPlayer()
                try {
                    FileInputStream(file).use { fis ->
                        testPlayer.setDataSource(fis.fd, 0, file.length())
                    }
                    testPlayer.prepare()
                    testPlayer.duration > 0
                } finally {
                    testPlayer.release()
                }
            }.getOrDefault(false)

            if (isValid) {
                voiceState = VoiceState.Recorded(file, elapsed.coerceAtLeast(1))
                message = null
            } else {
                file.delete()
                recordingFile = null
                voiceState = VoiceState.Idle
                message = "Recorded audio was unreadable. Please record again."
            }
        } else {
            file?.delete()
            recordingFile = null
            voiceState = VoiceState.Idle
            if (keep) message = "Recording was too short. Speak clearly and try again."
        }
    }

    fun startRecording() {
        stopPreview()
        runCatching {
            val folder = File(activity.filesDir, "audio").apply { mkdirs() }
            val file = File(folder, "voice-${UUID.randomUUID()}.m4a")
            recordingFile = file
            @Suppress("DEPRECATION")
            val mic = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(activity) else MediaRecorder()
            recorder = mic
            mic.setAudioSource(MediaRecorder.AudioSource.MIC)
            mic.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mic.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mic.setAudioEncodingBitRate(128000)
            mic.setAudioSamplingRate(44100)
            mic.setOutputFile(file.absolutePath)
            mic.setMaxDuration(120_000)
            mic.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    stopRecording(true)
                }
            }
            mic.prepare()
            mic.start()
            voiceState = VoiceState.Recording(0)
            recordingTimerJob?.cancel()
            recordingTimerJob = scope.launch {
                var seconds = 0
                while (isActive && seconds < 120) {
                    delay(1000)
                    seconds++
                    voiceState = VoiceState.Recording(seconds)
                }
            }
        }.onFailure {
            recorder?.release()
            recorder = null
            recordingFile?.delete()
            recordingFile = null
            recordingTimerJob?.cancel()
            voiceState = VoiceState.Idle
            message = "Microphone unavailable. Close other recording apps and try again."
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else message = "Microphone permission is needed to record your voice."
    }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            stopPreview()
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Imported audio"
                    val file = File(File(activity.filesDir, "audio").apply { mkdirs() }, "import-${UUID.randomUUID()}.audio")
                    try {
                        activity.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output ->
                            val buffer = ByteArray(8192); var total = 0
                            while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 25 * 1024 * 1024) { "Choose an audio file smaller than 25 MB." }; output.write(buffer, 0, count) }
                        } }
                        file.setReadable(true, false)
                        val check = MediaPlayer()
                        try {
                            FileInputStream(file).use { fis ->
                                check.setDataSource(fis.fd, 0, file.length())
                            }
                            check.prepare()
                            require(check.duration > 0) { "Audio file is empty." }
                        } finally {
                            check.release()
                        }
                        file.name to name
                    } catch (e: Exception) { file.delete(); throw e }
                }
            }.onSuccess { (file, name) ->
                alarm = alarm.copy(sound = file, soundName = name)
                message = "Imported '$name' successfully!"
            }.onFailure {
                message = it.message ?: "Could not import this audio format."
            }
            busy = false
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                stopRecording(false)
                stopPreview()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            recordingTimerJob?.cancel()
            recorder?.release()
            if (alarm.sound != recordingFile?.name) {
                recordingFile?.delete()
            }
            stopPreview()
        }
    }

    fun commit() {
        stopPreview()
        onSave(alarm.copy(label = alarm.label.ifBlank { "Rise and shine, Aditya" }))
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(Modifier.fillMaxHeight(0.96f)) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Edit Alarm", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${AlarmDisplay.day(nextAt, now)}, ${AlarmDisplay.time(nextAt)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onClose) { Text("Cancel") }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Section 1: Time Picker
                item {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        TimeInput(state = timeState)
                    }
                }

                // Section 2: Alarm Name
                item {
                    OutlinedTextField(
                        value = alarm.label,
                        onValueChange = { alarm = alarm.copy(label = it.take(80)) },
                        label = { Text("Alarm name") },
                        placeholder = { Text("Rise and shine, Aditya") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                }

                // Section 3: Repeat Schedule
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("REPEAT SCHEDULE", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Once" to emptySet<Int>(), "Daily" to (1..7).toSet(), "Weekdays" to (1..5).toSet()).forEach { (label, days) ->
                                    FilterChip(
                                        selected = alarm.days == days,
                                        onClick = { alarm = alarm.copy(days = days) },
                                        label = { Text(label, fontSize = 12.sp) }
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { index, text ->
                                    val day = index + 1
                                    val isSelected = day in alarm.days
                                    FilterChip(
                                        modifier = Modifier.weight(1f),
                                        selected = isSelected,
                                        onClick = {
                                            alarm = alarm.copy(days = if (isSelected) alarm.days - day else alarm.days + day)
                                        },
                                        label = {
                                            Text(
                                                text.take(1),
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Section 4: Alarm Sound & Volume Boost
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("SOUND & AMPLIFICATION", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (audioBoost > 100) {
                                    Text("⚡ ${audioBoost}% Boost", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            // Current sound preview banner
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(alarm.soundName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        val subtitle = when {
                                            Sounds.isBundled(alarm.sound) -> "Built-in tone (${Ringtones.get(alarm.sound)?.category ?: "Standard"})"
                                            alarm.sound.startsWith("voice-") -> "Personal voice recording"
                                            else -> "Custom audio file"
                                        }
                                        Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                    if (isPreviewingCurrentSound) {
                                        Button(onClick = { stopPreview() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                            Text("⏹ Stop", fontSize = 13.sp)
                                        }
                                    } else {
                                        OutlinedButton(
                                            enabled = voiceState !is VoiceState.Recording && !busy,
                                            onClick = {
                                                stopPreview()
                                                runCatching {
                                                    val res = Sounds.load(activity, alarm.sound, alarm.soundName, false, audioBoost)
                                                    previewResult = res
                                                    isPreviewingCurrentSound = true
                                                    res.player.setOnCompletionListener { stopPreview() }
                                                    res.player.start()
                                                }.onFailure { message = "Preview error: ${it.message}" }
                                            },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) { Text("▶ Preview", fontSize = 13.sp) }
                                    }
                                }
                            }

                            // Built-in Tones (Filtered by Category)
                            Text("Built-in Library (15 Tones)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Ringtones.categories.forEach { cat ->
                                    FilterChip(
                                        selected = selectedRingtoneCategory == cat,
                                        onClick = { selectedRingtoneCategory = cat },
                                        label = { Text(cat, fontSize = 11.sp) }
                                    )
                                }
                            }

                            val displayedTones = remember(selectedRingtoneCategory) {
                                if (selectedRingtoneCategory == "All") Ringtones.items
                                else Ringtones.items.filter { it.category == selectedRingtoneCategory }
                            }

                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                displayedTones.forEach { tone ->
                                    val isSelected = alarm.sound == tone.id
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            stopPreview()
                                            alarm = alarm.copy(sound = tone.id, soundName = tone.title)
                                        },
                                        label = { Text(tone.title, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) }
                                    )
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                            // Voice Recording
                            Text("Personal Voice Memo", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            when (val state = voiceState) {
                                is VoiceState.Idle -> {
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !busy,
                                        onClick = {
                                            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                                startRecording()
                                            } else {
                                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    ) { Text("🎙️  Record Voice Wake-Up", fontSize = 13.sp) }
                                }
                                is VoiceState.Recording -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("● Recording: ${state.elapsedSeconds}s / 120s", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Button(
                                                onClick = { stopRecording(keep = true) },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                            ) { Text("Done", fontSize = 12.sp) }
                                            OutlinedButton(onClick = { stopRecording(keep = false) }) { Text("Cancel", fontSize = 12.sp) }
                                        }
                                    }
                                }
                                is VoiceState.Recorded -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("✓ Voice (${state.durationSeconds}s)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            OutlinedButton(onClick = {
                                                stopPreview()
                                                alarm = alarm.copy(sound = state.file.name, soundName = "My voice")
                                                voiceState = VoiceState.Idle
                                            }) { Text("Use Voice", fontSize = 12.sp) }
                                            TextButton(onClick = {
                                                stopPreview()
                                                state.file.delete()
                                                voiceState = VoiceState.Idle
                                            }) { Text("Discard", fontSize = 12.sp) }
                                        }
                                    }
                                }
                            }

                            // Custom audio file import
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = voiceState !is VoiceState.Recording && !busy,
                                onClick = { import.launch(arrayOf("audio/*")) }
                            ) { Text("📁  Import Audio File (MP3, WAV, M4A)", fontSize = 13.sp) }
                        }
                    }
                }

                // Section 5: Dismissal Challenge (Optional Math Challenge)
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("DISMISSAL CHALLENGE", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (alarm.challengeType == "math") {
                                    Text("Active", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = alarm.challengeType == "none",
                                    onClick = { alarm = alarm.copy(challengeType = "none") },
                                    label = { Text("Off (Standard)", fontSize = 12.sp) }
                                )
                                FilterChip(
                                    selected = alarm.challengeType == "math",
                                    onClick = { alarm = alarm.copy(challengeType = "math") },
                                    label = { Text("🧠 Math Challenge", fontSize = 12.sp) }
                                )
                            }

                            if (alarm.challengeType == "math") {
                                Spacer(Modifier.height(2.dp))
                                Text("Difficulty level", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("easy" to "Easy (+, -)", "medium" to "Medium (+, -, ×)", "hard" to "Hard (×, ÷)").forEach { (level, title) ->
                                        FilterChip(
                                            selected = alarm.challengeDifficulty == level,
                                            onClick = { alarm = alarm.copy(challengeDifficulty = level) },
                                            label = { Text(title, fontSize = 11.sp) }
                                        )
                                    }
                                }

                                Text("Number of questions: ${alarm.challengeQuestions}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(3, 5, 7, 10).forEach { count ->
                                        FilterChip(
                                            selected = alarm.challengeQuestions == count,
                                            onClick = { alarm = alarm.copy(challengeQuestions = count) },
                                            label = { Text("$count", fontSize = 12.sp) }
                                        )
                                    }
                                }

                                Text(
                                    "When the alarm rings, you must solve these math problems to dismiss it. Snooze remains available.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }

                // Section 6: Snooze & Vibration
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("SNOOZE & VIBRATION", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Snooze:", fontSize = 13.sp)
                                listOf(5, 10, 15, 20).forEach { minutes ->
                                    FilterChip(
                                        selected = alarm.snoozeMinutes == minutes,
                                        onClick = { alarm = alarm.copy(snoozeMinutes = minutes) },
                                        label = { Text("$minutes min", fontSize = 12.sp) }
                                    )
                                }
                            }
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Vibrate with sound", fontSize = 14.sp)
                                Switch(checked = alarm.vibrate, onCheckedChange = { alarm = alarm.copy(vibrate = it) })
                            }
                        }
                    }
                }

                message?.let {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(it, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { message = null }) { Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }

            // Sticky Bottom Action Bar
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    val saveText = if (alarm.enabled) {
                        if (Scheduler(activity).allowed()) "Save alarm · ${AlarmDisplay.day(nextAt, now)}"
                        else "Save (exact alarms disabled)"
                    } else "Save disabled alarm"

                    Button(
                        enabled = !busy && voiceState !is VoiceState.Recording,
                        onClick = {
                            if (alarm.enabled && AlarmDisplay.rollsToTomorrow(alarm, System.currentTimeMillis())) {
                                confirmTomorrow = true
                            } else {
                                commit()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(saveText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (confirmTomorrow) {
        AlertDialog(
            onDismissRequest = { confirmTomorrow = false },
            title = { Text("Set for tomorrow?") },
            text = { Text("${AlarmDisplay.time(alarm.hour, alarm.minute)} has already passed today. This alarm will ring tomorrow.") },
            confirmButton = { TextButton(onClick = { confirmTomorrow = false; commit() }) { Text("Set for tomorrow") } },
            dismissButton = { TextButton(onClick = { confirmTomorrow = false }) { Text("Change time") } }
        )
    }
}
