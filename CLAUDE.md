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

**aR2Launcher** is an Android home launcher (`android.intent.category.HOME`) for automotive head units. It presents a fullscreen landscape dashboard: a vertical column of 6 configurable buttons on the right third, and a dynamic content pane (`reservedArea`) on the left two-thirds.

See **`ARCHITECTURE.md`** for full design documentation including the focus/input model, pane lifecycle, button type catalogue, persistence schema, and visual state details.

### Source files

All Kotlin lives under `app/src/main/java/de/codevoid/andronavibar/`:

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Single Activity; focus management, remote key routing, drag-to-reorder, widget binding flow |
| `LauncherApplication.kt` | Application subclass; persistent Round Button 2 hold receiver, weak ref to MainActivity |
| `LauncherButton.kt` | Custom `MaterialButton`; `ButtonConfig` sealed class, activation, icon loading, visual state |
| `PaneContent.kt` | Interface: `load(onReady)` / `show(container)` / `unload()` |
| `ConfigPaneContent.kt` | Config UI pane (tabbed: App / URL / Widget / AppsGrid / MusicPlayer) |
| `WebPaneContent.kt` | WebView pane for URL-type buttons |
| `WidgetPaneContent.kt` | `AppWidgetHostView` pane; triggers provider refresh before first inflate |
| `AppsGridPaneContent.kt` | 4-column installed-apps grid with d-pad navigation |
| `MusicPlayerPaneContent.kt` | MediaBrowser-based music controls (art, title, prev/play/next/shuffle) |
| `SafeAppWidgetHost.kt` | Crash-resistant `AppWidgetHost` wrapper; catches stale content:// URI exceptions |

### Key design decisions

- **`singleTask` launch mode** — relaunches route through `onNewIntent()`, required for home launchers.
- **Landscape-only, `stateNotNeeded=true`** — fixed orientation; no saved instance state.
- **`PaneContent` interface** — decouples button types from pane content. `load()` is async (non-blocking); `show()` is only called after `onReady` fires. Panes are load-on-activation and unload-on-replacement.
- **Two-tap touch model** — first tap on an unfocused button focuses it only (no activation, no ripple); second tap activates. Physical remote is the primary input path.
- **Focus owner** — two contexts: button-row focus (`focusedIndex` int) and pane focus. When a toggle-type button activates its pane, focus transfers into the pane; button row stays visible.
- **Remote input** — `BroadcastReceiver` on action `com.thorkracing.wireddevices.keypress`. Key 19/20 = DPAD up/down, 66 = activate (Round Button 1), 111 = Round Button 2. Auto-repeat suppressed via `pressedKeys: MutableSet<Int>`, cleared on `onPause()`.
- **SharedPreferences** (`button_config`) — persists `btn_{0-5}_type` / `btn_{0-5}_value` / `btn_{0-5}_label` and `focused_index`.
- **Material Design 3 dark theme** — `#1A1A1A` background, `#F57C00` orange accent; optimized for automotive displays. minSdk 34 (Android 14), no backwards-compatibility concerns.

### Button types (sealed class `ButtonConfig`)

| Type | Activation | Pane |
|---|---|---|
| `AppLauncher` | `startActivity()` via package manager | none |
| `UrlLauncher` | `Intent.ACTION_VIEW` (prepends `https://` if no scheme) | none |
| `WidgetLauncher` | Inflates `AppWidgetHostView` | `WidgetPaneContent` |
| `AppsGrid` | Opens installed-apps grid | `AppsGridPaneContent` |
| `MusicPlayer` | Binds MediaBrowserService | `MusicPlayerPaneContent` |
| `Empty` | no-op | none |

### CI/CD

Two GitHub Actions workflows in `.github/workflows/`:

- **`build.yml`** — push to `main` or PRs labeled `run-build`: lint → assembleDebug → zipalign + sign → draft pre-release (`dev` tag), APK named `aR2Launcher-dev-{SHORT_SHA}.apk`.
- **`release.yml`** — manual `workflow_dispatch`: auto-increments patch version from git tags → assembleRelease → zipalign + sign → git tag + GitHub release draft, APK named `aR2Launcher-{VERSION}.apk`.

Both workflows use Temurin JDK 17 and sign with `apksigner` (zipalign first).
