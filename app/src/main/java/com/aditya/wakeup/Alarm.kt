package com.aditya.wakeup

import java.time.Instant
import java.time.ZoneId

data class Alarm(
    val id: String = java.util.UUID.randomUUID().toString(),
    val hour: Int = 7, val minute: Int = 0,
    val label: String = "Rise and shine, Aditya",
    val days: Set<Int> = emptySet(), // ISO weekday: Monday=1, Sunday=7. Empty means once.
    val enabled: Boolean = true,
    val sound: String = "dawn", val soundName: String = "Soft dawn",
    val snoozeMinutes: Int = 5, val vibrate: Boolean = true,
    val nextAt: Long = 0, val snoozeAt: Long = 0, val ringing: Boolean = false,
    val ringingSince: Long = 0,
    val challengeType: String = "none",
    val challengeDifficulty: String = "easy",
    val challengeQuestions: Int = 3
)

object AlarmTime {
    fun next(hour: Int, minute: Int, days: Set<Int>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        require(hour in 0..23 && minute in 0..59 && days.all { it in 1..7 })
        val local = Instant.ofEpochMilli(now).atZone(zone)
        for (offset in 0L..7L) {
            val date = local.toLocalDate().plusDays(offset)
            if (days.isNotEmpty() && date.dayOfWeek.value !in days) continue
            // atZone moves nonexistent DST times forward and selects the first overlap.
            val candidate = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            if (candidate > now) return candidate
        }
        error("No future alarm occurrence")
    }
}

object AlarmTransitions {
    fun fire(a: Alarm, token: Long, snooze: Boolean, now: Long, zone: ZoneId = ZoneId.systemDefault()): Alarm? {
        val expected = if (snooze) a.snoozeAt else a.nextAt
        if (expected == 0L || expected != token || (!snooze && !a.enabled)) return null
        return a.copy(ringing = true, ringingSince = if (a.ringing) a.ringingSince else now, snoozeAt = 0,
            enabled = if (snooze) a.enabled else a.days.isNotEmpty(),
            nextAt = if (snooze) a.nextAt else if (a.days.isNotEmpty()) AlarmTime.next(a.hour, a.minute, a.days, now, zone) else 0)
    }
    fun finish(a: Alarm, snooze: Boolean, now: Long): Alarm =
        if (!a.ringing) a else a.copy(ringing = false, ringingSince = 0, snoozeAt = if (snooze) now + a.snoozeMinutes * 60_000L else 0)
}
