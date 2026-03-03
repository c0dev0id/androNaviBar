# androNaviBar - Phase 1: Split-Screen Toggle & Basic UI

## Overview

Build the foundation for androNaviBar, a companion app for Android navigation
systems. The app launches in split-screen mode next to the currently active app
and toggles off when tapped again. Phase 1 establishes the split-screen toggle
mechanism and the basic UI/navigation structure with placeholder content.

**Target:** Stock Android 14+ (API 34+), landscape only.

---

## Architecture

### Split-Screen Launch via TrampolineActivity

A transparent **TrampolineActivity** serves as the launcher entry point. When the
user taps the icon:

1. Trampoline checks if `MainActivity` is already running (via static flag)
2. **Not running** -> launch `MainActivity` with split-screen flags:
   - `FLAG_ACTIVITY_LAUNCH_ADJACENT | FLAG_ACTIVITY_NEW_TASK`
   - On Android 12L+ (API 32+, so all Android 14+ devices), this flag
     triggers split-screen entry from fullscreen automatically
   - Use `ActivityOptions.setLaunchBounds()` to hint at positioning
3. **Already running** -> send intent to `MainActivity` triggering quit
4. Trampoline calls `finish()` immediately (invisible to user)

### Toggle Mechanism

- `MainActivity` uses `singleTask` launch mode
- `onNewIntent()` detects re-launch and calls `finishAndRemoveTask()`
- Android automatically restores the other app to fullscreen when our app exits
- Static `isRunning` flag in `MainActivity.Companion` tracks lifecycle state

### Basic UI (Placeholder)

Based on the sketch, the layout follows a dashboard pattern:
- **Top bar**: horizontal row of tab/mode buttons
- **Content area**: grid of panels (placeholder CardViews with labels)
- All using standard XML Views (ConstraintLayout + LinearLayout)

---

## Files to Modify

### 1. `app/build.gradle.kts`
- Change `minSdk` from 26 to **34** (Android 14+)
- Add `dependencies` block: AndroidX AppCompat, ConstraintLayout, Material
  Components, CardView
- Add `signingConfigs` for release builds (reads env vars set by the release
  workflow: `SIGNING_KEYSTORE_PATH`, `SIGNING_KEYSTORE_PASSWORD`,
  `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`)
- Wire release `buildType` to use the signing config

## Files to Create

### 2. `app/src/main/res/values/strings.xml`
- App name: "androNaviBar"
- Placeholder button/panel labels

### 3. `app/src/main/res/values/themes.xml`
- App theme: `Theme.Material3.DayNight.NoActionBar` base
- Transparent theme for TrampolineActivity (`Theme.Transparent`)
- Dark color scheme (suited for car/head-unit use)

### 4. `app/src/main/res/values/colors.xml`
- Dark theme color palette (dark backgrounds, light text)

### 5. `app/src/main/AndroidManifest.xml`

**TrampolineActivity:**
- `<intent-filter>` with `MAIN` / `LAUNCHER`
- `android:theme="@style/Theme.Transparent"`
- `android:excludeFromRecents="true"`
- `android:noHistory="true"`
- `android:taskAffinity=""`
- `android:screenOrientation="landscape"`

**MainActivity:**
- `android:launchMode="singleTask"`
- `android:screenOrientation="landscape"`
- `android:resizeableActivity="true"`
- `android:supportsPictureInPicture="false"`
- `android:exported="false"`
- `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"`

### 6. `app/src/main/java/de/codevoid/andronavibar/TrampolineActivity.kt`

```
onCreate():
  if (MainActivity.isRunning)
    -> startActivity(MainActivity intent with ACTION_QUIT + FLAG_ACTIVITY_NEW_TASK)
  else
    -> startActivity(MainActivity intent with FLAG_ACTIVITY_LAUNCH_ADJACENT
         | FLAG_ACTIVITY_NEW_TASK, ActivityOptions with launch bounds)
  finish()
```

### 7. `app/src/main/res/layout/activity_main.xml`

Landscape layout structure matching the sketch:
```
ConstraintLayout (match_parent x match_parent, dark bg)
├── LinearLayout (horizontal, top bar)
│   ├── Button "Tab1"
│   ├── Button "Tab2"
│   ├── Button "Tab3"
│   ├── Button "Tab4"
│   └── Button "Tab5"
└── GridLayout or nested LinearLayouts (content area)
    ├── CardView "Panel 1" (placeholder)
    ├── CardView "Panel 2" (placeholder)
    ├── CardView "Panel 3" (placeholder)
    └── CardView "Panel 4" (placeholder)
```

### 8. `app/src/main/java/de/codevoid/andronavibar/MainActivity.kt`

```
companion object { var isRunning = false }

onCreate(savedInstanceState):
  isRunning = true
  setContentView(R.layout.activity_main)
  handleIntent(intent)

onNewIntent(intent):
  handleIntent(intent)

handleIntent(intent):
  if (intent.action == ACTION_QUIT) finishAndRemoveTask()

onDestroy():
  isRunning = false

onMultiWindowModeChanged(inMultiWindow, config):
  // If user manually exits split-screen, finish the app
  if (!inMultiWindow) finishAndRemoveTask()
```

---

## Implementation Order

1. Modify `app/build.gradle.kts` (minSdk, deps, signing)
2. Create `res/values/strings.xml`, `themes.xml`, `colors.xml`
3. Create `AndroidManifest.xml`
4. Create `TrampolineActivity.kt`
5. Create `res/layout/activity_main.xml`
6. Create `MainActivity.kt`
7. Build and verify with `./gradlew assembleDebug`
8. Commit and push

---

## Edge Cases & Notes

- **FLAG_ACTIVITY_LAUNCH_ADJACENT on stock Android 14+**: On API 32+, this flag
  triggers split-screen even when the device is currently in fullscreen mode.
  A `setLaunchBounds()` hint covering the right half of the screen ensures
  the system enters multi-window mode even on devices where the flags alone
  are not sufficient. The previous foreground app stays on one side; the new
  activity appears on the other. This is the standard public API approach -
  no root, Shizuku, or AccessibilityService needed.

- **Split-screen exit handling**: When the user drags the divider to dismiss
  split-screen, `onMultiWindowModeChanged(false)` fires. We call
  `finishAndRemoveTask()` so the app cleanly exits.

- **Process death**: The static `isRunning` flag resets to `false` if the
  process dies, which is correct - a dead process means the activity isn't
  running.

- **No backwards compat**: minSdk 34 means all devices support the 12L+
  split-screen APIs. No fallback code needed.
