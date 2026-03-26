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
9. [Edit Mode](#9-edit-mode)
10. [Persistence](#10-persistence)
11. [File Structure](#11-file-structure)

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
| Permissions | `INTERNET` (for URL launching and update checks) |

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
implementations attach their views here. It is populated entirely at runtime.

The right pane contains a `ScrollView` wrapping `buttonPanel` (vertical `LinearLayout`), plus
an `editChromeContainer` (horizontal bar with **+ Add**, **Done**, **Settings** buttons) visible
only in edit mode. Button heights are computed dynamically by `adjustButtonHeights()` to fill
the available space, up to a maximum of 10.

### Color Palette

| Token | Color | Usage |
|---|---|---|
| `surface_dark` | `#2B2B2B` | Root background, button pane background |
| `background_dark` | `#1A1A1A` | Status bar, navigation bar |
| `colorPrimary` / `colorAccent` | `#F57C00` | Orange accent: focus ring, flash, config border |
| `colorPrimaryDark` | `#E65100` | Darker orange (dark theme primary variant) |
| `button_inactive` | `#444444` | Default button background tint |
| `button_active` | `#F57C00` | Button background tint during activation flash |
| `surface_card` | `#3C3C3C` | Alert dialog backgrounds, type picker cards |
| `text_primary` | `#FFFFFF` | Button labels, dialog text |
| `text_secondary` | `#B0B0B0` | Secondary labels |

### Button Styles

Two named styles exist in `themes.xml`:

- `Widget.AndroNaviBar.Button` — normal resting style: `#444444` background tint,
  16dp corner radius, 32dp icon at `textStart`, white text.
- `Widget.AndroNaviBar.Button.Config` — config-mode style: outlined variant with a 2dp
  orange stroke, same corners and icon settings.

The focus ring is not a style — it is a `GradientDrawable` created in code and set as
the button's `foreground`.

---

## 3. Component Overview

```
MainActivity
├── manages: focusedIndex, editMode, focusOwner
├── owns: remoteListener (BroadcastReceiver)
├── drives: LauncherButton × N  (dynamic count, DB-driven)
├── routes: all pane load/show/unload/hide
│
LauncherButton  (custom View, extends FocusableButton extends MaterialButton)
├── owns: ButtonConfig (sealed class: type + type-specific data)
├── fires: activation callbacks → MainActivity
└── draws: focus ring, activation flash, edit overlay (drag handle + delete zone)
    │
    PaneContent (interface)
    ├── load(onReady)   — async, non-blocking
    ├── show(container) — attach to left pane
    ├── hide()          — make invisible without detaching (default: no-op)
    ├── refresh()       — reload data (default: no-op)
    ├── handleKey(key)  — remote key routing (default: false)
    └── unload()        — detach and release
```

`LauncherButton` fires typed callbacks (e.g. `onAppLauncherActivated`, `onWidgetActivated`).
`MainActivity` receives them and creates/shows the appropriate `PaneContent`. The button itself
does not own or manage pane instances.

---

## 4. LauncherButton

`LauncherButton` is a custom `View` that extends `FocusableButton` (which extends
`MaterialButton`). Each button instance knows its own type and persists/loads its config.

### ButtonConfig

`ButtonConfig` is a sealed class holding the button's type and type-specific data:

```kotlin
sealed class ButtonConfig {
    object Empty : ButtonConfig()
    data class AppLauncher(val packageName: String, val label: String) : ButtonConfig()
    data class UrlLauncher(val url: String, val label: String,
                           val icon: UrlIcon, val openInBrowser: Boolean) : ButtonConfig()
    data class WidgetLauncher(val provider: ComponentName, val appWidgetId: Int,
                              val label: String, val icon: UrlIcon) : ButtonConfig()
    data class MusicPlayer(val playerPackage: String, val label: String,
                           val icon: UrlIcon) : ButtonConfig()
    data class BookmarkCollection(val label: String, val icon: UrlIcon) : ButtonConfig()
    data class NavTargetCollection(val label: String, val icon: UrlIcon,
                                   val appPackage: String) : ButtonConfig()
}
```

`ButtonConfig` is a pure data container — no logic. Icon is represented by `UrlIcon`
(sealed: `None`, `CustomFile`, `Emoji`).

### Visual State

| Condition | Visual effect |
|---|---|
| `isFocused` | Orange `GradientDrawable` foreground (6dp stroke, 16dp corner) |
| `isActivated` | 150ms background tint flash to `#F57C00`, then back |
| `isEditMode` | Drag-handle overlay (left) + delete zone overlay (right) drawn in `onDrawContent` |

### Touch Logic (normal mode)

A single physical tap on an unfocused button focuses it and does nothing else. A second
tap activates it. `ACTION_DOWN` on an unfocused button is consumed without ripple to avoid
accidental activations.

### Edit Mode Overlays

In edit mode, each button draws two touch zones over its content:

- **Drag handle** (left, over the icon slot): long-press initiates system drag-and-drop reorder.
- **Delete zone** (right edge, red): tap fires `onDeleteTapped`.

All other taps in edit mode route to `onEditTapped` (opens the button's config pane).

---

## 5. PaneContent Interface

`PaneContent` is the contract between `MainActivity` and the left pane. Each button type that
shows something in the left pane provides an implementation.

```kotlin
interface PaneContent {
    fun load(onReady: () -> Unit)  // async; must not block main thread
    fun show(container: ViewGroup) // attach prepared view
    fun hide()          {}         // make invisible; default no-op
    fun refresh()       {}         // reload data; default no-op
    fun handleKey(keyCode: Int): Boolean = false  // remote key routing
    fun unload()                   // detach and release all resources
}
```

The `hide()`/`show()` pair enables pane caching: a pane can become invisible when another takes
over `reservedArea`, then become visible again without a full `load()` cycle. Widget panes use
this — they are never unloaded once created.

`handleKey()` lets panes consume remote key events when `focusOwner == PANE`. Returning `false`
falls through to `MainActivity`'s default key handling.

---

## 6. Pane Loading Lifecycle

The loading model is **load-on-activation, unload-on-replacement**. Pane content is only
triggered when the user explicitly activates a toggle-type button, not on focus change.

```
Activate B → B.load() starts
             │
             ▼
B.load completes → B.show(), focus transfers to pane
             │
             ▼
Activate C → B.unload(), C.load() starts
             │
             ▼
C.load completes → C.show(), focus transfers to pane
```

A loading spinner overlay is shown while `load()` is in progress, hidden when `onReady` fires.

Widget panes are an exception: they are never unloaded once created. A `widgetPanes` map
in `MainActivity` keyed by widget ID holds them. When a widget pane is not the active pane,
it is hidden (`View.GONE`) rather than unloaded. This avoids expensive re-inflation cycles.

---

## 7. Button Types

### Direct-Action Types

Fire immediately on activation; show nothing in the left pane.

**AppLauncher**
- Activation: `packageManager.getLaunchIntentForPackage()` → `startActivity()`.
- Icon: `packageManager.getApplicationIcon()`.

**UrlLauncher** (two modes)
- Browser mode (`openInBrowser = true`): fires `Intent.ACTION_VIEW`; shows `UrlLauncherPaneContent`
  (a confirmation/info pane) in `reservedArea`.
- WebView mode: loads the URL in an internal `WebPaneContent`.
- Prepends `https://` if no scheme is present.

**Empty**
- Activation: no-op.
- Shows `[Empty]` label.

### Toggle/Pane Types

Load and display content in the left pane. Activation triggers `load()`; once `onReady` fires,
focus transfers from the button row into the pane.

**WidgetLauncher**
- Activation: inflates `AppWidgetHostView` via `SafeAppWidgetHost`.
- Pane: `WidgetPaneContent` — cached indefinitely, never unloaded after first inflate.
- Setup flow: user picks provider → system bind dialog → `onActivityResult` → widget bound.

**MusicPlayer**
- Activation: binds `MediaBrowserService` for the configured player package.
- Pane: `MusicPlayerPaneContent` — cover art, title, artist, prev/play/next/shuffle controls.
- Remote keys routed via `handleKey()`: 19/20 = prev/next, 66 = play/pause.

**BookmarkCollection**
- Activation: shows `BookmarksPaneContent` — scrollable list of stored URL bookmarks.
- Each item tapped opens the URL in WebView or external browser per item's `openBrowser` flag.
- Items stored in `collection_items` table keyed by `button_position`.

**NavTargetCollection**
- Activation: shows `NavTargetsPaneContent` — scrollable list of navigation targets.
- Each item fires `Intent.ACTION_VIEW` with the item's URI, package-restricted to the
  configured app (`appPackage`). Falls back to unqualified intent if the app can't handle it.
- Items stored in `collection_items` table keyed by `button_position`.

---

## 8. Focus and Input Model

### Focus State

Two focus contexts exist in `MainActivity`:

1. **Button row focus** — `focusedIndex` (Int) tracks which button has the orange focus ring.
   Persisted to the DB (`settings` table, key `focused_index`) on every change.

2. **Pane focus** — when a toggle-type button activates and its pane is ready, `focusOwner`
   transitions to `FocusOwner.PANE`. The button row stays visible; remote keys route to
   `keyPane.handleKey()` first.

`setFocusOwner(FocusOwner.BUTTONS)` returns focus to the button row; `setFocusOwner(FocusOwner.PANE)`
moves it into the pane.

### Remote Input

The remote communicates via broadcast intent on action `com.thorkracing.wireddevices.keypress`.
Auto-repeat is suppressed with `pressedKeys: MutableSet<Int>`, cleared on `onPause()`.

In edit mode, all remote input is suppressed (edit mode is touch-only).

**Button row focus:**

| Key | Name | Action |
|---|---|---|
| 19 | DPAD_UP | Move focus up (clamped) |
| 20 | DPAD_DOWN | Move focus down (clamped) |
| 66 | Round Button 1 | Activate focused button |
| 21 | DPAD_LEFT | Enter pane focus (if pane active) |
| 111 | Round Button 2 | No-op (launcher never exits) |

**Pane focus:**

Keys are forwarded to `keyPane.handleKey()` first. If it returns `false`, `MainActivity`
handles them (typically 111/Round Button 2 dismisses the active pane).

### Touch Input

| Scenario | Result |
|---|---|
| Tap unfocused button | Focus moves; `ACTION_DOWN` consumed (no ripple) |
| Tap focused direct-action button | Activation fires |
| Tap focused toggle button | Pane load starts |
| Edit mode: tap drag handle | Long-press starts drag-and-drop reorder |
| Edit mode: tap delete zone | Button deleted |
| Edit mode: tap button center | Button config pane opens |

---

## 9. Edit Mode

Edit mode is touch-only. All remote key input is suppressed while edit mode is active.

### Entry and Exit

- Entry: tap the **≡** / hamburger icon on the dashboard pane, or long-press any button.
- Exit: tap **Done** in the edit chrome.

### Edit Chrome

A horizontal bar appears above the button scroll when edit mode is active:

```
[ + Add ]  [ Done ]  [ ⚙ Settings ]
```

- **+ Add**: shows `TypePickerPaneContent` in `reservedArea` — user picks a type, then the
  new button is created and its config pane opens immediately.
- **Done**: exits edit mode, hides chrome, dismisses any active config pane.
- **⚙ Settings**: shows `GlobalSettingsPaneContent` (currently: update checker).

### Per-Button Editing

Tapping a button's center in edit mode opens `ButtonConfigPaneContent` in `reservedArea`.
The config pane is type-specific:

- **App**: app picker + label/icon fields.
- **URL**: URL text input + open-in-browser toggle + label/icon fields.
- **Widget**: widget picker (shows system bind dialog); label/icon fields.
- **Music**: music player app picker + label/icon fields.
- **Bookmark**: list of collection items (add/edit/delete inline via `AlertDialog`) + label/icon.
- **NavTarget**: app picker + list of URI targets (add/edit/delete inline) + label/icon.

Config changes to the button row (label, icon) are staged in `pendingRow` and committed on
**Save**. Collection items (bookmarks, nav targets) are persisted to the DB immediately on
each add/edit/delete.

### Drag-to-Reorder

Long-press on a button's drag handle starts Android system drag-and-drop. Each button is a
drop target. On drop, `db.moveButton(from, to)` reorders both the `buttons` table and the
`collection_items` table atomically.

### Type Immutability

A button's type cannot be changed after creation. To change type, delete the button and add
a new one. This keeps `ButtonConfigPaneContent` simple — it always knows the type it is editing.

---

## 10. Persistence

All state lives in a single SQLite database (`launcher.db`) managed by `LauncherDatabase`
(singleton, `SQLiteOpenHelper`, DB version 2).

### Schema

**`buttons` table**

| Column | Type | Description |
|---|---|---|
| `position` | INTEGER PK | Button index (0-based, contiguous) |
| `type` | TEXT | `"app"`, `"url"`, `"widget"`, `"music"`, `"bookmark"`, `"navtarget"`, or NULL (empty) |
| `value` | TEXT | Package name, URL, widget provider CN, etc. (type-specific) |
| `label` | TEXT | Display label |
| `widget_id` | INTEGER | Bound `AppWidgetId` (widget type only) |
| `open_browser` | INTEGER | 0/1 (URL type only) |
| `icon_type` | TEXT | `"custom"`, `"emoji"`, or NULL |
| `icon_data` | TEXT | Emoji char, or NULL for custom/none |

**`settings` table**

| Key | Description |
|---|---|
| `focused_index` | Last focused button index |
| `pending_widget_id` | Widget ID reserved during bind flow |
| `apps_filter_on` | Whether app list filter is active |
| `apps_hidden_pkgs` | Comma-separated hidden package names |

**`collection_items` table**

| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT | Row identity |
| `button_position` | INTEGER | FK → `buttons.position` |
| `sort_order` | INTEGER | Display order within the collection |
| `label` | TEXT | Item label |
| `uri` | TEXT | URL (bookmarks) or navigation URI (nav targets) |
| `open_browser` | INTEGER | 0/1 (bookmark type only) |

### Structural Integrity

`removeButton()` and `moveButton()` operate in transactions and mirror all position changes
to `collection_items`, maintaining referential consistency without a foreign key constraint.
`moveButton()` uses a temporary position of `-1` as a parking slot to avoid uniqueness
conflicts during the shift.

### Icons

Custom image icons are stored as JPEG files at `context.filesDir/btn_{index}.jpg`. The DB
stores `icon_type = "custom"` with no `icon_data`. `cleanStaleIconFile()` in `LauncherButton`
deletes the file when the icon is changed to non-file types.

### Migration

On first run after the SharedPreferences era, `migrateFromPrefsIfNeeded()` copies all data
from the `"button_config"` SharedPreferences file into the DB and clears the prefs. The
`_migrated` settings key prevents re-migration.

---

## 11. File Structure

```
app/src/main/
├── AndroidManifest.xml
├── java/de/codevoid/andronavibar/
│   ├── MainActivity.kt              — Activity; focus, remote routing, edit mode, pane coordination
│   ├── LauncherApplication.kt       — Application subclass; RB2 hold receiver, weak MainActivity ref
│   ├── Models.kt                    — ButtonConfig, UrlIcon, AppEntry sealed/data classes
│   ├── LauncherDatabase.kt          — SQLiteOpenHelper; buttons, settings, collection_items tables
│   ├── PaneContent.kt               — PaneContent interface
│   ├── Extensions.kt                — dpToPx, MATCH/WRAP constants, renderEmojiDrawable
│   ├── UpdateChecker.kt             — Self-update download/install flow
│   ├── SafeAppWidgetHost.kt         — Crash-resistant AppWidgetHost wrapper
│   │
│   ├── — Runtime panes (shown when a button is activated) —
│   ├── DashboardPaneContent.kt      — Weather/location/rain dashboard (default pane)
│   ├── WebPaneContent.kt            — WebView for URL buttons (non-browser mode)
│   ├── AppLauncherPaneContent.kt    — App browser/launcher list
│   ├── UrlLauncherPaneContent.kt    — URL confirmation pane (browser mode)
│   ├── WidgetPaneContent.kt         — AppWidgetHostView pane; never unloaded after first inflate
│   ├── AppsGridPaneContent.kt       — 4-column installed-apps grid with d-pad navigation
│   ├── MusicPlayerPaneContent.kt    — MediaBrowser music controls (art, title, prev/play/next)
│   ├── BookmarksPaneContent.kt      — Scrollable URL bookmark list
│   ├── NavTargetsPaneContent.kt     — Scrollable navigation target list
│   │
│   ├── — Edit mode panes (shown in reservedArea during edit) —
│   ├── ButtonConfigPaneContent.kt   — Per-button config form (type-specific fields)
│   ├── TypePickerPaneContent.kt     — Type selection cards (shown when "+ Add" is tapped)
│   └── GlobalSettingsPaneContent.kt — Global settings (update checker)
│
│   └── ui/
│       ├── FocusableButton.kt       — Base button: focus ring, activation flash, barW, drawPath
│       └── LauncherButton.kt        — Full launcher button: ButtonConfig, edit overlays, callbacks
│
└── res/
    ├── layout/
    │   └── activity_main.xml        — Two-pane root layout
    └── values/
        ├── colors.xml               — Full color palette
        ├── dimens.xml               — Dimensional tokens
        ├── strings.xml              — All user-visible strings
        └── themes.xml               — App theme, button styles, dialog theme overlay
```
