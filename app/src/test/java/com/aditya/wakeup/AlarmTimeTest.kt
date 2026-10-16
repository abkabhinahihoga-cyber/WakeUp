package com.aditya.wakeup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AlarmTimeTest {
    private val utc = ZoneId.of("UTC")
    private fun at(text: String) = Instant.parse(text).toEpochMilli()
    @Test fun oneShotLaterToday() {
        assertEquals(at("2026-10-16T07:00:00Z"), AlarmTime.next(7, 0, emptySet(), at("2026-10-16T06:00:00Z"), utc))
    }
    @Test fun exactCurrentMinuteMovesToTomorrow() {
        assertEquals(at("2026-10-17T07:00:00Z"), AlarmTime.next(7, 0, emptySet(), at("2026-10-16T07:00:00Z"), utc))
    }
    @Test fun weekdaysSkipWeekend() {
        assertEquals(at("2026-10-19T07:00:00Z"), AlarmTime.next(7, 0, setOf(1,2,3,4,5), at("2026-10-16T08:00:00Z"), utc))
    }
    @Test fun sameWeekdayCanBeSevenDaysAway() {
        assertEquals(at("2026-10-23T07:00:00Z"), AlarmTime.next(7, 0, setOf(5), at("2026-10-16T08:00:00Z"), utc))
    }
    @Test fun indiaOffsetIsRespected() {
        assertEquals(at("2026-10-17T01:30:00Z"), AlarmTime.next(7, 0, emptySet(), at("2026-10-16T23:00:00Z"), ZoneId.of("Asia/Kolkata")))
    }
    @Test fun springGapMovesForward() {
        assertEquals(at("2026-03-08T07:30:00Z"), AlarmTime.next(2, 30, emptySet(), at("2026-03-08T05:00:00Z"), ZoneId.of("America/New_York")))
    }
    @Test fun autumnOverlapUsesFirstOccurrence() {
        assertEquals(at("2026-11-01T05:30:00Z"), AlarmTime.next(1, 30, emptySet(), at("2026-11-01T04:00:00Z"), ZoneId.of("America/New_York")))
    }
    @Test fun everyDayIsStrictlyFuture() {
        val now = at("2026-12-31T23:59:59Z")
        assertTrue(AlarmTime.next(0, 0, (1..7).toSet(), now, utc) > now)
        assertEquals(at("2027-01-01T00:00:00Z"), AlarmTime.next(0, 0, (1..7).toSet(), now, utc))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidTimeRejected() {
        AlarmTime.next(24, 0, emptySet(), 0, utc)
    }
}
