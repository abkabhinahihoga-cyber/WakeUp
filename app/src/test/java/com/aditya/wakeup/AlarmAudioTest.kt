package com.aditya.wakeup

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AlarmAudioTest {
    private val now = Instant.parse("2026-10-16T07:00:00Z").toEpochMilli()

    @Test
    fun bundledSoundsIdentifiedCorrectly() {
        // Test all 15 bundled ringtones
        Ringtones.items.forEach { tone ->
            assertTrue("Tone '${tone.id}' should be identified as bundled", Sounds.isBundled(tone.id))
        }

        assertFalse(Sounds.isBundled("voice-123.m4a"))
        assertFalse(Sounds.isBundled("import-456.audio"))
        assertFalse(Sounds.isBundled("random_sound"))
    }

    @Test
    fun all15RingtonesConfiguredWithValidMetadata() {
        assertEquals(15, Ringtones.items.size)
        val uniqueIds = Ringtones.items.map { it.id }.toSet()
        assertEquals("Each of the 15 ringtones must have a unique ID", 15, uniqueIds.size)

        val validCategories = setOf("Gentle", "Classic", "Modern", "Urgent")
        Ringtones.items.forEach { tone ->
            assertTrue("Title for ${tone.id} must not be blank", tone.title.isNotBlank())
            assertTrue("Description for ${tone.id} must not be blank", tone.description.isNotBlank())
            assertTrue("Category '${tone.category}' for ${tone.id} must be valid", tone.category in validCategories)
            assertTrue("Resource ID for ${tone.id} must be valid", tone.resId != 0)
        }
    }

    @Test
    fun audioBoostGainMathAndFormatting() {
        // 100% -> 0 mB (0 dB)
        assertEquals(0, AudioBoostManager.percentageToGainMB(100))
        assertEquals("0 dB", AudioBoostManager.formatGainDB(100))

        // 150% -> round(2000 * log10(1.5)) = round(2000 * 0.17609) = 352 mB (+3.5 dB)
        assertEquals(352, AudioBoostManager.percentageToGainMB(150))
        assertEquals("+3.5 dB", AudioBoostManager.formatGainDB(150))

        // 200% -> round(2000 * log10(2.0)) = round(2000 * 0.30103) = 602 mB (+6.0 dB)
        assertEquals(602, AudioBoostManager.percentageToGainMB(200))
        assertEquals("+6.0 dB", AudioBoostManager.formatGainDB(200))

        // Boundary clamping
        assertEquals(0, AudioBoostManager.percentageToGainMB(50))
        assertEquals(602, AudioBoostManager.percentageToGainMB(250))
    }

    @Test
    fun updateManagerManifestParsingAndComparison() {
        val availableJson = """
            {
                "versionCode": 6,
                "versionName": "1.5.0",
                "releaseNotes": "Enhanced audio amplification and design updates.",
                "downloadUrl": "https://github.com/aditya/wakeup-alarm/releases/download/v1.5.0/WakeUp-1.5.0.apk",
                "apkSizeBytes": 15728640
            }
        """.trimIndent()

        // When remote version (6) is higher than current version (5)
        val availableState = UpdateManager.parseManifest(availableJson, currentCode = 5, currentName = "1.4.0")
        assertTrue("Higher remote versionCode must trigger Available state", availableState is UpdateState.Available)
        val available = availableState as UpdateState.Available
        assertEquals(6, available.info.versionCode)
        assertEquals("1.5.0", available.info.versionName)
        assertEquals("https://github.com/aditya/wakeup-alarm/releases/download/v1.5.0/WakeUp-1.5.0.apk", available.info.downloadUrl)

        // When remote version (6) is same as current version (6)
        val upToDateState = UpdateManager.parseManifest(availableJson, currentCode = 6, currentName = "1.5.0")
        assertTrue("Matching versionCode must trigger UpToDate state", upToDateState is UpdateState.UpToDate)

        // When remote version is lower
        val upToDateLower = UpdateManager.parseManifest(availableJson, currentCode = 7, currentName = "1.6.0")
        assertTrue("Lower remote versionCode must trigger UpToDate state", upToDateLower is UpdateState.UpToDate)

        // Invalid manifest
        val errorState = UpdateManager.parseManifest("{}", currentCode = 5, currentName = "1.4.0")
        assertTrue("Missing fields must trigger Error state", errorState is UpdateState.Error)
    }

    @Test
    fun updateVersionComparisonDirectLogic() {
        assertTrue("Remote > Current must return true", UpdateManager.isUpdateAvailable(currentCode = 5, remoteCode = 6))
        assertFalse("Remote == Current must return false", UpdateManager.isUpdateAvailable(currentCode = 6, remoteCode = 6))
        assertFalse("Remote < Current must return false", UpdateManager.isUpdateAvailable(currentCode = 7, remoteCode = 6))
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
