# WakeUp 1.1.0 verification

- APK build: successful; version code 2; package `com.aditya.wakeup`.
- Signature: verified (v2), same signing certificate as 1.0.0. Install as an update; do not uninstall to preserve alarms and recordings.
- Unit tests: **24 passed, 0 failures, 0 errors**.
- Android lint: **0 errors, 14 warnings** (dependency updates, API version and optional Kotlin syntax).
- Compile/target SDK 36; minimum SDK 26.
- Validation command: `assembleDebug testDebugUnitTest lintDebug`.

## Changes verified by automated tests

AM/PM labels including noon/midnight; same-day afternoon scheduling in Asia/Kolkata; past morning selections requiring tomorrow; remaining-time formatting; date rollover in the phone timezone; recurring alarm behavior; stale triggers; snooze/dismiss and persistent ringing timestamps.

## Manual checks still needed

No device UI, recording/playback, locked-screen or reboot test was performed for this update. On Moto G85 / Android 16 and iQOO Z7 / Android 15:

1. Install over 1.0.0. Confirm existing alarms and recordings remain.
2. Create an alarm five minutes ahead. Check AM/PM, Today/Tomorrow and countdown before and after saving.
3. In the afternoon, select a later PM time: it should show Today. Select the equivalent earlier AM time: it should explain that the time passed and ask before saving for Tomorrow.
4. Check 12 AM (midnight), 12 PM (noon), repeating presets and weekday selections.
5. Check both themes, keyboard visibility, scrolling, persistent Save button and large text on the phone.
6. Lock the phone and verify ring, snooze and dismiss.

Existing alarms are not converted between AM and PM automatically. An old 03:00 selection remains 3:00 AM; edit it to PM if afternoon was intended. Alarms after reboot still require the first unlock. This remains a debug-signed test build.

## APK SHA-256

`cec40576f057ff54ceb8998fe115f75d69e68d4c5e509498434fa28eb5ca6454`
