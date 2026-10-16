package com.aditya.wakeup

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class AlarmActivity : ComponentActivity() {
    private var mathSession: MathChallengeSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = android.media.AudioManager.STREAM_ALARM
        enableEdgeToEdge()

        val startSolvingImmediately = intent.getBooleanExtra("START_SOLVING", false)

        setContent {
            WakeUpTheme("Dark") {
                val alarms by app.store.alarms.collectAsState(initial = null)
                val alarm = alarms?.firstOrNull { it.ringing }
                val fallbackNotice by AlarmService.currentFallbackNotice.collectAsState()

                LaunchedEffect(alarms) {
                    if (alarms != null && alarm == null) finish()
                }

                var time by remember { mutableStateOf(LocalTime.now()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        time = LocalTime.now()
                        delay(1000)
                    }
                }

                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }

                BackHandler {
                    // Prevent accidental dismissal via gesture or hardware back
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF1E2D1A), Color(0xFF0F150D), Color(0xFF0A0D08))
                            )
                        )
                        .safeDrawingPadding()
                ) {
                    if (alarm != null && alarm.challengeType == "math") {
                        val session = remember(alarm.id) {
                            val count = alarm.challengeQuestions.coerceIn(1, 10)
                            val problems = MathChallenge.generateSet(alarm.challengeDifficulty, count)
                            MathChallengeSession(
                                alarmId = alarm.id,
                                questions = problems,
                                onPauseRinging = { AlarmService.pauseRinging(this@AlarmActivity) },
                                onResumeRinging = { AlarmService.resumeRinging(this@AlarmActivity) },
                                onDismissAlarm = {
                                    scope.launch {
                                        busy = true
                                        runCatching { app.store.finish(alarm.id, false) }
                                            .onFailure { error = "Could not dismiss alarm: ${it.message}" }
                                        busy = false
                                    }
                                },
                                timeoutMs = 30_000L,
                                scheduler = CoroutineTimerScheduler(scope)
                            ).also {
                                mathSession = it
                                if (startSolvingImmediately) {
                                    it.startSolving()
                                }
                            }
                        }

                        MathChallengeRingingScreen(
                            alarm = alarm,
                            session = session,
                            currentTime = time,
                            fallbackNotice = fallbackNotice,
                            onSnooze = {
                                val id = alarm.id
                                busy = true
                                scope.launch {
                                    runCatching { app.store.finish(id, true) }
                                        .onFailure { error = "Could not snooze alarm: ${it.message}" }
                                    busy = false
                                }
                            },
                            busy = busy,
                            errorMessage = error,
                            allowedToSnooze = Scheduler(this@AlarmActivity).allowed()
                        )
                    } else {
                        StandardRingingScreen(
                            alarm = alarm,
                            currentTime = time,
                            fallbackNotice = fallbackNotice,
                            onSnooze = {
                                val id = alarm?.id ?: return@StandardRingingScreen
                                busy = true
                                scope.launch {
                                    runCatching { app.store.finish(id, true) }
                                        .onFailure { error = "Could not snooze: ${it.message}" }
                                    busy = false
                                }
                            },
                            onDismiss = {
                                val id = alarm?.id ?: return@StandardRingingScreen
                                busy = true
                                scope.launch {
                                    runCatching { app.store.finish(id, false) }
                                        .onFailure { error = "Could not dismiss: ${it.message}" }
                                    busy = false
                                }
                            },
                            busy = busy,
                            errorMessage = error,
                            allowedToSnooze = Scheduler(this@AlarmActivity).allowed()
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        mathSession?.onAppBackgrounded()
    }

    override fun onDestroy() {
        mathSession?.release()
        mathSession = null
        super.onDestroy()
    }
}

@Composable
private fun StandardRingingScreen(
    alarm: Alarm?,
    currentTime: LocalTime,
    fallbackNotice: String?,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean,
    errorMessage: String?,
    allowedToSnooze: Boolean
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top branding
        Row(
            modifier = Modifier.padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFFB7F36B).copy(alpha = 0.2f),
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("⏰", fontSize = 14.sp)
                }
            }
            Text(
                "WAKEUP",
                color = Color(0xFFB7F36B),
                letterSpacing = 4.sp,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        // Center Time & Label
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                currentTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)),
                fontSize = 58.sp,
                fontWeight = FontWeight.Light,
                color = Color.White
            )
            Text(
                alarm?.label ?: "WakeUp Alarm",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE2E9DC)
            )
            if (fallbackNotice != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFFFFB74D).copy(alpha = 0.15f),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        "Custom sound unavailable · Playing Soft Dawn",
                        color = Color(0xFFFFB74D),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Bottom Actions
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            if (allowedToSnooze && (alarm?.snoozeMinutes ?: 5) > 0) {
                Button(
                    enabled = alarm != null && !busy,
                    onClick = onSnooze,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2C3E26),
                        contentColor = Color(0xFFD4E8CE)
                    )
                ) {
                    Text("Snooze (${alarm?.snoozeMinutes ?: 5} min)", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Button(
                enabled = alarm != null && !busy,
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB7F36B),
                    contentColor = Color(0xFF13220E)
                )
            ) {
                Text("I’m awake · Dismiss", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                "Ringing continuously at maximum volume until dismissed.",
                color = Color(0xFF8F9B8A),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun MathChallengeRingingScreen(
    alarm: Alarm,
    session: MathChallengeSession,
    currentTime: LocalTime,
    fallbackNotice: String?,
    onSnooze: () -> Unit,
    busy: Boolean,
    errorMessage: String?,
    allowedToSnooze: Boolean
) {
    val isSolving by session.isSolving.collectAsState()
    val isRingingPaused by session.isRingingPaused.collectAsState()
    val currentIndex by session.currentIndex.collectAsState()
    val currentInput by session.currentInput.collectAsState()
    val inputError by session.errorMessage.collectAsState()
    val currentProblem = session.currentProblem ?: MathProblem("2 + 2", 4)

    if (!isSolving) {
        // Initial Ringing Mode: Sound and vibration are active. User must tap Solve Math or Snooze.
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top branding
            Row(
                modifier = Modifier.padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFB7F36B).copy(alpha = 0.2f),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("⏰", fontSize = 14.sp)
                    }
                }
                Text(
                    "WAKEUP",
                    color = Color(0xFFB7F36B),
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            // Center Time, Label & Math Requirement notice
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    currentTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)),
                    fontSize = 58.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White
                )
                Text(
                    alarm.label,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE2E9DC)
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFFB7F36B).copy(alpha = 0.15f),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text(
                        "🧠 Solve ${session.totalQuestions} questions to dismiss",
                        color = Color(0xFFB7F36B),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
                if (fallbackNotice != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFFFFB74D).copy(alpha = 0.15f),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            "Custom sound unavailable · Playing Soft Dawn",
                            color = Color(0xFFFFB74D),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Bottom Actions: Solve Math (Primary) & Snooze
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }

                if (allowedToSnooze && alarm.snoozeMinutes > 0) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            session.onSnooze()
                            onSnooze()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2C3E26),
                            contentColor = Color(0xFFD4E8CE)
                        )
                    ) {
                        Text("Snooze (${alarm.snoozeMinutes} min)", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Button(
                    enabled = !busy,
                    onClick = { session.startSolving() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB7F36B),
                        contentColor = Color(0xFF13220E)
                    )
                ) {
                    Text("🧠 Solve Math to Dismiss", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }

                Text(
                    "Alarm rings until you tap Solve Math or Snooze.",
                    color = Color(0xFF8F9B8A),
                    fontSize = 11.sp
                )
            }
        }
    } else {
        // Active Solving Mode: Sound is paused while interacting. Inactivity (30s) or backgrounding resumes ringing.
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: Clock, Title, Progress & Ringing status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("🧠", fontSize = 16.sp)
                    Text(
                        "MATH DISMISSAL CHALLENGE",
                        color = Color(0xFFB7F36B),
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
                Text(
                    currentTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White
                )
                Text(
                    alarm.label,
                    fontSize = 14.sp,
                    color = Color(0xFFCED8C6)
                )

                Spacer(Modifier.height(4.dp))

                // Progress pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF2C3E26)
                ) {
                    Text(
                        "Problem ${currentIndex + 1} of ${session.totalQuestions}  •  ${MathChallenge.difficultyTitle(alarm.challengeDifficulty)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFB7F36B),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }

                // Quiet mode vs Resumed Warning pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isRingingPaused) Color(0xFF1E2D1A) else Color(0xFF4A1F1F),
                    border = BorderStroke(1.dp, if (isRingingPaused) Color(0xFFB7F36B).copy(alpha = 0.5f) else Color(0xFFFF8A80)),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        if (isRingingPaused) "🔇 Ringing paused · 30s inactivity timeout"
                        else "🔔 Ringing resumed! Tap keypad to pause & solve",
                        color = if (isRingingPaused) Color(0xFFD4E8CE) else Color(0xFFFFCDD2),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            // Middle: The Math Question Card and Input Display
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E281C)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Solve to dismiss alarm:",
                        fontSize = 13.sp,
                        color = Color(0xFF9EABA0)
                    )

                    // Large readable equation
                    Text(
                        text = "${currentProblem.questionText} = ?",
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    // User Input display box
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (inputError != null) Color(0xFF3E1E1E) else Color(0xFF121A11),
                        border = BorderStroke(
                            1.5.dp,
                            if (inputError != null) MaterialTheme.colorScheme.error else Color(0xFFB7F36B)
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(54.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text(
                                text = if (currentInput.isEmpty()) "Tap answer below" else currentInput,
                                fontSize = if (currentInput.isEmpty()) 15.sp else 28.sp,
                                fontWeight = if (currentInput.isEmpty()) FontWeight.Normal else FontWeight.Bold,
                                color = if (currentInput.isEmpty()) Color(0xFF6E7A6F) else Color.White
                            )
                        }
                    }

                    inputError?.let { err ->
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Numeric Keypad & Actions
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val keys = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("C", "0", "⌫")
                )

                keys.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(0.92f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { key ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = when (key) {
                                    "C" -> Color(0xFF332020)
                                    "⌫" -> Color(0xFF262D24)
                                    else -> Color(0xFF243022)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .clickable { session.onKeypadInput(key) }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        text = key,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = when (key) {
                                            "C" -> Color(0xFFFF8A80)
                                            "⌫" -> Color(0xFFB0C0AD)
                                            else -> Color.White
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))

                // Submit Button
                Button(
                    enabled = !busy && currentInput.isNotEmpty(),
                    onClick = { session.submitAnswer() },
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB7F36B),
                        contentColor = Color(0xFF13220E)
                    )
                ) {
                    Text(
                        text = if (currentIndex + 1 >= session.totalQuestions) "Submit & Dismiss Alarm" else "Submit Answer (${currentIndex + 1}/${session.totalQuestions})",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Snooze Button
                if (allowedToSnooze && alarm.snoozeMinutes > 0) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            session.onSnooze()
                            onSnooze()
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCED8C6))
                    ) {
                        Text("Snooze (${alarm.snoozeMinutes} min)", fontSize = 14.sp)
                    }
                }

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
    }
}
