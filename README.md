# Mobile-Stdiod

The Android client for the SealGate **stdio tunnel** — a device-side daemon
that lets cloud agents reach MCP (Model Context Protocol) servers running on
your phone. It holds a single **outbound** WebSocket to the SealGate backend,
so there is no inbound port to open, and your data and logins stay on the
device.

Based on the design in
[Stdio Tunnels: Bridging Cloud Agents to Local MCPs](https://sealgate.ai/blog/stdio-tunnels-cloud-agents-reach-local-mcps).

The tunnel speaks the same wire protocol as the desktop `sealgate-stdiod`
daemon (protocol v2; see `schema/tunnel-protocol.json`). Where the desktop
daemon spawns `npx`/`uvx` subprocesses, this app answers MCP requests from
**in-process Kotlin modules** — a phone can't spawn stdio servers. The first
module is `deviceinfo` (`get_device_info`).

## Architecture

```
        Phone (Mobile-Stdiod)                        Cloud
  ┌───────────────────────────────┐         ┌──────────────────────┐
  │  MainActivity (start / stop)  │         │                      │
  │             │                 │         │                      │
  │             ▼                 │         │                      │
  │  TunnelService  ──────────────┼── wss ──┼──►  SealGate backend │◄── Cloud agent
  │  (foreground service)         │ outbound│   (MCP gateway)      │ (ChatGPT / Claude / …)
  │             │                 │         └──────────────────────┘
  │             ▼                 │
  │  TunnelClient                 │
  │   client_hello / server_hello │
  │   ping-pong, reconnect+backoff│
  │             │                 │
  │             ▼                 │
  │  built-in MCP modules         │
  │  (deviceinfo, …)              │
  └───────────────────────────────┘
```

- The daemon opens **one outbound WebSocket** — no incoming ports, works behind
  NAT and mobile carriers.
- After a `client_hello`/`server_hello` handshake it exchanges symmetric
  `mcp_frame`s (opaque JSON-RPC bodies), routed to built-in modules by server
  name.
- It runs as an Android **foreground service** so the OS keeps it alive, and
  reconnects forever with jittered exponential backoff.

## Project layout

```
.
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/ai/sealgate/stdiod/
│   │   │   ├── MainActivity.kt      # start/stop UI (View-based, no Compose)
│   │   │   ├── TunnelService.kt     # foreground service; owns TunnelClient
│   │   │   ├── TunnelConfig.kt      # gateway URL + auth token value type
│   │   │   ├── tunnel/              # wire protocol codec + WebSocket client
│   │   │   └── mcp/                 # in-process MCP modules (deviceinfo, …)
│   │   └── res/                     # layout, strings, theme, adaptive icon
│   └── src/test/                    # JVM tests incl. golden-frame round trips
├── schema/
│   ├── tunnel-protocol.json         # vendored wire schema (canonical: Edison-Watch/app)
│   └── golden-frames/               # shared fixtures round-tripped in CI
├── build.gradle.kts                 # root build
├── settings.gradle.kts
├── gradle/libs.versions.toml        # version catalog
└── gradlew / gradlew.bat            # Gradle wrapper (Gradle 8.9)
```

## Requirements

- Android Studio (Ladybug / 2024.2+ recommended)
- JDK 17+
- Android SDK Platform 35, min SDK 26

## Getting started

1. Open the project in Android Studio (**File → Open**, select this folder) and
   let it sync, **or** build from the command line:
   ```bash
   ./gradlew assembleDebug        # build the debug APK
   ./gradlew testDebugUnitTest    # run JVM unit tests
   ./gradlew installDebug         # install on a connected device/emulator
   ```
2. Run the app and tap **Start tunnel**. Today that starts the foreground
   service with a placeholder config; wire up a real gateway URL and token next.

## Implementing the tunnel

The transport is intentionally left as a `TODO` so you can drop in your own
client. In `TunnelService.connect`:

1. Open a WebSocket to `TunnelConfig.gatewayUrl` with a bearer auth header.
2. Launch the local stdio MCP server process.
3. Pump bytes both ways: gateway frames → process `stdin`, process `stdout` →
   gateway, translating stdio framing to the gateway's HTTP/SSE transport.
4. Reconnect with exponential backoff on disconnect.

Then replace the placeholder config in `MainActivity` with values read from
app settings (e.g. Jetpack DataStore).

## License

See [LICENSE](LICENSE).
