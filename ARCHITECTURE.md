# aR2Launcher — Architecture

aR2Launcher is an Android home launcher (`android.intent.category.HOME`) for automotive head
units. It presents a fullscreen, landscape-only dashboard with a vertical column of configurable
buttons on the right and a content pane on the left. It is designed around a physical remote
controller as the primary input device, with touch as a secondary path.

---

## Table of Contents

1. [App Identity and Constraints](#1-app-identity-and-constraints)
2. [Layout and Visual Design](#2-layout-and-visual-design)
3. [Component Overview](#3-component-overview)
4. [LauncherButton](#4-launcherbutton)
5. [PaneContent Interface](#5-panecontent-interface)
6. [Pane Loading Lifecycle](#6-pane-loading-lifecycle)
7. [Button Types](#7-button-types)
8. [Focus and Input Model](#8-focus-and-input-model)
9. [Configuration Mode](#9-configuration-mode)
10. [Persistence](#10-persistence)
11. [File Structure](#11-file-structure)
12. [Evolution Notes](#12-evolution-notes)

---

## 1. App Identity and Constraints

| Property | Value |
|---|---|
| Package | `de.codevoid.andronavibar` |
| minSdk | 34 (Android 14) |
| targetSdk | 35 |
| Activity | `MainActivity` — single activity, no fragments |
| Launch mode | `singleTask` |
| Orientation | Landscape only (`android:screenOrientation="landscape"`) |
| State | `android:stateNotNeeded="true"` |
| Permissions | `INTERNET` (for URL launching) |

`singleTask` is required because Android routes relaunches of home activities through
`onNewIntent()` rather than creating new instances. `stateNotNeeded=true` reflects that the
activity is always visible and does not need saved instance state.

No backwards-compatibility shims are needed anywhere. Android 14 APIs are used directly.

---

## 2. Layout and Visual Design

### Two-Pane Layout

The root view is a horizontal `LinearLayout` split with weights:

```
┌───────────────────────────────────┬───────────────┐
│                                   │   [ Button 0 ] │
│         Left Pane (2/3)           │   [ Button 1 ] │
│         id: reservedArea          │   [ Button 2 ] │
│         FrameLayout               │   [ Button 3 ] │
│                                   │   [ Button 4 ] │
└───────────────────────────────────┴───────────────┘
                                     Right (1/3)
                                     id: buttonPanel
```

The left pane (`reservedArea`) is a `FrameLayout` used as a dynamic container. `PaneContent`
implementations attach their views here. It is currently empty in the layout file and populated
entirely at runtime.

The right pane (`buttonPanel`) is a vertical `LinearLayout` with 4dp padding. Each button has
`layout_weight="1"` and 4dp margin, so all five buttons fill the pane with equal height.

### Color Palette

| Token | Color | Usage |
|---|---|---|
| `surface_dark` | `#2B2B2B` | Root background, button pane background |
| `background_dark` | `#1A1A1A` | Status bar, navigation bar |
| `colorPrimary` / `colorAccent` | `#F57C00` | Orange accent: focus ring, flash, config border |
| `colorPrimaryDark` | `#E65100` | Darker orange (dark theme primary variant) |
| `button_inactive` | `#444444` | Default button background tint |
| `button_active` | `#F57C00` | Button background tint during activation flash |
| `surface_card` | `#3C3C3C` | Alert dialog backgrounds |
| `text_primary` | `#FFFFFF` | Button labels, dialog text |
| `text_secondary` | `#B0B0B0` | Secondary labels (available for pane content) |

### Button Styles

Two named styles exist in `themes.xml`:

- `Widget.AndroNaviBar.Button` — the normal resting style: `#444444` background tint,
  16dp corner radius, 32dp icon at `textStart`, white text.
- `Widget.AndroNaviBar.Button.Config` — the config-mode style: outlined variant with a 2dp
  orange stroke, same corners and icon settings.

The focus ring is not a style — it is a `GradientDrawable` created in code and set as the
button's `foreground`. See [LauncherButton](#4-launcherbutton).

### Theme

`Theme.AndroNaviBar` extends `Theme.Material3.Dark.NoActionBar`. The dialog overlay
`ThemeOverlay.AndroNaviBar.AlertDialog` sets `surface_card` as the dialog background.

---

## 3. Component Overview

```
MainActivity
├── manages: focusedIndex, configMode
├── owns: remoteListener (BroadcastReceiver)
├── drives: LauncherButton × N
│
LauncherButton  (custom View, extends MaterialButton)
├── owns: ButtonConfig (type + type-specific data)
├── owns: PaneContent instance (nullable)
├── owns: CoroutineScope (for async pane loading)
└── renders: focus ring, activation flash, config stroke
    │
    PaneContent (interface)
    ├── load(onReady)   — async, non-blocking
    ├── show(container) — attach to left pane
    └── unload()        — detach and release
```

---

## 4. LauncherButton

`LauncherButton` is a custom `View` that extends `MaterialButton`. Each button instance is fully
self-contained: it knows its own type, holds its own `PaneContent`, and manages its own async
loading scope.

### Configuration Ownership

Each button owns a `ButtonConfig` sealed class instance:

```kotlin
sealed class ButtonConfig {
    object Empty : ButtonConfig()

    data class AppLauncher(
        val packageName: String,
        val label: String
    ) : ButtonConfig()

    data class UrlLauncher(
        val url: String,
        val label: String
    ) : ButtonConfig()

    // Future types:
    // data class Widget(...) : ButtonConfig()
    // data class MusicPlayer(...) : ButtonConfig()
    // data class Metrics(...) : ButtonConfig()
}
```

`ButtonConfig` is a pure data container — no logic. The button uses it to construct its
`PaneContent` and to serialize/deserialize from `SharedPreferences`.

### Visual State

The button tracks three independent visual conditions:

| Condition | Visual effect |
|---|---|
| `isFocused` | Orange `GradientDrawable` foreground (6dp stroke, 16dp corner radius) |
| `isActivated` | 150ms background tint flash to `#F57C00`, then back to `#444444` |
| `configMode` | Orange 2dp outline stroke on all buttons simultaneously |

The focus ring is created as a `GradientDrawable` with `shape = RECTANGLE`,
`cornerRadius = 16dp`, `stroke(6dp, colorPrimary)`, `color = TRANSPARENT`, and assigned to
`button.foreground`. When focus moves away, `foreground` is set to `null`.

The activation flash uses `backgroundTintList` and a `Handler.postDelayed` of 150ms.

### Touch Logic

Unfocused buttons consume `ACTION_DOWN` without propagating it, which prevents the Material
ripple from firing on what is purely a focus-setting tap. The sequence is:

1. `ACTION_DOWN` arrives on an unfocused button → consumed; focus moves to this button.
2. Subsequent tap (click) on the now-focused button → activation fires normally with ripple.

This means a single physical tap on an unfocused button focuses it and does nothing else. A
second tap activates it. This is intentional: the physical remote is the primary activation
path; touch is supplementary.

### CoroutineScope

Each `LauncherButton` owns a `CoroutineScope` cancelled in `onDetachedFromWindow()`. All async
pane loading is launched within this scope, so loading automatically stops if the button is
removed from the hierarchy (e.g., during a full reconfiguration).

---

## 5. PaneContent Interface

`PaneContent` is the contract between a button and the left pane. Each button type that shows
something in the left pane provides an implementation.

```kotlin
interface PaneContent {
    /**
     * Begin any async work needed to prepare the view (network fetch, media session
     * binding, widget inflation, etc.). Must not block the main thread. Call [onReady]
     * when the view is ready to be displayed. [onReady] is called on the main thread.
     */
    fun load(onReady: () -> Unit)

    /**
     * Attach the prepared view to [container] (the left pane FrameLayout).
     * Only called after [onReady] has fired, and only when the button is still focused.
     */
    fun show(container: ViewGroup)

    /**
     * Detach from [container] and release any held resources (media sessions,
     * service connections, observers, etc.).
     */
    fun unload()
}
```

Types that perform a direct action (AppLauncher, UrlLauncher) return `null` for their
`PaneContent` — there is nothing to show in the pane.

Config UI for each button type also implements `PaneContent`, allowing the config editor to
reuse the same attach/detach protocol.

---

## 6. Pane Loading Lifecycle

The loading model is **load-on-focus, unload-on-unfocus**. It is non-blocking and avoids
mid-load cancellation complexity by letting loads always run to completion and checking focus
state at the end.

```
Focus → B: B not already loading → load() starts
        │
        ▼
Focus → C: B.unload(), C.load() starts. B's load still running.
        │
        ▼
Focus → B: B is already loading → do nothing, in-flight load continues.
        │
        ▼
B's load completes → isFocused = true → show()
C's load completes → isFocused = false → discard silently
```

Each button tracks an `isLoading` boolean. When focus arrives:
- `isLoading = false` → start a new load
- `isLoading = true` → do nothing; the in-flight load will call `show()` when done

Key properties of this model:

- **No mid-load interruption.** Loads always run to completion. No cooperative cancellation
  needed inside `PaneContent` implementations.
- **No double loads.** `isLoading` prevents starting a second load if focus returns while
  the first is still in progress.
- **No race conditions.** The `isFocused` check at completion time is sufficient because all
  callbacks are dispatched on the main thread.
- **Scope cancellation as safety net.** If the button is removed from the hierarchy entirely,
  the button's `CoroutineScope` is cancelled, stopping any in-flight coroutine-based loading.

This model is intentionally simple. A future refinement — retaining loaded content on unfocus
instead of always unloading — can be added as an opt-in flag per type:

```kotlin
interface PaneContent {
    val retainOnUnfocus: Boolean get() = false  // future hook
    // ...
}
```

---

## 7. Button Types

Every button type defines three things:

1. **Activation behavior** — what happens when the button is pressed.
2. **PaneContent** — what appears in the left pane when the button is focused (`null` for
   direct-action types).
3. **Config UI** — a `PaneContent` shown in the left pane when the button is activated in
   config mode.

### Current Types

**AppLauncher**
- Activation: calls `packageManager.getLaunchIntentForPackage(packageName)` and `startActivity()`.
- PaneContent: `null`.
- Icon: loaded from `packageManager.getApplicationIcon(packageName)`.
- Config UI: app picker list (queries `ACTION_MAIN` + `CATEGORY_LAUNCHER`, sorted by label).

**UrlLauncher**
- Activation: fires `Intent.ACTION_VIEW` with the stored URL. Automatically prepends `https://`
  if no scheme is present.
- PaneContent: `null`.
- Config UI: single-field text input for the URL.

**Empty**
- Activation: no-op.
- PaneContent: `null`.
- Config UI: the type-selection menu (Choose App / Enter URL / Clear).

### Planned Types

**Widget**
- Activation: transfers focus to the widget view in the left pane.
- PaneContent: inflates and hosts an Android `AppWidgetHostView`.
- Notes: likely sets `retainOnUnfocus = true` to keep the widget alive across focus changes.

**MusicPlayer**
- Activation: transfers focus to the now-playing view.
- PaneContent: binds to the active `MediaSession` via `MediaSessionManager`; displays track
  title, artist, cover art, and playback controls.
- Notes: cover art loading is async; the `load` callback fires when the session is bound and
  initial metadata is available.

**Metrics**
- Activation: transfers focus to the metrics view.
- PaneContent: displays system or GPS metrics (speed, altitude, heading, etc.).
- Data source TBD (GPS via `LocationManager`, OBD, or a custom broadcast).

---

## 8. Focus and Input Model

### Focus State

A single integer `focusedIndex` in `MainActivity` tracks which button is focused. It is
persisted to `SharedPreferences` under the key `focused_index` on every change, so the launcher
restores to the previously focused button after a restart.

Only one button is focused at a time. When a pane-type button is activated, focus management
within the pane is the pane's own responsibility.

### Touch Input

| Scenario | Result |
|---|---|
| Tap unfocused button | Focus moves to that button; `ACTION_DOWN` consumed (no ripple) |
| Tap focused button | Activation fires (ripple + flash + action) |
| Long-press any button | Toggles config mode |

### Remote Input (DMD Remote)

The DMD remote communicates via broadcast intent:

- **Action**: `com.thorkracing.wireddevices.keypress`
- **Key press extra**: `key_press` (int) — fired on key down
- **Key release extra**: `key_release` (int) — fired on key up

Auto-repeat is suppressed by tracking a `pressedKeys: MutableSet<Int>`. A key code arriving
while already in the set is ignored. The set is cleared on `onPause()`.

Current key mappings:

| Key code | Name | Action |
|---|---|---|
| 19 | DPAD_UP | Move focus up (clamped, no wrap) |
| 20 | DPAD_DOWN | Move focus down (clamped, no wrap) |
| 66 | ENTER / Round Button 1 | Activate focused button |

All remaining key codes are unmapped and reserved for future use (pane focus navigation,
page switching, back from pane).

### Back Key Behavior

`onBackPressed()` exits config mode if active. When not in config mode it does nothing —
correct for a home launcher, which must never be dismissed by back.

---

## 9. Configuration Mode

### Entry and Exit

Config mode is toggled by a long-press on any button. It is exited by pressing Back or by
long-pressing again.

### Visual Indicators

In config mode, every button receives a 2dp orange outline stroke via `MaterialButton.strokeWidth`
and `strokeColor`. The focus ring continues to be drawn normally on the focused button.

### Config Activation

In config mode, activating a button opens its config UI instead of performing its normal action.
The config UI is a `PaneContent` shown in the left pane using the same `load` → `show` protocol.

Currently, config UI is presented as a `MaterialAlertDialog`. As types grow more complex, pane-
based config UI will be used, splitting the left pane into a type-selector row (using the same
`LauncherButton` component and style) and a detail area for the selected type's options.

---

## 10. Persistence

All persistent state lives in a single `SharedPreferences` file named `"button_config"`.

### Button Configuration Keys

For each button index `i` (0–N):

| Key | Type | Value |
|---|---|---|
| `btn_{i}_type` | String | `"app"`, `"url"`, etc. (absent = empty) |
| `btn_{i}_value` | String | Package name, URL, or type-specific value |
| `btn_{i}_label` | String | Human-readable label displayed on button |

### Focus Key

| Key | Type | Value |
|---|---|---|
| `focused_index` | Int | Index of focused button, default 0 |

### Durability

Survives restarts, reboots, and APK updates (same package + signing key). Does not survive
uninstall or clear-data.

---

## 11. File Structure

```
app/src/main/
├── AndroidManifest.xml
├── java/de/codevoid/andronavibar/
│   ├── MainActivity.kt          — Activity; focus management, remote receiver, button wiring
│   ├── LauncherButton.kt        — (planned) Custom MaterialButton subclass
│   └── PaneContent.kt           — (planned) PaneContent interface + per-type implementations
└── res/
    ├── layout/
    │   └── activity_main.xml    — Two-pane root layout
    └── values/
        ├── colors.xml           — Full color palette
        ├── dimens.xml           — Dimensional tokens
        ├── strings.xml          — All user-visible strings
        └── themes.xml           — App theme, button styles, dialog theme overlay
```

### Current vs. Planned

`LauncherButton.kt` and `PaneContent.kt` are planned extractions. Currently, all logic resides
in `MainActivity.kt`. The extraction into `LauncherButton` is the primary pending refactor.

---

## 12. Evolution Notes

### Button List Pagination

The button list is currently five fixed items in XML. The design goal is a scrollable or pageable
list. Because buttons are self-contained, the pagination mechanism does not require changes to
button or pane logic. `MainActivity` only needs to manage which page/offset is active and
translate remote UP/DOWN into either within-page focus movement or a page switch at the boundary.

### Pane Focus

When a pane-type button is activated, focus moves into the left pane. The button row remains
visible with the active button indicated. A designated back key returns focus to the button row.
Pane-internal focus routing is per-type and will be defined when the first pane type is
implemented.

### Reserved Remote Keys

Unassigned key codes are reserved for:
- Left/right navigation into and out of the left pane
- Page up/down for the button list
- A dedicated back key to return focus from pane to button row

### Dependency Notes

`constraintlayout` and `cardview` are present as dependencies but unused in any layout — they
are candidates for removal. `kotlinx-coroutines-android` must be added to `build.gradle.kts`
when `LauncherButton` is implemented with its `CoroutineScope`.
