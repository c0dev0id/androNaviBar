# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug    # build debug APK
./gradlew assembleRelease  # build signed release APK (requires env vars below)
./gradlew lint             # run lint checks
```

There are no unit tests. Lint is the only automated code-quality check.

### Release signing

`assembleRelease` reads signing config from environment variables:
`SIGNING_KEYSTORE_PATH`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`

CI (GitHub Actions) provides these via repository secrets. Locally, export them before running `assembleRelease` or use a debug build instead.

## Architecture

**aR2Launcher** is an Android home launcher (`android.intent.category.HOME`) for automotive head units. It presents a fullscreen landscape dashboard: a dynamic-count vertical column of configurable buttons on the right third, and a content pane (`reservedArea`) on the left two-thirds.

See **`ARCHITECTURE.md`** for full design documentation including the focus/input model, pane lifecycle, button type catalogue, persistence schema, and visual state details.

### Source files

All Kotlin lives under `app/src/main/java/de/codevoid/andronavibar/`:

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Single Activity; focus management, remote key routing, edit mode, pane coordination, drag-to-reorder, widget binding flow |
| `LauncherApplication.kt` | Application subclass; persistent Round Button 2 hold receiver, weak ref to MainActivity |
| `Models.kt` | `ButtonConfig` sealed class, `UrlIcon` sealed class, `AppEntry` data class |
| `LauncherDatabase.kt` | SQLite persistence; buttons, settings, collection_items tables |
| `PaneContent.kt` | Interface: `load` / `show` / `hide` / `refresh` / `handleKey` / `unload` |
| `Extensions.kt` | `dpToPx`, `MATCH`/`WRAP` constants, `renderEmojiDrawable` |
| `UpdateChecker.kt` | Self-update download/install flow |
| `SafeAppWidgetHost.kt` | Crash-resistant `AppWidgetHost` wrapper |
| `DashboardPaneContent.kt` | Weather/location/rain dashboard (default left pane) |
| `WebPaneContent.kt` | WebView pane for URL buttons (non-browser mode) |
| `AppLauncherPaneContent.kt` | App browser/launcher list |
| `UrlLauncherPaneContent.kt` | URL confirmation pane (browser mode) |
| `WidgetPaneContent.kt` | `AppWidgetHostView` pane; cached indefinitely after first inflate |
| `AppsGridPaneContent.kt` | 4-column installed-apps grid with d-pad navigation |
| `MusicPlayerPaneContent.kt` | MediaBrowser-based music controls (art, title, prev/play/next/shuffle) |
| `BookmarksPaneContent.kt` | Scrollable URL bookmark list for BookmarkCollection buttons |
| `NavTargetsPaneContent.kt` | Scrollable navigation target list for NavTargetCollection buttons |
| `ButtonConfigPaneContent.kt` | Per-button config form shown in edit mode (type-specific fields) |
| `TypePickerPaneContent.kt` | Type selection cards shown when "+ Add" is tapped in edit mode |
| `GlobalSettingsPaneContent.kt` | Global settings pane (update checker) |
| `ui/FocusableButton.kt` | Base button: focus ring, activation flash, `barW`, `drawPath` |
| `ui/LauncherButton.kt` | Full launcher button: `ButtonConfig`, edit overlays, activation callbacks |

### Key design decisions

- **`singleTask` launch mode** — relaunches route through `onNewIntent()`, required for home launchers.
- **Landscape-only, `stateNotNeeded=true`** — fixed orientation; no saved instance state.
- **`PaneContent` interface** — decouples button types from pane content. `load()` is async (non-blocking); `show()` is only called after `onReady` fires. `hide()`/`show()` enable caching without unloading. Panes are load-on-activation, unload-on-replacement.
- **Two-tap touch model** — first tap on an unfocused button focuses it only (no activation, no ripple); second tap activates. Physical remote is the primary input path.
- **Focus owner** — two contexts: button-row focus (`focusedIndex` int) and pane focus. When a toggle-type button activates its pane, focus transfers into the pane; button row stays visible.
- **Remote input** — `BroadcastReceiver` on action `com.thorkracing.wireddevices.keypress`. Key 19/20 = DPAD up/down, 66 = activate (Round Button 1), 111 = Round Button 2. Auto-repeat suppressed via `pressedKeys: MutableSet<Int>`, cleared on `onPause()`. Remote fully suppressed in edit mode.
- **SQLite persistence** — `LauncherDatabase` (singleton `SQLiteOpenHelper`); `buttons` table (dynamic count, position-keyed), `settings` table (key/value), `collection_items` table (bookmark/navtarget items keyed by button position).
- **Edit mode** — touch-only; entered via long-press or dashboard gear. Chrome bar shows "+ Add" / "Done" / "⚙ Settings". Type is immutable after creation (delete + re-add to change). Drag handle → drag-to-reorder; delete zone → instant delete.
- **Material Design 3 dark theme** — `#1A1A1A` background, `#F57C00` orange accent; optimized for automotive displays. minSdk 34 (Android 14), no backwards-compatibility concerns.

### Button types (sealed class `ButtonConfig`)

| Type | Activation | Pane |
|---|---|---|
| `Empty` | no-op | none |
| `AppLauncher` | `startActivity()` via package manager | none |
| `UrlLauncher` | WebView or `Intent.ACTION_VIEW` (prepends `https://` if no scheme) | `WebPaneContent` or `UrlLauncherPaneContent` |
| `WidgetLauncher` | Inflates `AppWidgetHostView` | `WidgetPaneContent` |
| `MusicPlayer` | Binds `MediaBrowserService` | `MusicPlayerPaneContent` |
| `BookmarkCollection` | Shows bookmark list | `BookmarksPaneContent` |
| `NavTargetCollection` | Shows nav target list | `NavTargetsPaneContent` |

### CI/CD

Two GitHub Actions workflows in `.github/workflows/`:

- **`build.yml`** — push to `main` or PRs labeled `run-build`: lint → assembleDebug → zipalign + sign → draft pre-release (`dev` tag), APK named `aR2Launcher-dev-{SHORT_SHA}.apk`.
- **`release.yml`** — manual `workflow_dispatch`: auto-increments patch version from git tags → assembleRelease → zipalign + sign → git tag + GitHub release draft, APK named `aR2Launcher-{VERSION}.apk`.

Both workflows use Temurin JDK 17 and sign with `apksigner` (zipalign first).
