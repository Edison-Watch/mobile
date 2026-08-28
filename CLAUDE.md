# CLAUDE.md

Caveman-terse. Android client for SealGate stdio tunnel. Details: README.md.

## What
- Device daemon. Outbound WebSocket to backend. Cloud agents reach local MCP servers.
- No subprocesses. `mcp_frame` -> in-process `LocalMcpModule` (`app/.../mcp/`). Unknown name -> spawn error.
- Kotlin, JDK 17. Android Views, no Compose. Single module `:app`, ns `ai.sealgate.stdiod`.

## ❗ Environment (NOT discoverable from code — read this)
- **No Android SDK in cloud sandbox.** `dl.google.com` blocked. `./gradlew assembleDebug|lintDebug|testDebugUnitTest` FAIL locally. CI (`.github/workflows/android.yml`, job "Build & unit test") = sole build+lint+test gate. Push, watch CI.
- Verify module logic without SDK -> JVM scratch project. Skill: `add-mobile-module`.
- lint `MissingPermission` per-call-site. Catch `SecurityException` in SAME function as the BLE/permission call, never a caller.
- Trifecta/policy classification for these tools lives in `edison-watch` repo, NOT here.

## Where
- Modules: `app/.../mcp/`. Add one: copy a module (e.g. `BatteryModule`), register in `TunnelService.connect()`. Hardware behind `*Source` iface + `Android*.kt` impl -> JVM-testable. Skill: `add-mobile-module`.
- Protocol: `schema/`. `golden-frames/` = codec fixtures (`GoldenFramesTest`). Change -> update schema+fixtures across 3 repos in lockstep (README).
- Versions: `gradle/libs.versions.toml` = source of truth. Use `libs.*`. Don't copy numbers here (they rot).

## Rules
- Buildable + installable every step.
- Tool failure -> in-band `JsonRpc.textToolResult(id, text, isError=true)`. Never crash, never a JSON-RPC error.
- Tunnel long-lived: foreground service + reconnect backoff.
- Never commit signing material (`*.jks`, `*.keystore`) or `local.properties`.
