package com.aditya.wakeup

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object AlarmDisplay {
    fun time(hour: Int, minute: Int): String = LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    fun time(at: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = Instant.ofEpochMilli(at).atZone(zone)
        return time(local.hour, local.minute)
    }
    fun day(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (date) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH))
        }
    }
    fun remaining(at: Long, now: Long): String {
        val seconds = ((at - now).coerceAtLeast(0) + 999) / 1000
        if (seconds == 0L) return "Due now"
        if (seconds < 60) return "Rings in less than a minute"
        val minutes = (seconds + 59) / 60
        val days = minutes / 1440
        val hours = minutes % 1440 / 60
        val mins = minutes % 60
        val parts = listOfNotNull(if (days > 0) "${days}d" else null, if (hours > 0) "${hours}h" else null, if (mins > 0) "${mins}m" else null)
        return "Rings in " + parts.joinToString(" ")
    }
    fun rollsToTomorrow(a: Alarm, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        a.days.isEmpty() && day(AlarmTime.next(a.hour, a.minute, a.days, now, zone), now, zone) == "Tomorrow"
}
