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

### Mobile Bash

The app exposes one built-in MCP server, conventionally registered under the
SealGate prefix `mobilebash`. The prefix is only a label: the dashboard
command `mobile-builtin` is what binds a server to this module, so any prefix
works (prefixes are unique per organisation, so a second phone in the same org
needs a different one).
Its single `run` tool executes the required `script` argument
inside a restricted [just-bash](https://github.com/vercel-labs/just-bash)
environment:

```bash
battery status | jq '.level_percent'
bluetooth scan --timeout-ms 5000 > /tmp/scan.json
jq '.devices[] | select(.rssi > -70)' /tmp/scan.json
```

Run `device --help`, `battery --help`, `wifi --help`, `bluetooth --help`,
`usb --help`, or (in non-Play builds) `computer --help` to discover the Android CLI. It delegates to the same Kotlin
capability modules, including Bluetooth and USB control operations, so Android runtime
permissions and on-device permission dialogs still apply.

| Command | What it does |
|---------|--------------|
| `device` | Device model, OS build, and identifiers. |
| `battery` | Charge level and charging state. |
| `wifi` | Wi-Fi connection status. |
| `bluetooth` | BLE + classic Bluetooth: status, scan, GATT read/write, notify/indicate, RFCOMM/SPP, and pairing. |
| `usb` | USB-OTG host access: enumerate devices, request permission, and raw bulk/control transfers. |
| `computer` | Optional private-build cross-app observation and control, returning an MCP image and accessibility tree together. |

Computer control is compiled into debug, private, and enterprise builds only;
the Play/release manifest contains no accessibility service. It defaults off
and requires both the in-app toggle and explicit activation in Android's
Accessibility settings. `computer observe` and post-action results carry the
screenshot as native MCP image content and the matching accessibility tree as
structured content. The 1 MiB Bash output limit remains separate from a bounded
4 MiB typed MCP result.

The virtual filesystem is shared across calls for one tunnel run and destroyed
when that run stops. Shell-local variables, functions, aliases, and working
directory reset after each call. Mobile Bash has no Android
filesystem, real process, general network, Python, JavaScript, or SQLite access. It enforces a
60-second call deadline, a 64 KiB script limit, 1 MiB output limit, 8 MiB
per-file limit, and 32 MiB total virtual filesystem limit.

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

   While the tunnel is running, pull down from the top of the app screen to
   close the current socket and reconnect immediately with the saved settings.

   In the SealGate dashboard, add one local server for the device with display
   name **Mobile Bash**, MCP prefix `mobilebash`, and command `mobile-builtin`.
   No arguments are required. The resulting agent tool is `mobilebash_run`.
   If `mobilebash` is already taken in your organisation, pick any other
   prefix (say `mobilebash-alice`); the command is what matters, and the tool
   becomes `<prefix>_run`.

While the service runs, it posts an ongoing notification:

<p align="center">
  <img src=".github/assets/notification.svg" alt="The Mobile-Stdiod foreground-service notification on an Android lock screen: 'Stdio tunnel active — Connected to the gateway.'" width="300">
</p>

## Adding a hardware module

Android capabilities are in-process Kotlin modules behind the Mobile Bash CLI.
To add one:

1. Extend `BaseMcpModule` (see `mcp/DeviceInfoModule.kt`) — supply the tool
   descriptors and the `tools/call` handler; the MCP lifecycle
   (`initialize`, `ping`, `tools/list`) is handled for you.
2. Register it in the capability list in `TunnelService.connect`.
3. Map its commands in `MobileCommandRouter` and add CLI-focused tests. Do not
   register another dashboard server or expose the module as a separate MCP
   surface.

## License

See [LICENSE](LICENSE).

The embedded just-bash runtime and QuickJS bridge are Apache-2.0 dependencies;
see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
