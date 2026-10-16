package com.aditya.wakeup

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AlarmDisplayTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-10-16T08:30:00Z").toEpochMilli() // 2 PM
    @Test fun afternoonSelectionStaysToday() {
        val next = AlarmTime.next(15, 0, emptySet(), now, zone)
        assertEquals("Today", AlarmDisplay.day(next, now, zone))
        assertEquals("3:00 PM", AlarmDisplay.time(next, zone))
        assertEquals("Rings in 1h", AlarmDisplay.remaining(next, now))
    }
    @Test fun morningSelectionExplainsTomorrow() {
        assertTrue(AlarmDisplay.rollsToTomorrow(Alarm(hour = 3), now, zone))
        assertFalse(AlarmDisplay.rollsToTomorrow(Alarm(hour = 15), now, zone))
    }
    @Test fun noonAndMidnightAreUnambiguous() {
        assertEquals("12:00 AM", AlarmDisplay.time(0, 0))
        assertEquals("12:00 PM", AlarmDisplay.time(12, 0))
        assertEquals("11:59 PM", AlarmDisplay.time(23, 59))
    }
    @Test fun countdownBoundaries() {
        assertEquals("Due now", AlarmDisplay.remaining(now - 1, now))
        assertEquals("Rings in less than a minute", AlarmDisplay.remaining(now + 30000, now))
        assertEquals("Rings in 1m", AlarmDisplay.remaining(now + 60000, now))
        assertEquals("Rings in 1h 1m", AlarmDisplay.remaining(now + 3660000, now))
        assertEquals("Rings in 1d 1h", AlarmDisplay.remaining(now + 90000000, now))
    }
    @Test fun dateLabelsUsePhoneTimezone() {
        val beforeMidnight = Instant.parse("2026-10-16T18:29:00Z").toEpochMilli()
        assertEquals("Tomorrow", AlarmDisplay.day(beforeMidnight + 120000, beforeMidnight, zone))
    }
    @Test fun recurringDayIsNotMistakenForOneShotRollover() {
        assertFalse(AlarmDisplay.rollsToTomorrow(Alarm(hour = 3, days = setOf(6)), now, zone))
    }
}
