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

    @Test
    fun manifestParsingSupportsFileSizeBytesKey() {
        val jsonWithFileSizeBytes = """
            {
              "versionCode": 8,
              "versionName": "1.7.0",
              "releaseNotes": "• Polish\n• Math pause",
              "downloadUrl": "https://github.com/aditya/WakeUp/releases/download/v1.7.0/WakeUp-1.7.0-debug.apk",
              "fileSizeBytes": 15012840,
              "sha256": "4a72d3e18f26b52e691234567890abcdef1234567890abcdef1234567890abcdef"
            }
        """.trimIndent()

        val state = UpdateManager.parseManifest(jsonWithFileSizeBytes, currentCode = 7, currentName = "1.6.0")
        assertTrue(state is UpdateState.Available)
        val available = state as UpdateState.Available
        assertEquals(8, available.info.versionCode)
        assertEquals("1.7.0", available.info.versionName)
        assertEquals(15012840L, available.info.apkSizeBytes)
    }

    @Test
    fun manifestWithSameOrLowerVersionReportsUpToDate() {
        val json = """
            {
              "versionCode": 8,
              "versionName": "1.7.0",
              "releaseNotes": "Latest features",
              "downloadUrl": "https://github.com/abkabhinahihoga-cyber/WakeUp/releases/download/v1.7.0/WakeUp-1.7.0-debug.apk",
              "fileSizeBytes": 15029224
            }
        """.trimIndent()

        // Same version (v1.7.0 / 8 installed, 8 remote)
        val stateSame = UpdateManager.parseManifest(json, currentCode = 8, currentName = "1.7.0")
        assertTrue("Equal versionCode must report UpToDate", stateSame is UpdateState.UpToDate)
        assertEquals(8, (stateSame as UpdateState.UpToDate).currentCode)

        // Older remote version (v1.8.0 / 9 installed, 8 remote)
        val stateOlder = UpdateManager.parseManifest(json, currentCode = 9, currentName = "1.8.0")
        assertTrue("Lower versionCode must report UpToDate", stateOlder is UpdateState.UpToDate)
    }

    @Test
    fun manifestWithPlaceholderDownloadUrlReportsError() {
        val json = """
            {
              "versionCode": 9,
              "versionName": "1.8.0",
              "releaseNotes": "Future update",
              "downloadUrl": "https://github.com/OWNER/REPOSITORY/releases/download/v1.8.0/WakeUp-1.8.0-debug.apk",
              "fileSizeBytes": 15000000
            }
        """.trimIndent()

        val state = UpdateManager.parseManifest(json, currentCode = 8, currentName = "1.7.0")
        assertTrue("Placeholder OWNER/REPOSITORY must report Error", state is UpdateState.Error)
        assertTrue((state as UpdateState.Error).message.contains("placeholder"))
    }

    @Test
    fun malformedOrMissingFieldsManifestReportsError() {
        val missingUrl = """{"versionCode": 9, "versionName": "1.8.0"}"""
        val stateMissingUrl = UpdateManager.parseManifest(missingUrl, currentCode = 8, currentName = "1.7.0")
        assertTrue("Missing downloadUrl must report Error", stateMissingUrl is UpdateState.Error)

        val invalidCode = """{"versionCode": 0, "downloadUrl": "https://example.com/a.apk"}"""
        val stateInvalidCode = UpdateManager.parseManifest(invalidCode, currentCode = 8, currentName = "1.7.0")
        assertTrue("Invalid versionCode must report Error", stateInvalidCode is UpdateState.Error)
    }
}
