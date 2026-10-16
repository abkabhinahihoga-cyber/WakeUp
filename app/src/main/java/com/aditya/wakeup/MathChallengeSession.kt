package com.aditya.wakeup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun interface CancellableHandle {
    fun cancel()
}

interface TimerScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): CancellableHandle
}

class CoroutineTimerScheduler(private val scope: CoroutineScope) : TimerScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): CancellableHandle {
        val job: Job = scope.launch {
            delay(delayMs)
            action()
        }
        return CancellableHandle { job.cancel() }
    }
}

/**
 * Manages the interactive state machine for an alarm math dismissal challenge.
 *
 * Requirements:
 * - When user begins solving, alarm ringing (audio & vibration) is paused.
 * - Keypad interaction resets a 30-second inactivity timer.
 * - If 30 seconds elapse without keypad input, ringing resumes while keeping problem and answer intact.
 * - Keypad input while ringing resumes immediately pauses ringing again and restarts the 30s timer.
 * - Correct answer advances questions; last question dismisses alarm and stops audio.
 * - Snooze or dismiss cancels inactivity timers.
 * - Leaving the app or backgrounding resumes ringing so the alarm cannot be silenced by cheating.
 * - Idempotent pause/resume ensures no duplicate players or timers.
 */
class MathChallengeSession(
    val alarmId: String,
    val questions: List<MathProblem>,
    private val onPauseRinging: () -> Unit,
    private val onResumeRinging: () -> Unit,
    private val onDismissAlarm: () -> Unit,
    val timeoutMs: Long = 30_000L,
    private val scheduler: TimerScheduler
) {
    private val _isSolving = MutableStateFlow(false)
    val isSolving: StateFlow<Boolean> = _isSolving.asStateFlow()

    private val _isRingingPaused = MutableStateFlow(false)
    val isRingingPaused: StateFlow<Boolean> = _isRingingPaused.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentInput = MutableStateFlow("")
    val currentInput: StateFlow<String> = _currentInput.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isCompleted = MutableStateFlow(false)
    val isCompleted: StateFlow<Boolean> = _isCompleted.asStateFlow()

    private var activeTimerHandle: CancellableHandle? = null

    val currentProblem: MathProblem?
        get() = questions.getOrNull(_currentIndex.value)

    val totalQuestions: Int
        get() = questions.size

    fun startSolving() {
        if (_isCompleted.value) return
        _isSolving.value = true
        pauseRingingInternal()
        resetInactivityTimer()
    }

    private fun pauseRingingInternal() {
        if (!_isRingingPaused.value) {
            _isRingingPaused.value = true
            onPauseRinging()
        }
    }

    private fun resumeRingingInternal() {
        if (_isRingingPaused.value) {
            _isRingingPaused.value = false
            onResumeRinging()
        }
    }

    private fun resetInactivityTimer() {
        activeTimerHandle?.cancel()
        activeTimerHandle = scheduler.schedule(timeoutMs) {
            onInactivityTimeout()
        }
    }

    fun onInactivityTimeout() {
        if (_isSolving.value && !_isCompleted.value) {
            // 30 seconds without keypad input -> resume audio & vibration
            // Keeps current question, entered answer, and progress completely intact!
            resumeRingingInternal()
        }
    }

    fun onKeypadInput(key: String) {
        if (_isCompleted.value) return
        if (!_isSolving.value) {
            startSolving()
        }

        // If ringing resumed due to inactivity or backgrounding, pause ringing immediately on active input
        if (!_isRingingPaused.value) {
            pauseRingingInternal()
        }

        _errorMessage.value = null
        when (key) {
            "C" -> _currentInput.value = ""
            "⌫" -> {
                val cur = _currentInput.value
                if (cur.isNotEmpty()) _currentInput.value = cur.dropLast(1)
            }
            else -> {
                if (key.all { it.isDigit() || it == '-' }) {
                    if (_currentInput.value.length < 8) {
                        _currentInput.value += key
                    }
                }
            }
        }

        // Reset inactivity timer on any valid keypad interaction
        resetInactivityTimer()
    }

    fun submitAnswer() {
        if (_isCompleted.value) return
        val problem = currentProblem ?: return

        // Submit action counts as interaction
        if (!_isRingingPaused.value) {
            pauseRingingInternal()
        }
        resetInactivityTimer()

        if (MathChallenge.isAnswerCorrect(problem, _currentInput.value)) {
            _errorMessage.value = null
            _currentInput.value = ""
            val nextIdx = _currentIndex.value + 1
            if (nextIdx >= questions.size) {
                _isCompleted.value = true
                cancelTimers()
                onDismissAlarm()
            } else {
                _currentIndex.value = nextIdx
            }
        } else {
            _errorMessage.value = "Incorrect answer! Try again."
            _currentInput.value = ""
        }
    }

    fun onAppBackgrounded() {
        // Leaving challenge screen or locking phone must not silence alarm permanently
        if (_isSolving.value && !_isCompleted.value && _isRingingPaused.value) {
            resumeRingingInternal()
            activeTimerHandle?.cancel()
            activeTimerHandle = null
        }
    }

    fun onSnooze() {
        cancelTimers()
        _isSolving.value = false
        _isRingingPaused.value = false
    }

    fun onDismiss() {
        cancelTimers()
        _isSolving.value = false
        _isRingingPaused.value = false
    }

    fun cancelTimers() {
        activeTimerHandle?.cancel()
        activeTimerHandle = null
    }

    fun release() {
        cancelTimers()
    }
}
