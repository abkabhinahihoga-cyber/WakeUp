package com.aditya.wakeup

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FakeTimerScheduler : TimerScheduler {
    var scheduledDelayMs: Long? = null
    var pendingAction: (() -> Unit)? = null
    var isCancelled: Boolean = false
    var totalSchedules: Int = 0

    override fun schedule(delayMs: Long, action: () -> Unit): CancellableHandle {
        totalSchedules++
        scheduledDelayMs = delayMs
        pendingAction = action
        isCancelled = false
        return CancellableHandle {
            isCancelled = true
            pendingAction = null
        }
    }

    fun fireTimeout() {
        if (!isCancelled) {
            val action = pendingAction
            pendingAction = null
            action?.invoke()
        }
    }

    fun hasPendingTimer(): Boolean = pendingAction != null && !isCancelled
}

class MathChallengeRingingTest {

    private lateinit var scheduler: FakeTimerScheduler
    private var pauseCallCount = 0
    private var resumeCallCount = 0
    private var dismissCallCount = 0
    private val testProblems = listOf(
        MathProblem("3 + 5", 8),
        MathProblem("12 - 4", 8),
        MathProblem("6 × 3", 18)
    )

    private fun createSession(timeoutMs: Long = 30_000L): MathChallengeSession {
        return MathChallengeSession(
            alarmId = "test-alarm-id",
            questions = testProblems,
            onPauseRinging = { pauseCallCount++ },
            onResumeRinging = { resumeCallCount++ },
            onDismissAlarm = { dismissCallCount++ },
            timeoutMs = timeoutMs,
            scheduler = scheduler
        )
    }

    @Before
    fun setUp() {
        scheduler = FakeTimerScheduler()
        pauseCallCount = 0
        resumeCallCount = 0
        dismissCallCount = 0
    }

    @Test
    fun solveMathPausesAudioAndVibration() {
        val session = createSession()
        assertFalse(session.isSolving.value)
        assertFalse(session.isRingingPaused.value)

        session.startSolving()

        assertTrue(session.isSolving.value)
        assertTrue(session.isRingingPaused.value)
        assertEquals(1, pauseCallCount)
        assertEquals(0, resumeCallCount)
        assertTrue(scheduler.hasPendingTimer())
        assertEquals(30_000L, scheduler.scheduledDelayMs)
    }

    @Test
    fun thirtySecondsWithoutInputResumesRinging() {
        val session = createSession()
        session.startSolving()
        assertEquals(1, pauseCallCount)
        assertEquals(0, resumeCallCount)

        // 30 seconds pass without keypad input
        scheduler.fireTimeout()

        assertFalse(session.isRingingPaused.value)
        assertTrue(session.isSolving.value)
        assertEquals(1, resumeCallCount)
    }

    @Test
    fun keypadInputResetsInactivityTimeout() {
        val session = createSession()
        session.startSolving()
        assertEquals(1, scheduler.totalSchedules)

        // User enters '8' on keypad
        session.onKeypadInput("8")

        // Inactivity timer must be rescheduled
        assertEquals(2, scheduler.totalSchedules)
        assertTrue(scheduler.hasPendingTimer())
        assertEquals("8", session.currentInput.value)
    }

    @Test
    fun currentQuestionAndEnteredAnswerSurvivePauseResume() {
        val session = createSession()
        session.startSolving()

        // User enters partial answer "8"
        session.onKeypadInput("8")
        assertEquals("8", session.currentInput.value)
        assertEquals(0, session.currentIndex.value)
        assertEquals("3 + 5", session.currentProblem?.questionText)

        // 30 seconds pass without further input -> ringing resumes
        scheduler.fireTimeout()
        assertFalse(session.isRingingPaused.value)

        // Problem and input remain completely intact
        assertEquals(0, session.currentIndex.value)
        assertEquals("3 + 5", session.currentProblem?.questionText)
        assertEquals("8", session.currentInput.value)

        // User resumes typing -> ringing immediately pauses again, and input appends
        session.onKeypadInput("5")
        assertTrue(session.isRingingPaused.value)
        assertEquals("85", session.currentInput.value)
        assertEquals("3 + 5", session.currentProblem?.questionText)
    }

    @Test
    fun correctAnswersAdvanceTheChallenge() {
        val session = createSession()
        session.startSolving()

        // Problem 1: "3 + 5" = 8
        session.onKeypadInput("8")
        session.submitAnswer()

        assertEquals(1, session.currentIndex.value)
        assertEquals("12 - 4", session.currentProblem?.questionText)
        assertEquals("", session.currentInput.value)
        assertFalse(session.isCompleted.value)
        assertEquals(0, dismissCallCount)

        // Problem 2: "12 - 4" = 8
        session.onKeypadInput("8")
        session.submitAnswer()

        assertEquals(2, session.currentIndex.value)
        assertEquals("6 × 3", session.currentProblem?.questionText)
        assertFalse(session.isCompleted.value)
    }

    @Test
    fun completingAllQuestionsDismissesAlarmAndReleasesAudioResources() {
        val session = createSession()
        session.startSolving()

        // Answer 1
        session.onKeypadInput("8")
        session.submitAnswer()

        // Answer 2
        session.onKeypadInput("8")
        session.submitAnswer()

        // Answer 3 (final)
        session.onKeypadInput("1")
        session.onKeypadInput("8")
        session.submitAnswer()

        assertTrue(session.isCompleted.value)
        assertEquals(1, dismissCallCount)
        assertFalse("Inactivity timer must be cancelled upon completion", scheduler.hasPendingTimer())
    }

    @Test
    fun snoozeCancelsInactivityTimerAndPreservesChallenge() {
        val session = createSession()
        session.startSolving()
        session.onKeypadInput("8")
        assertTrue(scheduler.hasPendingTimer())

        session.onSnooze()

        assertFalse("Inactivity timer must be cancelled on snooze", scheduler.hasPendingTimer())
        assertFalse(session.isSolving.value)
        assertFalse(session.isRingingPaused.value)
    }

    @Test
    fun dismissalAndServiceDestructionCancelPendingCallbacks() {
        val session = createSession()
        session.startSolving()
        assertTrue(scheduler.hasPendingTimer())

        session.onDismiss()

        assertFalse("Pending callbacks must be cancelled on dismissal", scheduler.hasPendingTimer())

        session.release()
        assertFalse(scheduler.hasPendingTimer())
    }

    @Test
    fun repeatedPauseResumeActionsDoNotCreateDuplicatePlaybackOrTimers() {
        val session = createSession()

        // Repeated startSolving calls
        session.startSolving()
        session.startSolving()
        session.startSolving()

        // Only 1 pause invocation
        assertEquals(1, pauseCallCount)

        // Timeout triggers resume
        scheduler.fireTimeout()
        assertEquals(1, resumeCallCount)

        // Redundant timeout when already resumed must not trigger duplicate resume
        scheduler.fireTimeout()
        assertEquals(1, resumeCallCount)

        // Keypad input pauses ringing again
        session.onKeypadInput("1")
        assertEquals(2, pauseCallCount)
    }
}
