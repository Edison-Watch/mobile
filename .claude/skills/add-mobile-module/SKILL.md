---
name: add-mobile-module
description: Add or modify an in-process hardware MCP module in the Edison-Watch/mobile Android client, and verify its logic without the Android SDK. Use when adding or editing a *Module under app/.../mcp/, wiring a new *Source, hitting a lint MissingPermission error, or when ./gradlew fails because there is no Android SDK in the sandbox.
---

# Add a mobile hardware MCP module

The mobile app exposes each phone capability as an in-process "MCP server": a
Kotlin module answering MCP JSON-RPC directly (no subprocess). This skill is the
contract + how to verify it in the SDK-less cloud sandbox.

## The module contract

Two files per module, plus a test.

1. **`FooModule.kt`** in `app/src/main/java/ai/sealgate/stdiod/mcp/`
   - A **`FooSource` interface** holding *all* `android.*` access (so module
     logic is pure JVM, testable with a fake source).
   - `class FooModule(private val source: FooSource) : BaseMcpModule()`
     (`BaseMcpModule` + `JsonRpc` live in `LocalMcpModule.kt`).
   - Override `val name` (the server name the backend addresses) and
     `toolDescriptors(): JsonElement` (the `tools/list` array). Dispatch tool
     calls in the body — copy `BatteryModule` (simplest) or `UsbModule`.

2. **`AndroidFoo.kt` / `AndroidFooSource.kt`** — the real `FooSource`
   implementation over the Android APIs. This file is **not** JVM-compilable and
   is excluded from scratch verification (below).

3. **`FooModuleTest.kt`** in `app/src/test/java/ai/sealgate/stdiod/mcp/` — drive
   the module with a fake `FooSource`; assert on the JSON it returns.

Then **register it** in `TunnelService.connect()`:
```kotlin
FooModule(AndroidFooSource(this)),
```
And add a bullet to `README.md`'s module list.

## Hard rules

- **Every precondition failure is in-band**, never a crash and never a JSON-RPC
  error: return `JsonRpc.textToolResult(id, text, isError = true)` (missing
  permission, device not found, adapter off, …). The gateway must always get a
  tool result.
- **Binary payloads travel as hex strings** in tool args/results (see
  `BluetoothModule`/`UsbModule`), not base64, not byte arrays.
- **lint `MissingPermission` is checked per call-site and does NOT follow a
  `try/catch (SecurityException)` up into callers.** A permission-requiring call
  (BLE GATT, `UsbManager`, etc.) must catch `SecurityException` in the *same
  function* that makes the call. If lint fails only on a helper, wrap the
  helper's body in its own try/catch returning an error reason. This is one of
  the few things only the real toolchain (CI) catches — JVM tests won't.
- Async Android callbacks bridge to the synchronous `handle()` with
  latches/timeouts (`CountDownLatch` for GATT) or reader threads +
  `BlockingQueue` (SPP, notifications). Hold live connections in a
  `ConcurrentHashMap` keyed by address/id.

## Verify without the Android SDK

The cloud sandbox has **no Android SDK** (`dl.google.com` is proxy-blocked), so
`./gradlew assembleDebug|lintDebug|testDebugUnitTest` all fail locally. CI is the
real gate. To sanity-check module + test logic *before* pushing, compile just
the pure-JVM slice in a throwaway Gradle project:

```
/tmp/scratch/
  build.gradle.kts        # kotlin("jvm") + kotlin("plugin.serialization")
  settings.gradle.kts
  src/main/kotlin/  <- LocalMcpModule.kt + FooModule.kt   (NOT AndroidFoo*.kt)
  src/test/kotlin/  <- FooModuleTest.kt + a hand-written FakeFooSource
```

- `build.gradle.kts`: `kotlin("jvm")` and `kotlin("plugin.serialization")` at the
  **same version as `kotlin` in `gradle/libs.versions.toml`**; deps
  `kotlinx-serialization-json` (1.7.x is fine) and `junit:junit:4.13.2`.
- Copy in **only** `LocalMcpModule.kt`, your `FooModule.kt`, and the test.
  Exclude every `Android*.kt` — those need the SDK.
- `gradle test`. Green here means the JSON/dispatch logic holds; it does **not**
  cover lint or Android wiring — push and let CI confirm those.

## After push

CI ("Build & unit test") runs `assembleDebug` + `testDebugUnitTest` +
`lintDebug`. Green there is the real pass. Then on a device:
`git pull && ./gradlew installDebug`. The new tools appear in MCP Tester once a
server row of `name` is registered and the tool list refreshed.
