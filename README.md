# WakeUp — for Aditya

A native Kotlin alarm app with Jetpack Compose Material 3, designed for Motorola Moto G85 running Android 16 and iQOO Z7 running Android 15. Minimum Android 8; compile/target SDK 36. Physical-device compatibility must be verified using the checklist below.

## Version 1.1.0

- Visible 12-hour AM/PM time input, with matching time labels on the dashboard and ringing screen.
- Live Today/Tomorrow/date and time-until-alarm previews in the editor, dashboard and individual alarm cards.
- Explicit confirmation when a one-time selection has already passed today and must ring tomorrow. Future afternoon times stay today when PM is selected.
- New alarms default to five minutes ahead. Quick Once/Daily/Weekdays presets, full weekday names, expanded editor, persistent Save button and save confirmation.
- Refreshed green/teal gradient next-alarm card, clearer typography and differentiated disabled alarms.

Install the 1.1.0 APK over 1.0.0 without uninstalling. Package identity and signing certificate are unchanged; existing alarms and recordings remain in app storage. Stored times retain their original meaning: an old 03:00 alarm is now shown as 3:00 AM; edit it to PM if afternoon was intended.

## Features

- Multiple alarms; one-time, daily, weekdays or any selected weekdays.
- Labels, enable/disable, edit/delete, next alarm summary.
- Exact `AlarmManager.setAlarmClock` scheduling, with explicit permission setup.
- Recreates schedules after reboot (after first unlock), app update, time/time-zone changes and exact-alarm permission grants.
- Full-screen lock-screen alarm activity through a high-priority alarm notification.
- Foreground media playback using the alarm audio stream, vibration, snooze (5/10/15 minutes) and dismissal from the screen or notification.
- Simultaneous alarms queue and play one at a time; each has its own recurring/snooze schedule. Each ringing alarm auto-dismisses after 10 minutes.
- Record up to two minutes of voice in AAC, import audio up to 25 MB, preview sounds, and choose three original bundled WAV tones.
- System, light and dark themes. Private on-device storage; no Internet permission, analytics or accounts.

## Build

Open this folder in Android Studio. Install Android SDK 36 and Build Tools 36.0.0. Use JDK 17 or newer supported by Gradle 9.1 (this environment has Android Studio's JDK 25). The Gradle wrapper is included. Dependencies require Google's Maven repository and Maven Central on first build.

```text
./gradlew assembleDebug testDebugUnitTest lintDebug
```

On Windows use `gradlew.bat`. Configure your SDK path in a local, untracked `local.properties`, or use `ANDROID_HOME`.

Output: `app/build/outputs/apk/debug/app-debug.apk`. Debug builds are for sideload testing. A production release needs your own signing key; no release key is supplied.

## First launch

1. Grant exact alarms, notifications and full-screen alarms in the setup panel.
2. Set audible **alarm** volume in Android sound settings. Do Not Disturb rules remain under the user's control.
3. Add an alarm two minutes ahead and test with the phone locked.
4. Check app battery/background restrictions on each phone. OEM labels vary; use Setup → Open app settings. A force-stopped app cannot deliver alarms until reopened.
5. After restarting the phone, unlock once to let WakeUp restore schedules. This version does not support alarms before first unlock or when the phone is powered off.

If exact-alarm access is missing, alarms are retained but not scheduled; the app displays a setup warning. Regranting access reschedules them. If full-screen access is missing, the notification is the entry point. A locked-phone full-screen launch is decided by Android; an unlocked phone may show a heads-up notification instead.

## Architecture

- `Alarm.kt`: immutable model, pure time calculations and event transitions.
- `AlarmStore.kt`: Preferences DataStore containing versioned JSON alarm records and theme preference. Atomic serialized writes; one store per application. DataStore is used instead of Room because the small alarm collection does not require relational queries.
- `Scheduler.kt`: distinct PendingIntent identities for each normal/snooze alarm; token validation rejects stale deliveries after edits; broadcast receivers reschedule and dispatch playback.
- `AlarmService.kt`: alarm-channel notification, audio/vibration, bounded wake lock and timeout; persistent ringing queue supports service restart.
- `MainActivity.kt`: Compose dashboard, editor, settings, private audio import and recording.
- `AlarmActivity.kt`: lock-screen snooze/dismiss surface.

Recurring alarms are scheduled as successive exact one-shots, not Android's inexact repeating alarms. Local-time recurrence follows the current device time zone. In daylight-saving gaps, the time moves forward by the gap; in overlaps, the earlier occurrence is selected. Missed alarms discovered during rescheduling move to their next occurrence; expired snoozes are cleared. DataStore and audio live in credential-encrypted app storage, with Android backup disabled to avoid restoring stale schedules or broken audio references.

Recordings/imports are copied into app-private storage, so later removal of the source document does not break alarms. Deleted alarms do not currently remove their audio files; clearing app storage or uninstalling removes them. No file is uploaded.

## Device acceptance checklist (not yet executed)

Run on both Moto G85 / Android 16 and iQOO Z7 / Android 15:

- Fresh install: deny/grant each permission; verify warning and recovery. Revoke exact access then regrant.
- Set one-time alarm, lock phone; verify screen, audible sound and vibration. Dismiss and confirm no duplicate ring.
- Snooze from notification and full-screen UI; verify the selected delay, including a one-time alarm that has disabled itself.
- Schedule two alarms for the same minute; dismiss/snooze each and verify both are handled.
- Edit, toggle off and delete a scheduled alarm; confirm old schedule never fires.
- Weekday/weekend recurrence; timezone/time changes; reboot then unlock; app update.
- Leave phone idle overnight with manufacturer battery restrictions configured appropriately.
- Record, stop, preview, save; deny microphone; background during recording; short recording; two-minute cap.
- Import a supported audio file, delete source, reboot and verify playback. Reject corrupt/oversized files; test missing file fallback.
- Change light/dark/system theme, rotate screen, use large fonts and TalkBack.
- Check alarm volume zero and Do Not Disturb behavior; verify settings guidance.
- Verify automatic silence at ten minutes, foreground-service restart and notification controls.

## Platform references

- [Android exact-alarm scheduling](https://developer.android.com/develop/background-work/services/alarms)
- [Exact-alarm permission changes](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)
- [Android Gradle Plugin 9.0 compatibility](https://developer.android.com/build/releases/agp-9-0-0-release-notes)

The three bundled sounds were synthesized specifically for this project and have no third-party audio dependency.
