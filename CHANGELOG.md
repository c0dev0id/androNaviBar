# Changelog

## [Unreleased]

### Added
- Split-screen toggle launcher using `TrampolineActivity` with
  `FLAG_ACTIVITY_LAUNCH_ADJACENT` for Android 14+ (API 34+)
- `MainActivity` with `singleTask` launch mode and toggle lifecycle
  (re-launch quits, split-screen dismiss quits)
- Android project build boilerplate (Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21)
- CI workflow for debug builds on push/PR to main
- Release workflow with signed APK builds, auto-versioning, and GitHub release drafts
- Release signing config reading from environment variables
