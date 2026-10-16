package com.aditya.wakeup

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
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

    fun stopTestSound() {
        testPlayer?.release()
        testPlayer = null
        isTestingSound = false
    }

    BackHandler {
        stopTestSound()
        onNavigateBack()
    }

    DisposableEffect(Unit) {
        onDispose { stopTestSound() }
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
    val channelReady = remember(refreshPermissions) { (activity.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel("ringing")?.importance ?: android.app.NotificationManager.IMPORTANCE_HIGH) >= android.app.NotificationManager.IMPORTANCE_HIGH }
    val canInstall = remember(refreshPermissions) { updateManager.canInstallPackages() }

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
            // Group 1: Appearance
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("APPEARANCE", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("System", "Light", "Dark").forEach { mode ->
                                FilterChip(
                                    selected = theme == mode,
                                    onClick = { perform { store.setTheme(mode) } },
                                    label = { Text(mode, fontSize = 13.sp) }
                                )
                            }
                        }
                    }
                }
            }

            // Group 2: Default Alarm Behavior
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("DEFAULT ALARM BEHAVIOR", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        // Default Tone
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Default ringtone: $defaultSoundName", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            var showToneDropdown by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = { showToneDropdown = !showToneDropdown },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (showToneDropdown) "Hide Tone Choices" else "Change Default Tone ($defaultSoundName)")
                            }
                            if (showToneDropdown) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Ringtones.items.forEach { tone ->
                                        FilterChip(
                                            selected = defaultSound == tone.id,
                                            onClick = { perform { store.setDefaultSound(tone.id, tone.title) } },
                                            label = { Text(tone.title, fontSize = 12.sp) }
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider()

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
                            Text("Vibrate by default", fontSize = 13.sp, fontWeight = FontWeight.Medium)
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

            // Group 3: Audio Amplification Boost
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("AUDIO AMPLIFICATION BOOST", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            "Uses Android's native LoudnessEnhancer DSP effect to amplify sound beyond 100%. An integrated limiter protects speaker hardware from harsh clipping.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )

                        // Interactive Sound Test
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Boost Test: $defaultSoundName", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("Preview volume at $audioBoost%", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                                if (isTestingSound) {
                                    Button(onClick = { stopTestSound() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                        Text("⏹ Stop", fontSize = 13.sp)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            stopTestSound()
                                            runCatching {
                                                val res = Sounds.load(activity, defaultSound, defaultSoundName, false, audioBoost)
                                                testPlayer = res
                                                isTestingSound = true
                                                res.player.setOnCompletionListener { stopTestSound() }
                                                res.player.start()
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) { Text("▶ Test", fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                }
            }

            // Group 4: Dismissal Challenges & Practice
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("DISMISSAL CHALLENGES", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Prevent accidental alarm dismissal by requiring math problems to be solved before the alarm turns off. Snooze remains available if you need extra rest.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                        OutlinedButton(
                            onClick = { showPracticeMathDialog = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text("🧠  Practice Math Problem Now", fontSize = 13.sp)
                        }
                    }
                }
            }

            // Group 5: Permissions & Reliability
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("PERMISSIONS & RELIABILITY", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
                            "Battery Advice: On Motorola and iQOO devices, set WakeUp's battery usage to 'Unrestricted' in system settings so scheduled alarms fire reliably in deep sleep.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            // Group 6: App Updates
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("APP UPDATES", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("v$currentVersionName ($currentVersionCode)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        // Check status / action
                        when (val state = updateState) {
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
                            is UpdateState.NotConfigured -> {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Endpoint Not Configured", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                        Text(state.message, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, lineHeight = 15.sp)
                                        Button(
                                            onClick = { showUrlDialog = true },
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                        ) { Text("Configure Endpoint URL") }
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
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Downloading update: ${state.progressPercent}%", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    LinearProgressIndicator(
                                        progress = { state.progressPercent / 100f },
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

                        TextButton(
                            onClick = { showUrlDialog = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) { Text("Configure Manifest Endpoint URL", fontSize = 12.sp) }
                    }
                }
            }

            // Group 7: About & Diagnostics
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ABOUT & PRIVACY", fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "WakeUp is a dedicated personal alarm application engineered for Aditya. Designed with 100% on-device local storage, zero telemetry, zero analytics, and zero advertising. All custom audio recordings and alarms remain strictly private on this device.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }

    // Configure URL Dialog with Sample JSON Manifest
    if (showUrlDialog) {
        var tempUrl by remember { mutableStateOf(updateServerUrl) }
        val clipboard = LocalClipboardManager.current

        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Update Endpoint Configuration") },
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
                    Text("Expected Manifest Format:", fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
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
