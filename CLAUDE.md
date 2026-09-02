# CLAUDE.md

Caveman-terse. Android client for SealGate stdio tunnel. Details: README.md.

## What
- Device daemon. Outbound WebSocket to backend. Cloud agents reach local MCP servers.
- No subprocesses. `mcp_frame` -> in-process `LocalMcpModule` (`app/.../mcp/`). Unknown name -> spawn error.
- Mobile Bash is the only MCP surface: prefix `mobilebash`, one `run` tool, dashboard display name `Mobile Bash`, command `mobile-builtin`. Pinned just-bash browser bundle in QuickJS, in-memory FS only. Source/build recipe: `tools/bash-runtime/`; generated asset is checked in.
- Kotlin, JDK 17. Android Views, no Compose. Single module `:app`, ns `ai.sealgate.stdiod`.

## ❗ Environment (NOT discoverable from code — read this)
- **No Android SDK in cloud sandbox.** `dl.google.com` blocked. `./gradlew assembleDebug|lintDebug|testDebugUnitTest` FAIL locally. CI (`.github/workflows/android.yml`, job "Build & unit test") = sole build+lint+test gate. Push, watch CI.
- Verify module logic without SDK -> JVM scratch project. Skill: `add-mobile-module`.
- lint `MissingPermission` per-call-site. Catch `SecurityException` in SAME function as the BLE/permission call, never a caller.
- Trifecta/policy classification for these tools lives in `edison-watch` repo, NOT here.

## Where
- Capability modules: `app/.../mcp/`. Add one: copy a module (e.g. `BatteryModule`), register in `TunnelService.connect()`, and map its CLI in `MobileCommandRouter`. Never expose it as a separate MCP server. Hardware behind `*Source` iface + `Android*.kt` impl -> JVM-testable. Skill: `add-mobile-module`.
- Bash CLI mappings live in `MobileCommandRouter`; keep new module tools mapped there. Do not add host FS, process, network, or language-runtime bindings.
- Protocol: `schema/`. `golden-frames/` = codec fixtures (`GoldenFramesTest`). Change -> update schema+fixtures across 3 repos in lockstep (README).
- Versions: `gradle/libs.versions.toml` = source of truth. Use `libs.*`. Don't copy numbers here (they rot).

## Rules
- Buildable + installable every step.
- Tool failure -> in-band `JsonRpc.textToolResult(id, text, isError=true)`. Never crash, never a JSON-RPC error.
- Tunnel long-lived: foreground service + reconnect backoff.
- Never commit signing material (`*.jks`, `*.keystore`) or `local.properties`.
