# Chroma for Android

Companion client for the official Spotify app (Spotify on Android can't be modded, so Chroma controls it instead).

Build: open in Android Studio, or `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (signed with the debug key, sideload-ready).
First launch: grant notification access so Chroma can read Spotify's media session.

## Build without Android Studio
Push this folder to a GitHub repo; the "Build APK" workflow builds it. Download `chroma-android-apk` from the Actions run (Actions tab → latest run → Artifacts).
