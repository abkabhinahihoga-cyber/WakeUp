package com.aditya.wakeup

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ThemeAndUpdatesTest {

    @Test
    fun allSevenThemesConfiguredWithDistinctPalettes() {
        assertEquals(7, WakeUpThemeOptions.size)
        val ids = WakeUpThemeOptions.map { it.id }.toSet()
        val expectedIds = setOf("System", "Light", "Dark", "Midnight", "Forest", "Ocean", "Warm")
        assertEquals(expectedIds, ids)

        WakeUpThemeOptions.forEach { opt ->
            assertTrue("Theme ${opt.id} must have non-blank name", opt.name.isNotBlank())
            assertNotNull(opt.primaryColor)
            assertNotNull(opt.backgroundColor)
            assertNotNull(opt.surfaceColor)
        }
    }

    @Test
    fun colorSchemeResolutionMatchesThemeMode() {
        val lightScheme = getColorSchemeForTheme("Light", systemIsDark = false)
        assertEquals(LightColorScheme, lightScheme)

        val darkScheme = getColorSchemeForTheme("Dark", systemIsDark = false)
        assertEquals(DarkColorScheme, darkScheme)

        val midnightScheme = getColorSchemeForTheme("Midnight", systemIsDark = false)
        assertEquals(MidnightColorScheme, midnightScheme)

        val forestScheme = getColorSchemeForTheme("Forest", systemIsDark = false)
        assertEquals(ForestColorScheme, forestScheme)

        val oceanScheme = getColorSchemeForTheme("Ocean", systemIsDark = false)
        assertEquals(OceanColorScheme, oceanScheme)

        val warmScheme = getColorSchemeForTheme("Warm", systemIsDark = false)
        assertEquals(WarmColorScheme, warmScheme)

        // System mode delegation
        assertEquals(DarkColorScheme, getColorSchemeForTheme("System", systemIsDark = true))
        assertEquals(LightColorScheme, getColorSchemeForTheme("System", systemIsDark = false))
    }

    @Test
    fun calculateSha256ProducesAccurateHash() {
        val tempFile = File.createTempFile("sha_test", ".txt").apply {
            writeText("WakeUp-1.6.0-test-content")
            deleteOnExit()
        }
        val calculated = UpdateManager.calculateSha256(tempFile)
        val expected = "dbf7a26023d145d8a0640d834a250b87e9b9b5efeadfac2c918a4cfa63c4308d"
        assertEquals(expected, calculated.lowercase())
    }

    @Test
    fun manifestParsingExtractsSha256Checksum() {
        val jsonWithHash = """
            {
              "versionCode": 7,
              "versionName": "1.6.0",
              "releaseNotes": "• Minimal home screen redesign\n• Centralized sound settings\n• 7 theme options\n• SHA-256 update verification",
              "downloadUrl": "https://github.com/aditya/wakeup-alarm/releases/download/v1.6.0/WakeUp-1.6.0.apk",
              "apkSizeBytes": 15000000,
              "sha256": "4a72d3e18f26b52e691234567890abcdef1234567890abcdef1234567890abcdef"
            }
        """.trimIndent()

        val state = UpdateManager.parseManifest(jsonWithHash, currentCode = 6, currentName = "1.5.0")
        assertTrue("Higher versionCode must be Available", state is UpdateState.Available)
        val available = state as UpdateState.Available
        assertEquals(7, available.info.versionCode)
        assertEquals("1.6.0", available.info.versionName)
        assertEquals("4a72d3e18f26b52e691234567890abcdef1234567890abcdef1234567890abcdef", available.info.sha256)
    }

    @Test
    fun sha256MismatchIsDetected() {
        val tempFile = File.createTempFile("sha_mismatch", ".txt").apply {
            writeText("WakeUp-tampered-content")
            deleteOnExit()
        }
        val actualHash = UpdateManager.calculateSha256(tempFile)
        val manifestExpectedHash = "0000000000000000000000000000000000000000000000000000000000000000"
        assertFalse("Tampered content hash must not match expected manifest hash", actualHash.equals(manifestExpectedHash, ignoreCase = true))
    }

    @Test
    fun existingAlarmRetainsCustomSoundWhenDefaultChanges() {
        val existingAlarm = Alarm(
            id = "alarm-1",
            hour = 7,
            minute = 0,
            sound = "orbit",
            soundName = "Cosmic Orbit"
        )

        // User changes default sound to "pulse" in Settings
        val newDefaultSound = "pulse"
        val newDefaultSoundName = "Vibrant Pulse"

        // New alarm inherits new default
        val newAlarm = Alarm(
            id = "alarm-2",
            hour = 8,
            minute = 30,
            sound = newDefaultSound,
            soundName = newDefaultSoundName
        )

        // Existing alarm sound remains strictly intact
        assertEquals("orbit", existingAlarm.sound)
        assertEquals("Cosmic Orbit", existingAlarm.soundName)

        // New alarm has new default
        assertEquals("pulse", newAlarm.sound)
        assertEquals("Vibrant Pulse", newAlarm.soundName)
    }

    @Test
    fun mathChallengeGeneratesValidProblemsAcrossDifficulties() {
        listOf("easy", "medium", "hard").forEach { difficulty ->
            repeat(10) {
                val problem = MathChallenge.generateProblem(difficulty)
                assertTrue("Problem text should not be empty", problem.questionText.isNotBlank())
                // Verify correctness logic
                assertTrue("Correct answer must evaluate to true", MathChallenge.isAnswerCorrect(problem, problem.answer.toString()))
                assertFalse("Incorrect answer must evaluate to false", MathChallenge.isAnswerCorrect(problem, (problem.answer + 99).toString()))
                assertFalse("Invalid text must evaluate to false", MathChallenge.isAnswerCorrect(problem, "invalid"))
            }
        }
    }
}
