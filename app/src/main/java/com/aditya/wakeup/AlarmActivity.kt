package com.aditya.wakeup

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = android.media.AudioManager.STREAM_ALARM
        enableEdgeToEdge()
        setContent {
            WakeUpTheme("Dark") {
                val alarms by app.store.alarms.collectAsState(initial = null)
                val alarm = alarms?.firstOrNull { it.ringing }
                val fallbackNotice by AlarmService.currentFallbackNotice.collectAsState()
                LaunchedEffect(alarms) { if (alarms != null && alarm == null) finish() }
                var time by remember { mutableStateOf(LocalTime.now()) }
                LaunchedEffect(Unit) { while (true) { time = LocalTime.now(); delay(1000) } }
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                BackHandler { /* Explicit snooze or dismiss prevents accidental dismissal. */ }
                Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF283E24), Color(0xFF101510)))).safeDrawingPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                    Text("WAKEUP  /  FOR ADITYA", color = Color(0xFFB7F36B), letterSpacing = 3.sp, modifier = Modifier.padding(top = 32.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("A new day is yours.", color = Color(0xFFCED8C6), fontSize = 19.sp)
                        Text(time.format(DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH)), fontSize = 54.sp, fontWeight = FontWeight.Light, color = Color.White)
                        Text(alarm?.label ?: "WakeUp", fontSize = 24.sp, color = Color.White)
                        if (fallbackNotice != null) {
                            Text("Custom audio unavailable · Playing Soft dawn", color = Color(0xFFFFB74D), fontSize = 13.sp)
                        }
                        val count = alarms?.count { it.ringing } ?: 0
                        if (count > 1) Text("$count alarms ringing · handled one at a time", color = Color(0xFFCED8C6))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        fun act(snooze: Boolean) { val id = alarm?.id ?: return; busy = true; scope.launch {
                            runCatching { app.store.finish(id, snooze) }.onFailure { error = "Could not save the alarm action. Please try again." }
                            busy = false
                        } }
                        Button(enabled = alarm != null && !busy && Scheduler(this@AlarmActivity).allowed(), onClick = { act(true) }, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("Snooze ${alarm?.snoozeMinutes ?: 5} minutes", fontSize = 18.sp) }
                        OutlinedButton(enabled = alarm != null && !busy, onClick = { act(false) }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("I’m up · Dismiss", fontSize = 18.sp) }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text("Alarms stop automatically after 10 minutes.", color = Color(0xFFCED8C6), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
