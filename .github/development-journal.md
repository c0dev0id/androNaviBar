# Development Journal

## 2026-03-02 - Phase 1: Split-Screen Toggle & Basic UI Scaffold

### Goal
Establish the foundation for androNaviBar, a companion app for Android
navigation systems that launches in split-screen mode alongside the currently
active app and toggles off when tapped again.

### Decisions
- **TrampolineActivity pattern**: Chose a transparent launcher entry point
  that immediately finishes itself. This allows using
  `FLAG_ACTIVITY_LAUNCH_ADJACENT | FLAG_ACTIVITY_NEW_TASK` to trigger
  split-screen on Android 14+ (API 32+ behavior) without the user seeing
  an intermediate activity.
- **Static `isRunning` flag** for toggle detection: Simple and correct -
  resets naturally on process death. No need for `ActivityManager` queries
  or persistent state.
- **`singleTask` launch mode** on `MainActivity`: Ensures single instance
  and routes re-launch intents through `onNewIntent()`.
- **Stock Android approach**: Target device is non-rooted, so split-screen
  relies entirely on public APIs (`FLAG_ACTIVITY_LAUNCH_ADJACENT`). No
  shell commands, Shizuku, or AccessibilityService needed.
- **XML Views over Compose**: Simpler for a fixed dashboard layout on a
  head unit. Can revisit later if needed.
- **Dark theme**: Suited for car/head-unit use to reduce glare.
- **minSdk 34**: Android 14+ only, no backwards-compatibility code paths.

### Changes
- Modified `app/build.gradle.kts`: minSdk 26 -> 34, added AndroidX/Material
  dependencies, added `signingConfigs` block for CI release signing.
- Created `AndroidManifest.xml` with TrampolineActivity (launcher,
  transparent, excludeFromRecents) and MainActivity (singleTask, landscape,
  resizeableActivity).
- Created `TrampolineActivity.kt`: toggle logic + split-screen launch with
  `ActivityOptions.setLaunchBounds()` hinting at right-half positioning.
- Created `MainActivity.kt`: lifecycle toggle handling, auto-exit on
  split-screen dismiss via `onMultiWindowModeChanged()`.
- Created `activity_main.xml`: top tab bar (5 buttons) + 2x2 CardView
  panel grid, all placeholder content.
- Created resource files: dark color palette, NoActionBar + transparent
  themes, string resources.

### Open Items
- Build not verified locally (no Android SDK in CI environment) - relies
  on GitHub Actions CI.
- `FLAG_ACTIVITY_LAUNCH_ADJACENT` split-screen entry needs on-device
  testing. Fallback approaches (AccessibilityService, Shizuku) can be
  added if it doesn't trigger reliably.
- UI panels are placeholders - real content comes in subsequent phases.
- Split-screen ratio defaults to 50/50; may need adjustment for a
  sidebar-style layout.
