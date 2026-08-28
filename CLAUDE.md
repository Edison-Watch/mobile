# CLAUDE.md

Guidance for AI agents working in this repository.

## Project overview

**Mobile-Stdiod** is an Android app template for an **stdio tunnel**: a
device-side daemon that lets cloud agents reach local MCP servers over a single
**outbound** WebSocket to a hosted gateway. See [`README.md`](README.md) for the
architecture diagram and background.

The tunnel transport itself is a stub — the template gives you a building,
runnable app shell (Start/Stop UI + foreground service) to implement it in.

## Stack

- **Language:** Kotlin (JDK 17 toolchain)
- **UI:** Android Views + View Binding (no Jetpack Compose)
- **Build:** Gradle 8.9 (Kotlin DSL) with a version catalog in
  `gradle/libs.versions.toml`
- **Android Gradle Plugin:** 8.7.x · **compileSdk/targetSdk:** 35 · **minSdk:** 26
- Single module: `:app`, namespace `ai.sealgate.stdiod`

## Common commands

```bash
./gradlew assembleDebug        # build the debug APK
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew lint                 # Android lint
./gradlew installDebug         # install on a connected device/emulator
```

## Where things live

- `app/src/main/java/ai/sealgate/stdiod/MainActivity.kt` — start/stop control UI.
- `app/src/main/java/ai/sealgate/stdiod/TunnelService.kt` — foreground service;
  `connect()` is the stub where the WebSocket + stdio bridging goes.
- `app/src/main/java/ai/sealgate/stdiod/TunnelConfig.kt` — connection settings.
- `app/src/main/res/` — layouts, strings, theme, adaptive launcher icon.
- Versions are centralized in `gradle/libs.versions.toml`; add libraries there,
  reference them as `libs.*` in `app/build.gradle.kts`.

## Conventions

- Keep the app buildable and installable at every step.
- Prefer the version catalog over hard-coded dependency versions.
- The tunnel is long-lived: it must run as a foreground service with an ongoing
  notification, and reconnect with backoff.
- Do not commit signing material (`*.jks`, `*.keystore`) or `local.properties`.
