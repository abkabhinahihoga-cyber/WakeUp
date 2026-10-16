package com.aditya.wakeup

import android.Manifest
import android.app.*
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import java.time.LocalTime
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_ALARM
        enableEdgeToEdge()
        setContent {
            val theme by app.store.theme.collectAsStateWithLifecycle("System")
            WakeUpTheme(theme) { Home(this, theme) }
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
private fun Home(activity: MainActivity, theme: String) {
    val alarms by activity.app.store.alarms.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var deleting by remember { mutableStateOf<Alarm?>(null) }
    var setup by rememberSaveable { mutableStateOf(false) }
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val soon = LocalTime.now().plusMinutes(5)
                    editing = Alarm(hour = soon.hour, minute = soon.minute)
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                modifier = Modifier.navigationBarsPadding()
            ) {
                Text("+  New alarm", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
            contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "WAKEUP",
                        letterSpacing = 4.sp,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FilterChip(
                        selected = setup,
                        onClick = { setup = !setup },
                        label = { Text("Settings & theme") },
                        trailingIcon = {
                            if (setupNeeded) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error)
                                )
                            }
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Your time. Your rhythm.", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                Text("Make every wake-up yours, Aditya.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                Card(shape = RoundedCornerShape(28.dp)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)))
                            .padding(22.dp)
                    ) {
                        Text("NEXT ALARM", fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            next?.let { AlarmDisplay.time(it) } ?: "No alarms set",
                            fontSize = 38.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (next != null) {
                            if (exactAllowed) {
                                Spacer(Modifier.height(8.dp))
                                Text(AlarmDisplay.day(next, now), fontSize = 17.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.height(14.dp))
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)) {
                                    Text(
                                        AlarmDisplay.remaining(next, now),
                                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            } else {
                                Spacer(Modifier.height(8.dp))
                                Text("Saved for ${AlarmDisplay.day(next, now)} · Exact alarms disabled", fontSize = 15.sp, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(12.dp))
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
                                    Text(
                                        "Not scheduled · Tap Settings to allow",
                                        Modifier.clickable { setup = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        } else {
                            Text("Tap '+ New alarm' below to get started.", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f), modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }

            item { PermissionPanel(activity, setup) }

            if (setup) {
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("APPEARANCE", fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("System", "Light", "Dark").forEach { mode ->
                                    FilterChip(
                                        selected = theme == mode,
                                        onClick = { perform { activity.app.store.setTheme(mode) } },
                                        label = { Text(mode) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Your alarms", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("${alarms.count { it.enabled }} active", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }

            if (alarms.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Quiet for now. Add your first alarm below.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                        }
                    }
                }
            }

            items(alarms.sortedWith(compareBy<Alarm> { it.hour }.thenBy { it.minute }), key = { it.id }) { alarm ->
                val isRinging = alarm.ringing
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isRinging) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        else if (alarm.enabled || alarm.snoozeAt > 0) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().clickable { editing = alarm }
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(AlarmDisplay.time(alarm.hour, alarm.minute), fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                                val occurrence = listOf(if (alarm.enabled) alarm.nextAt else 0L, alarm.snoozeAt).filter { it > 0 }.minOrNull()
                                if (occurrence != null) {
                                    if (exactAllowed) {
                                        Text("${AlarmDisplay.day(occurrence, now)} · ${AlarmDisplay.remaining(occurrence, now)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    } else {
                                        Text("${AlarmDisplay.day(occurrence, now)} · Exact alarms disabled", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                }
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

                        Spacer(Modifier.height(8.dp))
                        Text(alarm.label, fontWeight = FontWeight.Medium, fontSize = 16.sp)

                        Spacer(Modifier.height(6.dp))
                        val soundBadge = when {
                            Sounds.isBundled(alarm.sound) -> "🔔 ${alarm.soundName}"
                            alarm.sound.startsWith("voice-") -> "🎙️ ${alarm.soundName}"
                            else -> "📁 ${alarm.soundName}"
                        }
                        Text("${dayLabel(alarm.days)} · $soundBadge · ${alarm.snoozeMinutes} min snooze" + if (alarm.vibrate) " · Vibrate" else "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

                        if (alarm.snoozeAt > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text("Snoozed until " + AlarmDisplay.time(alarm.snoozeAt), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }

                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { editing = alarm }) { Text("Edit") }
                            TextButton(onClick = { deleting = alarm }) { Text("Delete") }
                            if (alarm.ringing) {
                                FilledTonalButton(onClick = { activity.startActivity(Intent(activity, AlarmActivity::class.java)) }) {
                                    Text("🔔 Ringing · Open")
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
            title = { Text("Something needs attention") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }
        )
    }
}

private fun dayLabel(days: Set<Int>): String = when {
    days.isEmpty() -> "Once"
    days.size == 7 -> "Every day"
    days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
    else -> days.sorted().joinToString(" · ") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1] }
}

@Composable
private fun PermissionPanel(activity: ComponentActivity, expanded: Boolean) {
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val exact = remember(refresh) { Scheduler(activity).allowed() }
    val notifications = remember(refresh) { NotificationManagerCompat.from(activity).areNotificationsEnabled() }
    val channelReady = remember(refresh) { (activity.getSystemService(NotificationManager::class.java).getNotificationChannel("ringing")?.importance ?: NotificationManager.IMPORTANCE_HIGH) >= NotificationManager.IMPORTANCE_HIGH }
    val full = remember(refresh) { Build.VERSION.SDK_INT < 34 || activity.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() }

    if (expanded || !exact || !notifications || !full || !channelReady) {
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (!exact) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (exact && notifications && full && channelReady) "System settings & readiness" else "Finish alarm setup",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                if (!exact) Text("Alarms are saved, but cannot ring on time until exact alarms are allowed in system settings.", fontSize = 13.sp)

                fun open(action: String) {
                    runCatching {
                        activity.startActivity(Intent(action, Uri.parse("package:${activity.packageName}")))
                    }.onFailure {
                        runCatching {
                            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                        }
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!exact) Button(onClick = { Scheduler(activity).openExactAlarmSettings() }) { Text("Allow exact alarms") }
                    if (!notifications) OutlinedButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                    }) { Text("Allow notifications") }
                    if (!full) OutlinedButton(onClick = { if (Build.VERSION.SDK_INT >= 34) open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) }) { Text("Allow full-screen") }
                    if (!channelReady) OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "ringing")) }) { Text("Ringing channel") }
                }

                if (expanded) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Keep alarm volume audible. On Moto and iQOO devices, ensure WakeUp's battery/background mode is unrestricted so scheduled alarms fire reliably even in deep sleep. After reboot, unlock your phone once to restore alarms.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }) { Text("App info & battery") }
                        TextButton(onClick = { activity.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }) { Text("Sound settings") }
                    }
                }
            }
        }
    }
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
    val now = rememberNow()
    val nextAt = AlarmTime.next(alarm.hour, alarm.minute, alarm.days, now)
    var confirmTomorrow by remember { mutableStateOf(false) }
    val timeState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = false)
    LaunchedEffect(timeState.hour, timeState.minute) { alarm = alarm.copy(hour = timeState.hour, minute = timeState.minute) }

    var voiceState by remember { mutableStateOf<VoiceState>(VoiceState.Idle) }
    var recordingTimerJob by remember { mutableStateOf<Job?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }

    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPreviewingCurrentSound by remember { mutableStateOf(false) }

    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun stopPreview() {
        previewPlayer?.release()
        previewPlayer = null
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
            if (keep) message = "Recording was too short. Please speak clearly and try again."
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
        if (granted) startRecording() else message = "Microphone permission is needed to record your voice. You can still import audio."
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
                            require(check.duration > 0) { "This audio file is empty." }
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Set your alarm", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${AlarmDisplay.day(nextAt, now)}, ${AlarmDisplay.time(nextAt)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onClose) { Text("Cancel") }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Section 1: Time Picker
                item {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        TimeInput(state = timeState)
                        Text(
                            "Choose hour, minute, and AM or PM",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                // Section 2: Ring schedule notice card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${AlarmDisplay.day(nextAt, now)} · ${AlarmDisplay.time(nextAt)}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (Scheduler(activity).allowed()) {
                                Text(
                                    AlarmDisplay.remaining(nextAt, now),
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                Text(
                                    "Exact alarms are disabled. Alarm cannot ring on time.",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                TextButton(onClick = { Scheduler(activity).openExactAlarmSettings() }) {
                                    Text("Allow exact alarms in system settings")
                                }
                            }
                            if (AlarmDisplay.rollsToTomorrow(alarm, now)) {
                                Text(
                                    "This time has passed for today; scheduled for tomorrow.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }

                // Section 3: Alarm Label
                item {
                    OutlinedTextField(
                        value = alarm.label,
                        onValueChange = { alarm = alarm.copy(label = it.take(80)) },
                        label = { Text("Alarm name") },
                        placeholder = { Text("Rise and shine, Aditya") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )
                }

                // Section 4: Repeat Days
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("REPEAT SCHEDULE", fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Once" to emptySet<Int>(), "Daily" to (1..7).toSet(), "Weekdays" to (1..5).toSet()).forEach { (label, days) ->
                                    FilterChip(
                                        selected = alarm.days == days,
                                        onClick = { alarm = alarm.copy(days = days) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { index, text ->
                                    val day = index + 1
                                    val isSelected = day in alarm.days
                                    FilterChip(
                                        modifier = Modifier.semantics {
                                            contentDescription = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")[index]
                                        },
                                        selected = isSelected,
                                        onClick = {
                                            alarm = alarm.copy(days = if (isSelected) alarm.days - day else alarm.days + day)
                                        },
                                        label = { Text(text) }
                                    )
                                }
                            }
                            Text(dayLabel(alarm.days), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // Section 5: Sound Selection
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("ALARM SOUND", fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            // Current sound banner
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Current selection", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(alarm.soundName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    if (isPreviewingCurrentSound) {
                                        Button(onClick = { stopPreview() }) { Text("⏹ Stop") }
                                    } else {
                                        OutlinedButton(
                                            enabled = voiceState !is VoiceState.Recording && !busy,
                                            onClick = {
                                                stopPreview()
                                                runCatching {
                                                    val res = Sounds.load(activity, alarm.sound, alarm.soundName, false)
                                                    if (res.isFallback && !Sounds.isBundled(alarm.sound)) {
                                                        message = "Custom audio unavailable (${res.fallbackReason ?: "error"}). Playing fallback."
                                                    } else {
                                                        message = null
                                                    }
                                                    res.player.also { mp ->
                                                        previewPlayer = mp
                                                        isPreviewingCurrentSound = true
                                                        mp.setOnCompletionListener { p ->
                                                            p.release()
                                                            if (previewPlayer == p) {
                                                                previewPlayer = null
                                                                isPreviewingCurrentSound = false
                                                            }
                                                        }
                                                        mp.start()
                                                    }
                                                }.onFailure { message = "Unable to preview sound: ${it.message}" }
                                            }
                                        ) { Text("▶ Preview") }
                                    }
                                }
                            }

                            // Built-in tones subsection
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Built-in Tones", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Sounds.bundled.forEach { (key, name) ->
                                        FilterChip(
                                            selected = alarm.sound == key,
                                            onClick = {
                                                stopPreview()
                                                alarm = alarm.copy(sound = key, soundName = name)
                                            },
                                            label = { Text(name) }
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                            // Voice recording subsection (State Machine)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Personal Voice Alarm", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                when (val state = voiceState) {
                                    is VoiceState.Idle -> {
                                        Text("Record a personalized morning wake-up message (up to 2 minutes). Stored privately.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                        ) {
                                            Text("🎙️  Record your voice")
                                        }
                                    }
                                    is VoiceState.Recording -> {
                                        Card(
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)),
                                            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(16.dp))
                                        ) {
                                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                                                    Text("Recording in progress...", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                                }
                                                val mins = state.elapsedSeconds / 60
                                                val secs = state.elapsedSeconds % 60
                                                Text(
                                                    String.format(java.util.Locale.US, "%d:%02d / 2:00", mins, secs),
                                                    fontSize = 28.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text("Speak clearly near the microphone", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    Button(
                                                        onClick = { stopRecording(keep = true) },
                                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                                    ) {
                                                        Text("⏹  Stop recording")
                                                    }
                                                    OutlinedButton(onClick = { stopRecording(keep = false) }) {
                                                        Text("Cancel")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    is VoiceState.Recorded -> {
                                        Card(
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                        ) {
                                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Text("✓ Voice recorded (${state.durationSeconds} seconds)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    if (state.isPlaying) {
                                                        OutlinedButton(onClick = {
                                                            previewPlayer?.release()
                                                            previewPlayer = null
                                                            voiceState = state.copy(isPlaying = false)
                                                        }) { Text("⏹ Stop") }
                                                    } else {
                                                        OutlinedButton(onClick = {
                                                            stopPreview()
                                                            runCatching {
                                                                val mp = MediaPlayer().apply {
                                                                    setAudioAttributes(
                                                                        AudioAttributes.Builder()
                                                                            .setUsage(AudioAttributes.USAGE_ALARM)
                                                                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                                                            .build()
                                                                    )
                                                                    setVolume(1.0f, 1.0f)
                                                                    FileInputStream(state.file).use { fis ->
                                                                        setDataSource(fis.fd, 0, state.file.length())
                                                                    }
                                                                    prepare()
                                                                    setOnCompletionListener { p ->
                                                                        p.release()
                                                                        if (previewPlayer == p) previewPlayer = null
                                                                        voiceState = (voiceState as? VoiceState.Recorded)?.copy(isPlaying = false) ?: VoiceState.Idle
                                                                    }
                                                                }
                                                                previewPlayer = mp
                                                                mp.start()
                                                                voiceState = state.copy(isPlaying = true)
                                                            }.onFailure { message = "Failed to play voice preview: ${it.message}" }
                                                        }) { Text("▶ Listen") }
                                                    }

                                                    Button(onClick = {
                                                        stopPreview()
                                                        alarm = alarm.copy(sound = state.file.name, soundName = "My voice")
                                                        recordingFile = null
                                                        voiceState = VoiceState.Idle
                                                        message = "Voice alarm selected!"
                                                    }) {
                                                        Text("Use this voice")
                                                    }

                                                    TextButton(onClick = {
                                                        stopPreview()
                                                        state.file.delete()
                                                        recordingFile = null
                                                        voiceState = VoiceState.Idle
                                                    }) {
                                                        Text("Discard")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                            // Import audio subsection
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Custom Audio File", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = voiceState !is VoiceState.Recording && !busy,
                                    onClick = { import.launch(arrayOf("audio/*")) }
                                ) {
                                    Text(if (busy) "Importing audio…" else "📁  Import audio file (MP3, M4A, WAV)")
                                }
                                Text("Select any sound from your phone storage (max 25 MB).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // Section 6: Snooze & Vibration
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("SNOOZE & VIBRATION", fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Snooze duration", fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(5, 10, 15, 20).forEach { minutes ->
                                        FilterChip(
                                            selected = alarm.snoozeMinutes == minutes,
                                            onClick = { alarm = alarm.copy(snoozeMinutes = minutes) },
                                            label = { Text("$minutes min") }
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Vibrate", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                    Text("Vibration pattern when alarm rings", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = alarm.vibrate, onCheckedChange = { alarm = alarm.copy(vibrate = it) })
                            }
                        }
                    }
                }

                // Messages / Diagnostics
                message?.let {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(it, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { message = null }) { Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer) }
                            }
                        }
                    }
                }
            }

            // Sticky Bottom Action Bar
            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
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
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(saveText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (confirmTomorrow) {
        AlertDialog(
            onDismissRequest = { confirmTomorrow = false },
            title = { Text("Set for tomorrow?") },
            text = { Text("${AlarmDisplay.time(alarm.hour, alarm.minute)} has already passed today. This alarm will ring tomorrow. To ring today, choose a later time or change AM/PM.") },
            confirmButton = { TextButton(onClick = { confirmTomorrow = false; commit() }) { Text("Set for tomorrow") } },
            dismissButton = { TextButton(onClick = { confirmTomorrow = false }) { Text("Change time") } }
        )
    }
}
