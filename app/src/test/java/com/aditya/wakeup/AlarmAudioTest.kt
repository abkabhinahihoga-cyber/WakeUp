package com.aditya.wakeup

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AlarmAudioTest {
    private val now = Instant.parse("2026-10-16T07:00:00Z").toEpochMilli()

    @Test
    fun bundledSoundsIdentifiedCorrectly() {
        assertTrue(Sounds.isBundled("dawn"))
        assertTrue(Sounds.isBundled("orbit"))
        assertTrue(Sounds.isBundled("pulse"))
        assertFalse(Sounds.isBundled("voice-123.m4a"))
        assertFalse(Sounds.isBundled("import-456.audio"))
        assertFalse(Sounds.isBundled("random_sound"))
    }

    @Test
    fun alarmFiredPreservesCustomAudioProperties() {
        val customAlarm = Alarm(
            nextAt = now,
            sound = "voice-789-xyz.m4a",
            soundName = "My morning voice"
        )
        val ringing = AlarmTransitions.fire(customAlarm, now, false, now)
        assertNotNull(ringing)
        assertEquals("voice-789-xyz.m4a", ringing!!.sound)
        assertEquals("My morning voice", ringing.soundName)
    }

    @Test
    fun alarmFinishedPreservesCustomAudioProperties() {
        val customRinging = Alarm(
            ringing = true,
            ringingSince = now,
            sound = "import-sample.mp3",
            soundName = "Favorite Song"
        )
        val dismissed = AlarmTransitions.finish(customRinging, false, now)
        assertEquals("import-sample.mp3", dismissed.sound)
        assertEquals("Favorite Song", dismissed.soundName)

        val snoozed = AlarmTransitions.finish(customRinging, true, now)
        assertEquals("import-sample.mp3", snoozed.sound)
        assertEquals("Favorite Song", snoozed.soundName)
    }

    @Test
    fun defaultSoundIsDawn() {
        val defaultAlarm = Alarm()
        assertEquals("dawn", defaultAlarm.sound)
        assertEquals("Soft dawn", defaultAlarm.soundName)
        assertTrue(Sounds.isBundled(defaultAlarm.sound))
    }
}
