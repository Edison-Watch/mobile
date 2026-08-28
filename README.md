<p align="center">
  <img src=".github/assets/banner.png" alt="SealGate — connect &amp; govern how AI interacts with your data" width="820">
</p>

<h1 align="center">Mobile-Stdiod</h1>

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
**in-process Kotlin modules** — a phone can't spawn stdio servers. The built-in
modules are `deviceinfo` (`get_device_info`), `battery` (`get_battery_status`),
`wifi` (`get_wifi_status`), and `bluetooth` (`get_bluetooth_status`,
`list_bonded_devices`).

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
  │  (deviceinfo, battery,        │
  │   wifi, bluetooth)            │
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
│   │   │   └── mcp/                 # in-process MCP modules (deviceinfo, battery, wifi, bluetooth)
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
2. Run the app, fill in the gateway WebSocket URL and your SealGate API key
   (from the dashboard), and tap **Start tunnel**. Settings persist across
   restarts; the ongoing notification shows the live connection state.

## Adding a hardware module

Each "stdio server" on mobile is an in-process Kotlin module. To add one:

1. Extend `BaseMcpModule` (see `mcp/DeviceInfoModule.kt`) — supply the tool
   descriptors and the `tools/call` handler; the MCP lifecycle
   (`initialize`, `ping`, `tools/list`) is handled for you.
2. Register it in the `modules` list in `TunnelService.connect`.
3. Enable a server with that module's name for the device in the SealGate
   dashboard; the tunnel binds it by name and acks the spawn.

## License

See [LICENSE](LICENSE).
