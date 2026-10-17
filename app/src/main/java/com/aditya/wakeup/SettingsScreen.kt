package com.aditya.wakeup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID

private sealed interface SettingsVoiceState {
    data object Idle : SettingsVoiceState
    data class Recording(val elapsedSeconds: Int) : SettingsVoiceState
    data class Recorded(val file: File, val durationSeconds: Int) : SettingsVoiceState
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    activity: ComponentActivity,
    onNavigateBack: () -> Unit
) {
    val store = activity.app.store
    val theme by store.theme.collectAsStateWithLifecycle("System")
    val audioBoost by store.audioBoost.collectAsStateWithLifecycle(100)
    val defaultSound by store.defaultSound.collectAsStateWithLifecycle("dawn")
    val defaultSoundName by store.defaultSoundName.collectAsStateWithLifecycle("Soft Dawn")
    val defaultSnooze by store.defaultSnoozeMinutes.collectAsStateWithLifecycle(5)
    val defaultVibrate by store.defaultVibrate.collectAsStateWithLifecycle(true)
    val defaultChallengeType by store.defaultChallengeType.collectAsStateWithLifecycle("none")
    val defaultChallengeDifficulty by store.defaultChallengeDifficulty.collectAsStateWithLifecycle("easy")
    val defaultChallengeQuestions by store.defaultChallengeQuestions.collectAsStateWithLifecycle(3)
    val updateServerUrl by store.updateServerUrl.collectAsStateWithLifecycle("")

    val scope = rememberCoroutineScope()
    val updateManager = remember { UpdateManager(activity) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showPracticeMathDialog by remember { mutableStateOf(false) }

    // Test player state
    var testPlayer by remember { mutableStateOf<SoundResult?>(null) }
    var isTestingSound by remember { mutableStateOf(false) }
    var previewSoundId by remember { mutableStateOf<String?>(null) }

    // Sound Library selection state
    var selectedToneCategory by remember { mutableStateOf("All") }
    var voiceState by remember { mutableStateOf<SettingsVoiceState>(SettingsVoiceState.Idle) }
    var recordingTimerJob by remember { mutableStateOf<Job?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun stopTestSound() {
        testPlayer?.release()
        testPlayer = null
        isTestingSound = false
        previewSoundId = null
    }

    fun playPreview(soundId: String, soundName: String) {
        stopTestSound()
        runCatching {
            val res = Sounds.load(activity, soundId, soundName, false, audioBoost)
            testPlayer = res
            isTestingSound = true
            previewSoundId = soundId
            res.player.setOnCompletionListener { stopTestSound() }
            res.player.start()
        }.onFailure {
            statusMessage = "Preview error: ${it.message}"
        }
    }

    fun stopRecording(keep: Boolean) {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        val current = recorder ?: return
        val file = recordingFile
        val elapsed = (voiceState as? SettingsVoiceState.Recording)?.elapsedSeconds ?: 0
        val success = runCatching { current.stop() }.isSuccess
        current.release()
        recorder = null

        if (keep && success && file != null && file.exists() && file.length() > 512L) {
            file.setReadable(true, false)
            val isValid = runCatching {
                val test = MediaPlayer()
                try {
                    FileInputStream(file).use { fis ->
                        test.setDataSource(fis.fd, 0, file.length())
                    }
                    test.prepare()
                    test.duration > 0
                } finally {
                    test.release()
                }
            }.getOrDefault(false)

            if (isValid) {
                voiceState = SettingsVoiceState.Recorded(file, elapsed.coerceAtLeast(1))
                statusMessage = null
            } else {
                file.delete()
                recordingFile = null
                voiceState = SettingsVoiceState.Idle
                statusMessage = "Recorded audio was unreadable. Please record again."
            }
        } else {
            file?.delete()
            recordingFile = null
            voiceState = SettingsVoiceState.Idle
            if (keep) statusMessage = "Recording was too short. Speak clearly and try again."
        }
    }

    fun startRecording() {
        stopTestSound()
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
            voiceState = SettingsVoiceState.Recording(0)
            recordingTimerJob?.cancel()
            recordingTimerJob = scope.launch {
                var seconds = 0
                while (isActive && seconds < 120) {
                    delay(1000)
                    seconds++
                    voiceState = SettingsVoiceState.Recording(seconds)
                }
            }
        }.onFailure {
            recorder?.release()
            recorder = null
            recordingFile?.delete()
            recordingFile = null
            recordingTimerJob?.cancel()
            voiceState = SettingsVoiceState.Idle
            statusMessage = "Microphone unavailable. Close other recording apps and try again."
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else statusMessage = "Microphone permission is needed to record your voice."
    }

    val importAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            stopTestSound()
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
                store.setDefaultSound(file, name)
                statusMessage = "Set '$name' as default alarm sound!"
            }.onFailure {
                statusMessage = it.message ?: "Could not import this audio format."
            }
            busy = false
        }
    }

    BackHandler {
        stopTestSound()
        stopRecording(false)
        onNavigateBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            stopTestSound()
            stopRecording(false)
        }
    }

    // Permission refresh observer
    var refreshPermissions by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val exactAllowed = remember(refreshPermissions) { Scheduler(activity).allowed() }
    val notificationsAllowed = remember(refreshPermissions) { NotificationManagerCompat.from(activity).areNotificationsEnabled() }
    val fullScreenAllowed = remember(refreshPermissions) { Build.VERSION.SDK_INT < 34 || activity.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent() }
    val (currentVersionCode, currentVersionName) = remember { updateManager.getCurrentVersion() }

    fun perform(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold, fontSize = 20.sp) },
                navigationIcon = {
                    TextButton(onClick = {
                        stopTestSound()
                        stopRecording(false)
                        onNavigateBack()
                    }) {
                        Text("← Back", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
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
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Group 1: Appearance & 7 Themes
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("THEME & APPEARANCE", fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "Choose from 7 customized color palettes. Your choice is instantly applied across all screens and preserved on restart.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )

                        // Theme Swatches Grid / Row
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            WakeUpThemeOptions.forEach { opt ->
                                val isSelected = theme == opt.id
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    modifier = Modifier
                                        .clickable { perform { store.setTheme(opt.id) } }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Visual Swatch Dots
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy((-4).dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clip(CircleShape)
                                                    .background(opt.backgroundColor)
                                                    .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clip(CircleShape)
                                                    .background(opt.primaryColor)
                                                    .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                                            )
                                        }
                                        Text(
                                            opt.name,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Group 2: Centralized Default Alarm Sound Library (15 ringtones + voice memo + import)
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("DEFAULT ALARM SOUND", fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "New alarms will automatically use this sound. Existing alarms strictly retain their saved sounds.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )

                        // Current Default Sound Banner with Preview Button
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Current Default:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(defaultSoundName, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    val soundType = when {
                                        Sounds.isBundled(defaultSound) -> "Built-in tone · ${Ringtones.get(defaultSound)?.category ?: "Standard"}"
                                        defaultSound.startsWith("voice-") -> "Personal voice recording"
                                        else -> "Imported audio file"
                                    }
                                    Text(soundType, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                                if (isTestingSound && previewSoundId == defaultSound) {
                                    Button(onClick = { stopTestSound() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                        Text("⏹ Stop", fontSize = 13.sp)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { playPreview(defaultSound, defaultSoundName) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) { Text("▶ Test", fontSize = 13.sp) }
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                        // Category Chips for 15 Tones
                        Text("Built-in Library (15 Tones)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Ringtones.categories.forEach { cat ->
                                FilterChip(
                                    selected = selectedToneCategory == cat,
                                    onClick = { selectedToneCategory = cat },
                                    label = { Text(cat, fontSize = 11.sp) }
                                )
                            }
                        }

                        val displayedTones = remember(selectedToneCategory) {
                            if (selectedToneCategory == "All") Ringtones.items
                            else Ringtones.items.filter { it.category == selectedToneCategory }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            displayedTones.forEach { tone ->
                                val isSelected = defaultSound == tone.id
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        perform { store.setDefaultSound(tone.id, tone.title) }
                                        playPreview(tone.id, tone.title)
                                    },
                                    label = { Text(tone.title, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                    trailingIcon = if (isSelected && isTestingSound && previewSoundId == tone.id) {
                                        { Text("🔊", fontSize = 11.sp) }
                                    } else null
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                        // Voice memo option for default sound
                        Text("Record Voice Memo as Default", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        when (val state = voiceState) {
                            is SettingsVoiceState.Idle -> {
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
                            is SettingsVoiceState.Recording -> {
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
                            is SettingsVoiceState.Recorded -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("✓ Voice Memo (${state.durationSeconds}s)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Button(onClick = {
                                            perform { store.setDefaultSound(state.file.name, "My voice memo") }
                                            voiceState = SettingsVoiceState.Idle
                                            statusMessage = "Set voice memo as default sound!"
                                        }) { Text("Use as Default", fontSize = 12.sp) }
                                        TextButton(onClick = {
                                            state.file.delete()
                                            voiceState = SettingsVoiceState.Idle
                                        }) { Text("Discard", fontSize = 12.sp) }
                                    }
                                }
                            }
                        }

                        // Import Audio File as Default
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = voiceState !is SettingsVoiceState.Recording && !busy,
                            onClick = { importAudioLauncher.launch(arrayOf("audio/*")) }
                        ) { Text("📁  Import Audio File (MP3, WAV, M4A)", fontSize = 13.sp) }

                        statusMessage?.let { msg ->
                            Text(msg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // Group 3: Default Alarm Preferences (Snooze, Vibrate, Challenge)
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("DEFAULT ALARM PREFERENCES", fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                        // Default Snooze
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Default snooze duration", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(5, 10, 15, 20).forEach { minutes ->
                                    FilterChip(
                                        selected = defaultSnooze == minutes,
                                        onClick = { perform { store.setDefaultSnoozeMinutes(minutes) } },
                                        label = { Text("$minutes min", fontSize = 12.sp) }
                                    )
                                }
                            }
                        }

                        HorizontalDivider()

                        // Default Vibrate
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Vibrate with alarm", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Switch(checked = defaultVibrate, onCheckedChange = { v -> perform { store.setDefaultVibrate(v) } })
                        }

                        HorizontalDivider()

                        // Default Dismissal Challenge
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Default dismissal challenge", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = defaultChallengeType == "none",
                                    onClick = { perform { store.setDefaultChallenge("none", defaultChallengeDifficulty, defaultChallengeQuestions) } },
                                    label = { Text("Off (Standard)", fontSize = 12.sp) }
                                )
                                FilterChip(
                                    selected = defaultChallengeType == "math",
                                    onClick = { perform { store.setDefaultChallenge("math", defaultChallengeDifficulty, defaultChallengeQuestions) } },
                                    label = { Text("🧠 Math Challenge", fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }
            }

            // Group 4: Audio Amplification Boost
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("DIGITAL AUDIO GAIN BOOST", fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(
                                "$audioBoost% (${AudioBoostManager.formatGainDB(audioBoost)})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Slider(
                            value = audioBoost.toFloat(),
                            onValueChange = { newValue ->
                                val stepped = ((newValue / 10).toInt()) * 10
                                perform { store.setAudioBoost(stepped) }
                            },
                            valueRange = 100f..200f,
                            steps = 9
                        )

                        Text(
                            "Applies digital gain (up to +6.0 dB at 200%) via Android's native LoudnessEnhancer DSP. An integrated hardware limiter prevents audio clipping. Note: Digital gain amplifies quieter tones, but does not guarantee 2× perceived loudness and strictly respects your Android system alarm volume level.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            // Group 5: Practice Math Challenge
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("DISMISSAL CHALLENGES", fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "Prevent accidental alarm dismissal by requiring math problems to be solved before the alarm turns off.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                        OutlinedButton(
                            onClick = { showPracticeMathDialog = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text("🧠  Practice Sample Math Problem", fontSize = 13.sp)
                        }
                    }
                }
            }

            // Group 6: Permissions & Device Reliability
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("PERMISSIONS & RELIABILITY", fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                        fun statusBadge(ok: Boolean) = if (ok) "✓ Granted" else "⚠️ Action Required"

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Exact Alarms", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(statusBadge(exactAllowed), fontSize = 11.sp, color = if (exactAllowed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                            }
                            if (!exactAllowed) {
                                Button(onClick = { Scheduler(activity).openExactAlarmSettings() }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                    Text("Allow", fontSize = 12.sp)
                                }
                            }
                        }

                        HorizontalDivider()

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Notifications", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(statusBadge(notificationsAllowed), fontSize = 11.sp, color = if (notificationsAllowed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                            }
                            if (!notificationsAllowed) {
                                Button(onClick = {
                                    activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                                }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                    Text("Allow", fontSize = 12.sp)
                                }
                            }
                        }

                        HorizontalDivider()

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Full-Screen Lock Intent", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(statusBadge(fullScreenAllowed), fontSize = 11.sp, color = if (fullScreenAllowed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                            }
                            if (!fullScreenAllowed && Build.VERSION.SDK_INT >= 34) {
                                Button(onClick = {
                                    activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${activity.packageName}")))
                                }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                    Text("Allow", fontSize = 12.sp)
                                }
                            }
                        }

                        HorizontalDivider()

                        Text(
                            "Battery Advice: On Motorola (Moto G85) and iQOO (Z7) devices, set WakeUp's battery usage to 'Unrestricted' in system settings so scheduled alarms fire reliably in deep sleep.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            // Group 7: App Updates & Integrity Verification
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("IN-APP UPDATES", fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("v$currentVersionName ($currentVersionCode)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        // Check status / action with smooth AnimatedContent
                        AnimatedContent(
                            targetState = updateState,
                            label = "updateStateTransition",
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(160)) }
                        ) { state ->
                            when (state) {
                                is UpdateState.Idle -> {
                                    Button(
                                        onClick = {
                                            updateState = UpdateState.Checking
                                            scope.launch { updateState = updateManager.checkForUpdates(updateServerUrl) }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Check for Updates")
                                    }
                                }
                                is UpdateState.Checking -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Text("Checking for updates…", fontSize = 13.sp)
                                    }
                                }
                                is UpdateState.UpToDate -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("✓", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                                                Text("WakeUp is up to date (v$currentVersionName)", fontSize = 13.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Medium)
                                            }
                                        }
                                        Button(
                                            onClick = {
                                                updateState = UpdateState.Checking
                                                scope.launch { updateState = updateManager.checkForUpdates(updateServerUrl) }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("Check Again") }
                                    }
                                }
                                is UpdateState.NotConfigured -> {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("Update Server Not Configured", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                            Text(state.message, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, lineHeight = 15.sp)
                                            Button(
                                                onClick = { showUrlDialog = true },
                                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                            ) { Text("Configure Manifest URL") }
                                        }
                                    }
                                }
                                is UpdateState.Available -> {
                                    Card(
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                    ) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("🎉 Update Available: v${state.info.versionName}", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                            Text("Release notes:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                            Text(state.info.releaseNotes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f))
                                            if (!state.info.sha256.isNullOrBlank()) {
                                                Text("SHA-256 Checksum: ${state.info.sha256.take(12)}…", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace)
                                            }

                                            if (downloadedApk != null) {
                                                Button(
                                                    onClick = {
                                                        if (updateManager.canInstallPackages()) {
                                                            updateManager.launchPackageInstaller(downloadedApk!!)
                                                        } else {
                                                            updateManager.openInstallPermissionSettings()
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                                ) { Text(if (updateManager.canInstallPackages()) "Install APK Now" else "Allow Install Permission") }
                                            } else {
                                                Button(
                                                    onClick = {
                                                        updateState = UpdateState.Downloading(0)
                                                        scope.launch {
                                                            runCatching {
                                                                val file = updateManager.downloadApk(state.info) { progress ->
                                                                    updateState = UpdateState.Downloading(progress)
                                                                }
                                                                downloadedApk = file
                                                                updateState = UpdateState.ReadyToInstall(file)
                                                            }.onFailure {
                                                                updateState = UpdateState.Error("Download failed: ${it.message}")
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                                ) { Text("Download Update (${(state.info.apkSizeBytes / 1024 / 1024).coerceAtLeast(1)} MB)") }
                                            }
                                        }
                                    }
                                }
                                is UpdateState.Downloading -> {
                                    val animatedProgress by animateFloatAsState(
                                        targetValue = state.progressPercent / 100f,
                                        animationSpec = tween(150),
                                        label = "downloadProgress"
                                    )
                                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Downloading update: ${state.progressPercent}%", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        LinearProgressIndicator(
                                            progress = { animatedProgress },
                                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                        )
                                    }
                                }
                                is UpdateState.ReadyToInstall -> {
                                    Button(
                                        onClick = {
                                            if (updateManager.canInstallPackages()) {
                                                updateManager.launchPackageInstaller(state.apkFile)
                                            } else {
                                                updateManager.openInstallPermissionSettings()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(if (updateManager.canInstallPackages()) "Install APK Now" else "Allow Install Permission") }
                                }
                                is UpdateState.Error -> {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("Update Notice", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                                            Text(state.message, fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer, lineHeight = 15.sp)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                TextButton(onClick = {
                                                    updateState = UpdateState.Checking
                                                    scope.launch { updateState = updateManager.checkForUpdates(updateServerUrl) }
                                                }) { Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp) }
                                                TextButton(onClick = { showUrlDialog = true }) { Text("Configure URL", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp) }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        TextButton(
                            onClick = { showUrlDialog = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) { Text("Configure Manifest Endpoint URL", fontSize = 12.sp) }
                    }
                }
            }

            // Group 8: About & Privacy & Developer Credits
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "ABOUT & CREDITS",
                            fontSize = 11.sp,
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // App Identity Banner
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("⏰", fontSize = 20.sp)
                                }
                                Column {
                                    Text(
                                        "WakeUp",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "v$currentVersionName (Build $currentVersionCode)",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Developer Credits (Distinguishing developer from requester)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "App",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "WakeUp",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Developer",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "Vaibhav Patel",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Built on demand of",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "Aditya Paul",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // Privacy Commitment
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "🔒 Privacy & Local Storage",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "WakeUp is engineered with 100% on-device local storage, zero telemetry, zero analytics, and zero advertising. All custom audio recordings, alarms, and settings remain strictly private on this device.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Configure URL Dialog with Sample JSON Manifest & SHA-256 field
    if (showUrlDialog) {
        var tempUrl by remember { mutableStateOf(updateServerUrl) }
        @Suppress("DEPRECATION")
        val clipboard = LocalClipboardManager.current

        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Update Manifest URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Set an HTTPS endpoint returning your version manifest JSON (e.g., GitHub Releases raw link or private S3 bucket).",
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    OutlinedTextField(
                        value = tempUrl,
                        onValueChange = { tempUrl = it },
                        label = { Text("Manifest URL") },
                        placeholder = { Text("https://example.com/version.json") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Expected Manifest Format (with SHA-256):", fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            UpdateManager.SAMPLE_MANIFEST_JSON,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(8.dp),
                            lineHeight = 13.sp
                        )
                    }
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(UpdateManager.SAMPLE_MANIFEST_JSON))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Copy Sample JSON to Clipboard", fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    perform { store.setUpdateServerUrl(tempUrl) }
                    showUrlDialog = false
                    updateState = UpdateState.Idle
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Practice Math Challenge Dialog
    if (showPracticeMathDialog) {
        var problem by remember { mutableStateOf(MathChallenge.generateProblem(defaultChallengeDifficulty)) }
        var userAnswer by remember { mutableStateOf("") }
        var resultText by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showPracticeMathDialog = false },
            title = { Text("🧠 Practice Math Challenge") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Solve this sample problem exactly as it appears when an alarm rings:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${problem.questionText} = ?",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = userAnswer,
                        onValueChange = { userAnswer = it; resultText = null },
                        placeholder = { Text("Your answer") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(0.7f)
                    )
                    resultText?.let {
                        Text(it, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (it.startsWith("✓")) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (MathChallenge.isAnswerCorrect(problem, userAnswer)) {
                        resultText = "✓ Correct! Next problem generated."
                        userAnswer = ""
                        problem = MathChallenge.generateProblem(defaultChallengeDifficulty)
                    } else {
                        resultText = "Incorrect, try again!"
                    }
                }) { Text("Submit") }
            },
            dismissButton = {
                TextButton(onClick = { showPracticeMathDialog = false }) { Text("Close") }
            }
        )
    }
}
