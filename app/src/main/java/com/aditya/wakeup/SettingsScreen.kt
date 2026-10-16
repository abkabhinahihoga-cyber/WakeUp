package com.aditya.wakeup

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val updateServerUrl by store.updateServerUrl.collectAsStateWithLifecycle(UpdateManager.DEFAULT_UPDATE_URL)

    val scope = rememberCoroutineScope()
    val updateManager = remember { UpdateManager(activity) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }

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

    // Permission refresh
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("← Back", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { insets ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = insets.calculateTopPadding(),
                    start = insets.calculateStartPadding(layoutDirection),
                    end = insets.calculateEndPadding(layoutDirection)
                ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. APPEARANCE
            item {
                SettingsSection(title = "APPEARANCE") {
                    Text("Theme", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Select your preferred visual style", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("System", "Light", "Dark").forEach { mode ->
                            FilterChip(
                                selected = theme == mode,
                                onClick = { scope.launch { store.setTheme(mode) } },
                                label = { Text(mode) }
                            )
                        }
                    }
                }
            }

            // 2. AUDIO & AMPLIFICATION BOOST (PHASE 2)
            item {
                SettingsSection(title = "AUDIO AMPLIFICATION BOOST") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Alarm Audio Boost", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Digital gain for maximum speaker audibility", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (audioBoost > 100) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                "$audioBoost% (${AudioBoostManager.formatGainDB(audioBoost)})",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (audioBoost > 100) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = audioBoost.toFloat(),
                        onValueChange = { value ->
                            val stepped = (Math.round(value / 10f) * 10).coerceIn(100, 200)
                            scope.launch { store.setAudioBoost(stepped) }
                        },
                        valueRange = 100f..200f,
                        steps = 9
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("100% (Standard)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("150% (+3.5 dB)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("200% (+6.0 dB Max)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Up to 200% digital amplification is processed through Android's native LoudnessEnhancer engine with peak limiting. Applies across built-in ringtones, custom voice recordings, and imported sounds. Physical loudness also depends on phone alarm volume slider.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))

                    // Test sound with current boost
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Test Audio Output", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("Listen to $defaultSoundName at $audioBoost%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isTestingSound) {
                            Button(onClick = { stopTestSound() }) {
                                Text("⏹ Stop")
                            }
                        } else {
                            OutlinedButton(onClick = {
                                stopTestSound()
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        Sounds.load(activity, defaultSound, defaultSoundName, false, audioBoost)
                                    }
                                    testPlayer = res
                                    isTestingSound = true
                                    res.player.setOnCompletionListener {
                                        stopTestSound()
                                    }
                                    res.player.start()
                                }
                            }) {
                                Text("▶ Test Boost")
                            }
                        }
                    }
                }
            }

            // 3. ALARM DEFAULTS
            item {
                SettingsSection(title = "ALARM DEFAULTS") {
                    Text("Default Ringtone", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Currently: $defaultSoundName", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))

                    // Category filter for ringtones
                    var selectedCat by remember { mutableStateOf("All") }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Ringtones.categories.forEach { cat ->
                            FilterChip(
                                selected = selectedCat == cat,
                                onClick = { selectedCat = cat },
                                label = { Text(cat, fontSize = 12.sp) }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    val filteredRingtones = remember(selectedCat) {
                        if (selectedCat == "All") Ringtones.items else Ringtones.items.filter { it.category == selectedCat }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        filteredRingtones.forEach { item ->
                            val isSelected = defaultSound == item.id
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch { store.setDefaultSound(item.id, item.title) }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
                                        Text("${item.category} · ${item.description}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { scope.launch { store.setDefaultSound(item.id, item.title) } }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))

                    // Default snooze
                    Text("Default Snooze Duration", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15, 20).forEach { mins ->
                            FilterChip(
                                selected = defaultSnooze == mins,
                                onClick = { scope.launch { store.setDefaultSnoozeMinutes(mins) } },
                                label = { Text("$mins min") }
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Default Vibration", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text("Vibrate phone during alarms", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = defaultVibrate,
                            onCheckedChange = { checked -> scope.launch { store.setDefaultVibrate(checked) } }
                        )
                    }
                }
            }

            // 4. PERMISSIONS & RELIABILITY
            item {
                SettingsSection(title = "PERMISSIONS & RELIABILITY") {
                    Text("Verify system readiness for timely alarms", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))

                    fun openSettings(action: String) {
                        runCatching {
                            activity.startActivity(Intent(action, Uri.parse("package:${activity.packageName}")))
                        }.onFailure {
                            runCatching {
                                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                            }
                        }
                    }

                    PermissionStatusRow(
                        title = "Exact Alarms",
                        subtitle = "Required for ringing at the precise scheduled second",
                        isGranted = exactAllowed,
                        onFixClick = { Scheduler(activity).openExactAlarmSettings() }
                    )

                    PermissionStatusRow(
                        title = "Notifications",
                        subtitle = "Required to display ringing controls and snooze actions",
                        isGranted = notificationsAllowed,
                        onFixClick = {
                            activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                        }
                    )

                    PermissionStatusRow(
                        title = "Full-Screen Alarms",
                        subtitle = "Wakes display and shows full ringing UI over lock screen",
                        isGranted = fullScreenAllowed,
                        onFixClick = {
                            if (Build.VERSION.SDK_INT >= 34) openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        }
                    )

                    PermissionStatusRow(
                        title = "Prominent Ringing Channel",
                        subtitle = "High importance notification channel for audible ringing",
                        isGranted = channelReady,
                        onFixClick = {
                            activity.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "ringing"))
                        }
                    )

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Device Optimization Advice", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        "On Motorola and iQOO devices, ensure WakeUp's battery setting is set to 'Unrestricted' so background execution is not paused during deep sleep. Reopen WakeUp once after a phone reboot to guarantee alarm restore.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }) {
                            Text("App Info & Battery", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }) {
                            Text("System Sound Settings", fontSize = 12.sp)
                        }
                    }
                }
            }

            // 5. APP UPDATES & ABOUT (PHASE 4)
            item {
                SettingsSection(title = "APP & UPDATES") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("WakeUp Alarm App", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Version $currentVersionName (Build $currentVersionCode)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                "v$currentVersionName",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    when (val state = updateState) {
                        is UpdateState.Idle -> {
                            Button(
                                onClick = {
                                    updateState = UpdateState.Checking
                                    scope.launch {
                                        updateState = updateManager.checkForUpdates(updateServerUrl)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Check for Updates")
                            }
                        }
                        is UpdateState.Checking -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Checking update server…", fontSize = 14.sp)
                            }
                        }
                        is UpdateState.UpToDate -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("✓ You're up to date", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                                    Text("WakeUp $currentVersionName is the latest release.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                updateState = UpdateState.Checking
                                scope.launch { updateState = updateManager.checkForUpdates(updateServerUrl) }
                            }) {
                                Text("Check again")
                            }
                        }
                        is UpdateState.Available -> {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("🎉 Update Available: v${state.info.versionName}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text("Release Notes:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(state.info.releaseNotes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f))

                                    Spacer(Modifier.height(4.dp))
                                    if (downloadedApk != null) {
                                        Button(
                                            onClick = {
                                                if (updateManager.canInstallPackages()) {
                                                    updateManager.launchPackageInstaller(downloadedApk!!)
                                                } else {
                                                    updateManager.openInstallPermissionSettings()
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(if (updateManager.canInstallPackages()) "Install Update" else "Allow Install Permission")
                                        }
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
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Download and Install")
                                        }
                                    }
                                }
                            }
                        }
                        is UpdateState.Downloading -> {
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Downloading update: ${state.progressPercent}%", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                LinearProgressIndicator(
                                    progress = { state.progressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
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
                            ) {
                                Text(if (updateManager.canInstallPackages()) "Install APK Now" else "Allow Install Permission")
                            }
                        }
                        is UpdateState.Error -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Update Check Notice", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                                    Text(state.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                                    Row {
                                        TextButton(onClick = {
                                            updateState = UpdateState.Checking
                                            scope.launch { updateState = updateManager.checkForUpdates(updateServerUrl) }
                                        }) {
                                            Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer)
                                        }
                                        TextButton(onClick = { showUrlDialog = true }) {
                                            Text("Configure URL", color = MaterialTheme.colorScheme.onErrorContainer)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { showUrlDialog = true }) {
                        Text("Configure Update Endpoint URL", fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))

                    Text("About WakeUp & Privacy", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "WakeUp is a dedicated personal alarm clock engineered for Aditya. Designed with 100% on-device local storage, zero telemetry, zero analytics, and zero advertising. All custom audio recordings and alarms remain strictly private on this device.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }

    if (showUrlDialog) {
        var tempUrl by remember { mutableStateOf(updateServerUrl) }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Update Server Manifest URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Specify an HTTPS JSON endpoint returning versionCode, versionName, releaseNotes, and downloadUrl.", fontSize = 12.sp)
                    OutlinedTextField(
                        value = tempUrl,
                        onValueChange = { tempUrl = it },
                        label = { Text("Server URL") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { tempUrl = UpdateManager.DEFAULT_UPDATE_URL }) {
                        Text("Reset to default")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { store.setUpdateServerUrl(tempUrl) }
                    showUrlDialog = false
                    updateState = UpdateState.Idle
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            content = content
        )
    }
}

@Composable
private fun PermissionStatusRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onFixClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                )
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!isGranted) {
            TextButton(onClick = onFixClick) {
                Text("Allow", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text("Ready", fontSize = 12.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 8.dp))
        }
    }
}
