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
`wifi` (`get_wifi_status`), `bluetooth`, and `usb`. The `bluetooth` module reads state
(`get_bluetooth_status`, `list_bonded_devices`) **and** performs the write/control
actions a normal, unprivileged APK is permitted: BLE discovery and GATT
read/write (`bt_scan`, `bt_gatt_connect`, `bt_gatt_services`, `bt_gatt_read`,
`bt_gatt_write`, `bt_gatt_disconnect`), GATT notify/indicate for
request/response with devices that reply on an RX characteristic —
`bt_gatt_request_mtu`, `bt_gatt_subscribe`, `bt_gatt_notifications_poll`,
`bt_gatt_unsubscribe`, and the `bt_gatt_write_wait` primitive (write TX, collect
the RX reply) — classic serial RFCOMM/SPP streaming
(`bt_spp_connect`, `bt_spp_send`, `bt_spp_recv`, `bt_spp_disconnect`), and
pairing (`bt_pair`, `bt_unpair`). Turning the adapter on/off is intentionally
**not** offered — Android forbids it for third-party apps (a no-op since API 33).

The notify/indicate tools unblock Flipper Zero RPC, Nordic UART (NUS),
battery-level notify (`0x2a19`) and any UART-over-BLE sensor. `bt_gatt_subscribe`
writes the CCCD descriptor (`0x2902`) and buffers a device's notifications into a
per-characteristic queue that `bt_gatt_notifications_poll` drains (never silently
dropping — it reports an `overflow_count`). Both poll and `bt_gatt_write_wait`
accept `decode: "length_delimited"`, which reassembles varint-length-prefixed
frames (protobuf-style framing, as Flipper/NUS use) across notifications and
returns complete `frames` alongside the raw events. `bt_gatt_request_mtu` raises
the ATT MTU (Android defaults to 23, only 20 usable) so larger payloads and
Flipper screen frames fit.

Example — talk to a Flipper over its serial-RPC characteristic:

```jsonc
// 1. connect, then raise the MTU for large frames
bt_gatt_connect      { "address": "80:E1:26:00:11:22" }
bt_gatt_request_mtu  { "address": "80:E1:26:00:11:22", "mtu": 517 }
// 2. subscribe to the RX (indicate) characteristic
bt_gatt_subscribe    { "address": "80:E1:26:00:11:22",
                       "service": "19ed82ae-ed21-4c9d-4145-228e61fe0000",   // Flipper serial service
                       "characteristic": "19ed82ae-ed21-4c9d-4145-228e62fe0000", // RX (indicate)
                       "mode": "indicate" }
// 3. write a length-delimited protobuf frame to TX and collect the reply
bt_gatt_write_wait   { "address": "80:E1:26:00:11:22",
                       "tx_service": "19ed82ae-ed21-4c9d-4145-228e61fe0000",
                       "tx_characteristic": "19ed82ae-ed21-4c9d-4145-228e60fe0000", // TX (write)
                       "value_hex": "0a02087b",
                       "rx_service": "19ed82ae-ed21-4c9d-4145-228e61fe0000",
                       "rx_characteristic": "19ed82ae-ed21-4c9d-4145-228e62fe0000",
                       "decode": "length_delimited" }
// -> { "tx_written": true, "events": [...], "frames": ["..."], "overflow_count": 0 }
```

The `usb` module talks to devices plugged into the phone's USB port via the
**Android USB Host API** (USB-OTG): `usb_list_devices` enumerates attached devices
(vendor/product id, string descriptors, `has_permission`), `usb_request_permission`
fires the per-device system dialog USB host access needs (there is no manifest
permission for it), `usb_open` claims an interface and returns its endpoints, and
`usb_bulk_transfer` / `usb_control_transfer` / `usb_close` drive raw bulk and
control transfers (hex payloads, direction inferred from the endpoint address /
`request_type` top bit) — protocol-agnostic primitives an agent can layer CDC-ACM,
FTDI, HID or any vendor protocol on top of. A **USB-OTG adapter** is required, and
most devices need `usb_request_permission` approved on the phone before any I/O.

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
  │   wifi, bluetooth, usb)       │
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
│   │   │   └── mcp/                 # in-process MCP modules (deviceinfo, battery, wifi, bluetooth read + control, usb host)
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
