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

**aR2Launcher** is an Android home launcher (`android.intent.category.HOME`) for automotive head units. It presents a persistent fullscreen landscape dashboard with 5 configurable buttons on the right third of the screen; the left two-thirds (`reservedArea`) is intentionally empty for future use.

The entire app is a **single Activity** (`MainActivity.kt`) with no fragments, ViewModels, or navigation graph. All logic — button rendering, config mode toggle, app/URL picker dialogs, icon loading, and SharedPreferences persistence — lives there.

### Key design decisions

- **`singleTask` launch mode** — Android routes relaunches through `onNewIntent()` instead of creating new instances. Required for home launchers.
- **Landscape-only, `stateNotNeeded=true`** — fixed orientation for head units; no saved instance state needed.
- **SharedPreferences** (`button_config`) — persists button config as `btn_{0-4}_type` (`app`|`url`), `btn_{0-4}_value` (package name or URL), `btn_{0-4}_label`.
- **Config mode** — toggled by long-pressing any button; visually indicated by an orange border stroke. Back press exits config mode (and is otherwise suppressed, as expected for a home launcher).
- **Material Design 3 dark theme** — `#1A1A1A` background, `#F57C00` orange accent, optimized for automotive displays.
- **minSdk 34 (Android 14)** — no backwards-compatibility concerns.

### CI/CD

Two GitHub Actions workflows in `.github/workflows/`:

- **`build.yml`** — triggered on push to `main` or PRs labeled `run-build`: lint → assembleDebug → zipalign + sign → draft pre-release (`dev` tag) with APK named `aWayToGo-dev-{SHORT_SHA}.apk`.
- **`release.yml`** — manual `workflow_dispatch`: auto-increments patch version from git tags (or accepts manual version) → assembleRelease → zipalign + sign → git tag + GitHub release draft → APK named `aWayToGo-{VERSION}.apk`.

Both workflows use Temurin JDK 17 and sign with `apksigner` (zipalign first).
