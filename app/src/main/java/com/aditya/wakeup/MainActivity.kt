package com.aditya.wakeup

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalTime
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val theme by app.store.theme.collectAsStateWithLifecycle("System")
            WakeUpTheme(theme) { Home(this, theme) }
        }
    }
    override fun onResume() {
        super.onResume()
        app.scope.launch {
            app.store.reconcile()
            if (app.store.alarms.first().any { it.ringing }) ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, AlarmService::class.java))
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
    val next = alarms.flatMap { listOf(if (it.enabled) it.nextAt else 0L, it.snoozeAt) }.filter { it > now }.minOrNull()
    fun perform(block: suspend () -> Unit) { scope.launch { runCatching { block() }.onFailure { error = it.message ?: "Could not save. Please try again." } } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { val soon = LocalTime.now().plusMinutes(5); editing = Alarm(hour = soon.hour, minute = soon.minute) }, containerColor = MaterialTheme.colorScheme.primary) { Text("+  New alarm", fontWeight = FontWeight.Bold) } }
    ) { inset ->
        LazyColumn(Modifier.fillMaxSize().padding(inset), contentPadding = PaddingValues(24.dp, 20.dp, 24.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("WAKEUP", letterSpacing = 4.sp, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { setup = !setup }) { Text("Setup & theme") }
                }
                Text("Your time. Your rhythm.", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                Text("Make every wake-up yours, Aditya.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(20.dp))
                Card(shape = RoundedCornerShape(32.dp)) {
                    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer))).padding(24.dp)) {
                        Text("NEXT ALARM", fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text(next?.let { AlarmDisplay.time(it) } ?: "No alarms set", fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
                        if (next != null) {
                            if (exactAllowed) {
                                Text(AlarmDisplay.day(next, now), fontSize = 18.sp)
                                Spacer(Modifier.height(20.dp))
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)) {
                                    Text(AlarmDisplay.remaining(next, now), Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                Text("Saved for ${AlarmDisplay.day(next, now)} · Exact alarms disabled", fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(14.dp))
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
                                    Text("Not scheduled · Tap Setup to allow", Modifier.clickable { setup = true }.padding(horizontal = 16.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        } else Text("Tap + New alarm to get started.", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            item { PermissionPanel(activity, setup) }
            if (setup) item {
                Column { Text("APPEARANCE", fontSize = 12.sp, letterSpacing = 2.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("System", "Light", "Dark").forEach { mode -> FilterChip(selected = theme == mode, onClick = { perform { activity.app.store.setTheme(mode) } }, label = { Text(mode) }) } }
                }
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Your alarms", fontSize = 22.sp, fontWeight = FontWeight.SemiBold); Text("${alarms.count { it.enabled }} active", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (alarms.isEmpty()) item { Text("Quiet for now. Add your first alarm below.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(alarms.sortedWith(compareBy<Alarm> { it.hour }.thenBy { it.minute }), key = { it.id }) { alarm ->
                Card(colors = CardDefaults.cardColors(containerColor = if (alarm.enabled || alarm.snoozeAt > 0) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().clickable { editing = alarm }) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(AlarmDisplay.time(alarm.hour, alarm.minute), fontSize = 34.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Switch(checked = alarm.enabled, onCheckedChange = { checked ->
                                perform {
                                    activity.app.store.save(alarm.copy(enabled = checked))
                                    if (checked && !Scheduler(activity).allowed()) {
                                        val res = snackbar.showSnackbar("Alarm enabled, but exact alarms are disabled.", actionLabel = "Allow", duration = SnackbarDuration.Long)
                                        if (res == SnackbarResult.ActionPerformed) Scheduler(activity).openExactAlarmSettings()
                                    }
                                }
                            })
                        }
                        val occurrence = listOf(if (alarm.enabled) alarm.nextAt else 0L, alarm.snoozeAt).filter { it > 0 }.minOrNull()
                        if (occurrence != null) {
                            if (exactAllowed) {
                                Text("${AlarmDisplay.day(occurrence, now)} · ${AlarmDisplay.remaining(occurrence, now)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            } else {
                                Text("${AlarmDisplay.day(occurrence, now)} · Not scheduled (exact alarm permission needed)", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(alarm.label, fontWeight = FontWeight.Medium)
                        Text(dayLabel(alarm.days), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Text("${alarm.soundName} · ${alarm.snoozeMinutes} min snooze", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        if (alarm.snoozeAt > 0) Text("Snoozed until " + AlarmDisplay.time(alarm.snoozeAt), color = MaterialTheme.colorScheme.primary)
                        Row {
                            TextButton(onClick = { editing = alarm }) { Text("Edit") }
                            TextButton(onClick = { deleting = alarm }) { Text("Delete") }
                            if (alarm.ringing) TextButton(onClick = { activity.startActivity(Intent(activity, AlarmActivity::class.java)) }) { Text("Ringing · Open") }
                        }
                    }
                }
            }
        }
    }
    editing?.let { alarm -> Editor(activity, alarm, onClose = { editing = null }, onSave = { updated -> perform {
        activity.app.store.save(updated); editing = null
        val saved = activity.app.store.alarms.first().first { it.id == updated.id }
        if (saved.enabled) {
            if (Scheduler(activity).allowed()) {
                snackbar.showSnackbar("${AlarmDisplay.day(saved.nextAt, System.currentTimeMillis())}, ${AlarmDisplay.time(saved.nextAt)} · ${AlarmDisplay.remaining(saved.nextAt, System.currentTimeMillis())}")
            } else {
                val res = snackbar.showSnackbar("Alarm saved for ${AlarmDisplay.day(saved.nextAt, System.currentTimeMillis())}, but exact alarms are disabled.", actionLabel = "Allow", duration = SnackbarDuration.Long)
                if (res == SnackbarResult.ActionPerformed) Scheduler(activity).openExactAlarmSettings()
            }
        } else {
            snackbar.showSnackbar("Alarm saved · Off")
        }
    } }) }
    deleting?.let { alarm -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this alarm?") }, text = { Text(alarm.label) }, confirmButton = { TextButton(onClick = { perform { activity.app.store.delete(alarm.id); deleting = null } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Something needs attention") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
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
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (exact && notifications && full && channelReady) "Ready for your morning" else "Finish alarm setup", fontWeight = FontWeight.Bold)
                if (!exact) Text("Alarms are saved, but cannot ring on time until exact alarms are allowed in system settings.")
                fun open(action: String) {
                    runCatching {
                        activity.startActivity(Intent(action, Uri.parse("package:${activity.packageName}")))
                    }.onFailure {
                        runCatching {
                            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                        }
                    }
                }
                if (!exact) TextButton(onClick = { Scheduler(activity).openExactAlarmSettings() }) { Text("Allow exact alarms") }
                if (!notifications) TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                }) { Text("Allow alarm notifications") }
                if (!full) TextButton(onClick = { if (Build.VERSION.SDK_INT >= 34) open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) }) { Text("Allow full-screen alarms") }
                if (!channelReady) TextButton(onClick = { activity.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "ringing")) }) { Text("Enable prominent ringing notifications") }
                if (expanded) {
                    Text("Keep alarm volume audible. On Moto and iQOO, check WakeUp’s battery/background settings if alarms are delayed. Reopen WakeUp after force-stopping it. After a reboot, unlock once to restore alarms.", fontSize = 13.sp)
                    TextButton(onClick = { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }) { Text("Open app settings") }
                    TextButton(onClick = { activity.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }) { Text("Alarm volume & sound") }
                }
            }
        }
    }
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
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf<MediaPlayer?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun stopRecording(keep: Boolean) {
        val current = recorder ?: return
        val file = recordingFile
        val success = runCatching { current.stop() }.isSuccess
        current.release(); recorder = null; recordingFile = null
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
                alarm = alarm.copy(sound = file.name, soundName = "My voice")
                message = null
            } else {
                file.delete()
                message = "Recorded audio was unreadable. Please record again."
            }
        } else {
            file?.delete()
            if (keep) message = "Recording was too short. Please speak clearly and try again."
        }
    }
    fun startRecording() {
        preview?.release(); preview = null
        runCatching {
            val folder = File(activity.filesDir, "audio").apply { mkdirs() }
            val file = File(folder, "voice-${UUID.randomUUID()}.m4a")
            recordingFile = file
            @Suppress("DEPRECATION")
            val mic = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(activity) else MediaRecorder()
            recorder = mic
            mic.setAudioSource(MediaRecorder.AudioSource.MIC); mic.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mic.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); mic.setAudioEncodingBitRate(128000); mic.setAudioSamplingRate(44100)
            mic.setOutputFile(file.absolutePath); mic.setMaxDuration(120_000)
            mic.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording(true) }
            mic.prepare(); mic.start()
        }.onFailure { recorder?.release(); recorder = null; recordingFile?.delete(); recordingFile = null; message = "Microphone unavailable. Close other recording apps and try again." }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startRecording() else message = "Microphone permission is needed to record your voice. You can still import audio." }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
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
            }.onSuccess { (file, name) -> alarm = alarm.copy(sound = file, soundName = name) }
                .onFailure { message = it.message ?: "Could not import this audio format." }
            busy = false
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { stopRecording(true); preview?.release(); preview = null } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); recorder?.release(); recordingFile?.delete(); preview?.release() }
    }
    fun commit() {
        preview?.release(); preview = null
        onSave(alarm.copy(label = alarm.label.ifBlank { "Rise and shine, Aditya" }))
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
      Column(Modifier.fillMaxHeight(0.96f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Set your alarm", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClose) { Text("Cancel") }
        }
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(24.dp, 12.dp, 24.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    TimeInput(state = timeState)
                    Text("Choose the hour, minute and AM or PM", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${AlarmDisplay.day(nextAt, now)}, ${AlarmDisplay.time(nextAt)}", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        if (Scheduler(activity).allowed()) {
                            Text(AlarmDisplay.remaining(nextAt, now), fontSize = 16.sp)
                        } else {
                            Text("Exact alarms are disabled. This alarm cannot ring.", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { Scheduler(activity).openExactAlarmSettings() }) { Text("Allow exact alarms in system settings") }
                        }
                        if (AlarmDisplay.rollsToTomorrow(alarm, now)) Text("This time has passed today. Choose a later time or check AM/PM to ring today.", fontSize = 12.sp)
                    }
                }
            }
            item { OutlinedTextField(value = alarm.label, onValueChange = { alarm = alarm.copy(label = it.take(80)) }, label = { Text("Alarm name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                Text("Repeat", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Once" to emptySet<Int>(), "Daily" to (1..7).toSet(), "Weekdays" to (1..5).toSet()).forEach { (label, days) ->
                        FilterChip(selected = alarm.days == days, onClick = { alarm = alarm.copy(days = days) }, label = { Text(label) })
                    }
                }
                Text(dayLabel(alarm.days), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { index, text ->
                        val day = index + 1
                        FilterChip(modifier = Modifier.semantics { contentDescription = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")[index] }, selected = day in alarm.days, onClick = { alarm = alarm.copy(days = if (day in alarm.days) alarm.days - day else alarm.days + day) }, label = { Text(text) })
                    }
                }
            }
            item { Text("YOUR WAKE-UP SOUND", fontSize = 12.sp, letterSpacing = 2.sp) }
            items(Sounds.bundled.entries.toList()) { (key, name) ->
                Row(Modifier.fillMaxWidth().clickable { alarm = alarm.copy(sound = key, soundName = name) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = alarm.sound == key, onClick = { alarm = alarm.copy(sound = key, soundName = name) }); Text(name)
                }
            }
            item {
                Text("Selected: ${alarm.soundName}", fontWeight = FontWeight.Medium)
                Row {
                    TextButton(enabled = recorder == null && !busy, onClick = {
                        preview?.release()
                        preview = null
                        runCatching {
                            val res = Sounds.load(activity, alarm.sound, alarm.soundName, false)
                            if (res.isFallback && !Sounds.isBundled(alarm.sound)) {
                                message = "Custom audio unavailable (${res.fallbackReason ?: "error"}). Playing fallback."
                            } else {
                                message = null
                            }
                            res.player.also {
                                preview = it
                                it.setOnCompletionListener { p -> p.release(); if (preview == p) preview = null }
                                it.start()
                            }
                        }.onFailure { message = "Unable to preview sound: ${it.message}" }
                    }) { Text("Preview") }
                    TextButton(onClick = { preview?.release(); preview = null }) { Text("Stop") }
                    TextButton(enabled = recorder == null && !busy, onClick = { import.launch(arrayOf("audio/*")) }) { Text(if (busy) "Importing…" else "Import audio") }
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    if (recorder != null) stopRecording(true)
                    else if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(if (recorder != null) "● Recording · Tap to save" else "Record your own voice") }
                Text("Up to 2 minutes. Stored privately on this phone.", fontSize = 12.sp)
            }
            item {
                Text("Snooze")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(5, 10, 15).forEach { minutes -> FilterChip(selected = alarm.snoozeMinutes == minutes, onClick = { alarm = alarm.copy(snoozeMinutes = minutes) }, label = { Text("$minutes min") }) } }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Vibrate", Modifier.weight(1f)); Switch(checked = alarm.vibrate, onCheckedChange = { alarm = alarm.copy(vibrate = it) }) }
            }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
        Surface(shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                Button(enabled = !busy && recorder == null, onClick = {
                    if (alarm.enabled && AlarmDisplay.rollsToTomorrow(alarm, System.currentTimeMillis())) confirmTomorrow = true else commit()
                }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Text(if (alarm.enabled) {
                        if (Scheduler(activity).allowed()) "Save · ${AlarmDisplay.day(nextAt, now)}"
                        else "Save (exact alarms disabled)"
                    } else "Save disabled alarm")
                }
            }
        }
      }
    }
    if (confirmTomorrow) AlertDialog(onDismissRequest = { confirmTomorrow = false },
        title = { Text("Set for tomorrow?") },
        text = { Text("${AlarmDisplay.time(alarm.hour, alarm.minute)} has already passed today. This alarm will ring tomorrow. To ring today, go back and choose a later time or change AM/PM.") },
        confirmButton = { TextButton(onClick = { confirmTomorrow = false; commit() }) { Text("Set for tomorrow") } },
        dismissButton = { TextButton(onClick = { confirmTomorrow = false }) { Text("Change time") } })
}
