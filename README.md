<p align="center">
  <img src=".github/assets/banner.png" alt="SealGate — connect &amp; govern how AI interacts with your data" width="820">
</p>

<h1 align="center">Mobile-Stdiod</h1>

<img src=".github/assets/android.svg" alt="Android" height="16"> The Android
client for the SealGate **stdio tunnel** — a device-side daemon that lets cloud
agents reach MCP (Model Context Protocol) servers running on your phone. It holds
a single **outbound** WebSocket to the SealGate backend, so there is no inbound
port to open, and your data and logins stay on the device.

Based on the design in
[Stdio Tunnels: Bridging Cloud Agents to Local MCPs](https://sealgate.ai/blog/stdio-tunnels-cloud-agents-reach-local-mcps).

The tunnel speaks the same wire protocol as the desktop `sealgate-stdiod`
daemon (protocol v2; see `schema/tunnel-protocol.json`). Where the desktop
daemon spawns `npx`/`uvx` subprocesses, this app answers MCP requests from
**in-process Kotlin modules** — a phone can't spawn stdio servers.

| Module | Tools | What it does |
|--------|------:|--------------|
| `deviceinfo` | 1 | Device model, OS build, and identifiers. |
| `battery` | 1 | Charge level and charging state. |
| `wifi` | 1 | Wi-Fi connection status. |
| `bluetooth` | 19 | BLE + classic Bluetooth: status, scan, GATT read/write, notify/indicate, RFCOMM/SPP, and pairing. |
| `usb` | 6 | USB-OTG host access: enumerate devices, request permission, and raw bulk/control transfers. |

Each module's tool set is defined in its `mcp/*Module.kt` and surfaced to the
agent via `tools/list`.

## Architecture

<p align="center">
  <img src=".github/assets/architecture.svg" alt="Mobile-Stdiod architecture: a phone runs a daemon that dials out over a single wss WebSocket to the SealGate backend, which lets cloud agents reach the phone's local MCP modules" width="900">
</p>

- The daemon opens **one outbound WebSocket** — no incoming ports, works behind
  NAT and mobile carriers.
- After a `client_hello`/`server_hello` handshake it exchanges symmetric
  `mcp_frame`s (opaque JSON-RPC bodies), routed to built-in modules by server
  name.
- It runs as an Android **foreground service** so the OS keeps it alive, and
  reconnects forever with jittered exponential backoff.

## <img src=".github/assets/android.svg" alt="" height="15"> Requirements

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

While the service runs, it posts an ongoing notification:

<p align="center">
  <img src=".github/assets/notification.svg" alt="The Mobile-Stdiod foreground-service notification on an Android lock screen: 'Stdio tunnel active — Connected to the gateway.'" width="300">
</p>

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
