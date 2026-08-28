# CLAUDE.md

Guidance for AI agents working in this repository.

## Project overview

**Mobile-Stdiod** is the Android client for the SealGate **stdio tunnel**: a
device-side daemon that lets cloud agents reach local MCP servers over a single
**outbound** WebSocket to the SealGate backend. See [`README.md`](README.md)
for the architecture diagram and background.

The wire protocol (v2) is shared with the desktop `sealgate-stdiod` daemon.
The vendored schema lives at `schema/tunnel-protocol.json` (canonical copy:
`crates/stdiod/schema/` in Edison-Watch/app; also vendored in
edison-watch/edison-watch under `src/stdio_tunnel/`); the golden fixtures in
`schema/golden-frames/` are the shared bytes all three implementations
round-trip in their test suites. When the protocol changes, update the schema
+ fixtures in lockstep across all three repos.

Unlike the desktop daemon there is no subprocess supervision: `mcp_frame`s
route to in-process `LocalMcpModule` implementations (`app/.../mcp/`), and
desired-state entries that don't match a built-in module are refused with a
spawn error.

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
  owns the `TunnelClient` lifecycle and keeps the notification honest.
- `app/src/main/java/ai/sealgate/stdiod/TunnelConfig.kt` — connection settings.
- `app/src/main/java/ai/sealgate/stdiod/tunnel/` — wire protocol codec
  (`TunnelFrame.kt`), WebSocket client + reconnect (`TunnelClient.kt`),
  persisted device identity (`DeviceIdentityStore.kt`).
- `app/src/main/java/ai/sealgate/stdiod/mcp/` — in-process MCP modules
  (`deviceinfo`, `battery`, `wifi`, `bluetooth`); add a new hardware module by
  extending `BaseMcpModule` and registering it in `TunnelService.connect()`.
  `bluetooth` is a write/control module: besides the read tools
  (`get_bluetooth_status`, `list_bonded_devices`) it does BLE scan + GATT
  read/write (`bt_scan`, `bt_gatt_*`), classic RFCOMM/SPP (`bt_spp_*`), and
  pairing (`bt_pair`/`bt_unpair`). The Android impl bridges the async GATT
  callbacks / blocking RFCOMM IO to the synchronous module with
  latches/timeouts and holds live connections by address. Adapter on/off is
  deliberately excluded (Android forbids it for third-party apps).
  Hardware access goes behind a small source interface (e.g. `BatterySource`)
  with the Android implementation in its own `Android*` file, so module logic
  stays JVM-testable with fakes.
- `app/src/test/` — JVM tests; `GoldenFramesTest` round-trips every fixture
  in `schema/golden-frames/` and fails on codec drift.
- `app/src/main/res/` — layouts, strings, theme, adaptive launcher icon.
- Versions are centralized in `gradle/libs.versions.toml`; add libraries there,
  reference them as `libs.*` in `app/build.gradle.kts`.

## Conventions

- Keep the app buildable and installable at every step.
- Prefer the version catalog over hard-coded dependency versions.
- The tunnel is long-lived: it must run as a foreground service with an ongoing
  notification, and reconnect with backoff.
- Do not commit signing material (`*.jks`, `*.keystore`) or `local.properties`.
