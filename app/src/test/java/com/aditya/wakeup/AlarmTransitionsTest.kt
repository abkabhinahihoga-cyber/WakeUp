package com.aditya.wakeup

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AlarmTransitionsTest {
    private val now = Instant.parse("2026-10-16T07:00:00Z").toEpochMilli()
    @Test fun oneShotFiresOnceAndDisables() {
        val a = Alarm(nextAt = now)
        val ringing = AlarmTransitions.fire(a, now, false, now)!!
        assertTrue(ringing.ringing); assertFalse(ringing.enabled); assertEquals(0L, ringing.nextAt)
        assertNull(AlarmTransitions.fire(ringing, now, false, now))
    }
    @Test fun oldPendingIntentIsIgnoredAfterEdit() {
        assertNull(AlarmTransitions.fire(Alarm(nextAt = now + 60000), now, false, now))
    }
    @Test fun disabledAlarmDoesNotFire() {
        assertNull(AlarmTransitions.fire(Alarm(enabled = false, nextAt = now), now, false, now))
    }
    @Test fun snoozeWorksForDisabledOneShot() {
        val ringing = Alarm(enabled = false, ringing = true, snoozeMinutes = 10)
        val snoozed = AlarmTransitions.finish(ringing, true, now)
        assertFalse(snoozed.ringing); assertEquals(now + 600000, snoozed.snoozeAt)
        assertTrue(AlarmTransitions.fire(snoozed, snoozed.snoozeAt, true, snoozed.snoozeAt)!!.ringing)
    }
    @Test fun recurringAlarmSchedulesNextWeekBeforeDismissal() {
        val a = Alarm(days = setOf(5), nextAt = now)
        val ringing = AlarmTransitions.fire(a, now, false, now, ZoneId.of("UTC"))!!
        assertEquals(now + 7 * 86400000L, ringing.nextAt)
        assertTrue(ringing.enabled)
        assertEquals(ringing.nextAt, AlarmTransitions.finish(ringing, false, now).nextAt)
    }
    @Test fun dismissIsIdempotent() {
        val a = Alarm(ringing = true)
        val dismissed = AlarmTransitions.finish(a, false, now)
        assertEquals(dismissed, AlarmTransitions.finish(dismissed, true, now))
    }
    @Test fun regularOccurrenceCancelsAnOlderSnooze() {
        val a = Alarm(nextAt = now, snoozeAt = now + 60000)
        assertEquals(0L, AlarmTransitions.fire(a, now, false, now)!!.snoozeAt)
    }
    @Test fun ringingTimestampSurvivesAnotherOccurrence() {
        val a = Alarm(ringing = true, ringingSince = now - 1000, nextAt = now)
        assertEquals(now - 1000, AlarmTransitions.fire(a, now, false, now)!!.ringingSince)
    }
    @Test fun dismissalClearsRingingTimestamp() {
        assertEquals(0L, AlarmTransitions.finish(Alarm(ringing = true, ringingSince = now), false, now).ringingSince)
    }
}
