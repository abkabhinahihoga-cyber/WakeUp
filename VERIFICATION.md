# WakeUp 1.6.0 Verification Report

- APK build: successful; versionCode 7; versionName `1.6.0`; package `com.aditya.wakeup`.
- Signature: Android debug keystore v2/v3 verified, backwards compatible with v1.0.0 through v1.5.0. Install directly over prior versions to preserve alarm configurations and recorded voice memos.
- Unit tests: **43 passed, 0 failures, 0 errors**.
- Android lint: **0 errors**.
- Compile SDK: 36, Target SDK: 36, Min SDK: 26.
- Validation command: `assembleDebug testDebugUnitTest lintDebug`.

## Changes Implemented & Verified

1. **Minimal Home Dashboard**:
   - Removed oversized greeting ("Good morning, Aditya") and date headers.
   - Replaced with a compact, refined branding header ("WakeUp" + settings icon).
   - Replaced large hero card with a slim, single-line/compact Next Alarm card.
   - Reduced alarm card height by ~50%: displays prominent time, schedule, compact sound/override tag, 🧠 math challenge indicator, and enabled toggle.
   - Relocated Edit and Delete into a contextual menu (`⋮`), with card tap directly opening the editor.
   - Ensured bottom content clearance (`contentPadding = 108.dp`) so the floating Add Alarm button never overlaps any alarm content or actions.

2. **Centralized Sound Settings & Simplified Alarm Creation**:
   - Centralized default alarm sound selection in Settings under "DEFAULT ALARM SOUND", exposing the full 15-ringtone library categorized into Gentle, Classic, Modern, and Urgent.
   - Added direct recording of voice memos and audio file import as default sounds in Settings.
   - New alarms automatically inherit the selected default sound from DataStore.
   - Existing alarms strictly preserve their configured sounds without mutation.
   - Streamlined alarm editor to focus on Time $\to$ Repeat $\to$ Dismissal Challenge $\to$ Snooze/Vibrate $\to$ Save, with sound selection reduced to a compact override.

3. **7 Distinct Theme Palettes**:
   - System Default, Light, Dark, Midnight, Forest, Ocean, and Warm.
   - Added visual color swatch pills in Settings displaying primary and background palette colors.
   - Centralized Material 3 `ColorScheme` definitions in `Theme.kt` applied across all screens, dialogs, bottom sheets, and alerts.
   - Persisted across app restarts in DataStore preferences.

4. **In-App Updates & SHA-256 Verification**:
   - Clean handling when no server is configured: displays "Update server not configured. Set your manifest URL in Settings." rather than false "Up to date" or broken connections.
   - Implemented SHA-256 checksum verification against the manifest hash before launching the package installer.
   - Added HTTPS downgrade protection to prevent MITM attacks during redirect sequences.
   - Verified package archive metadata (package name and version code) before invoking Android's package installer.

## APK SHA-256 Checksum

`e516ca34938d19d822a5dd214358dec7c923130d96427297907cedcd26233acb`
