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

For a full, real-device worked example — reading an NFC card off a Flipper Zero
over its BLE serial-RPC service — see
[Flipper Zero over BLE](#flipper-zero-over-ble-worked-example) below.

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

<p align="center">
  <img src=".github/assets/architecture.svg" alt="Mobile-Stdiod architecture: a phone runs a daemon that dials out over a single wss WebSocket to the SealGate backend, which lets cloud agents reach the phone's local MCP modules" width="900">
</p>

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

## Flipper Zero over BLE (worked example)

A cloud agent can drive a [Flipper Zero](https://flipperzero.one/) through the
gateway to run its protobuf **BLE RPC** — here, reading an NFC card it just
scanned — using only the shipped `bt_*` GATT tools. The phone module is
**protocol-agnostic**: it moves framed bytes and never parses protobuf. The
agent encodes/decodes the Flipper `PB.Main` messages (`app_start`, storage/NFC
requests, `Empty` acks) itself and hands the module raw `value_hex`.

**Two gotchas, read first:**

1. **Do NOT send the `start_rpc_session` ASCII string over BLE.** That USB-only
   handshake corrupts the BLE RPC framing. A connected+activated BLE link is
   already in RPC mode — just write length-delimited `PB.Main` frames to TX.
2. **The official Flipper mobile app must be disconnected.** Flipper BLE accepts
   exactly **one** client; a second connection silently fails.

Flipper serial service `8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000`, characteristics
(share the base, differ in the `…6Xfe…` nibble):

| Role | Characteristic UUID | GATT op |
|------|---------------------|---------|
| RX (device→phone) | `8fe5b3d5-2e7f-4a98-2a48-7acc61fe0000` | **indicate** (CCCD `0x0002`) |
| TX (phone→device) | `8fe5b3d5-2e7f-4a98-2a48-7acc62fe0000` | **write** (length-delimited protobuf) |
| Flow control | `8fe5b3d5-2e7f-4a98-2a48-7acc63fe0000` | notify (CCCD `0x0001`) |
| RPC status | `8fe5b3d5-2e7f-4a98-2a48-7acc64fe0000` | notify (CCCD `0x0001`) |

The sequence below is against a Pixel 6 ↔ Flipper `80:E1:27:66:36:CB`.
`value_hex` payloads are illustrative — the agent supplies the real
protobuf-encoded bytes.

```jsonc
// 0. pair (once) + connect, then raise the MTU so whole frames fit
bt_pair              { "address": "80:E1:27:66:36:CB" }
bt_gatt_connect      { "address": "80:E1:27:66:36:CB" }
bt_gatt_request_mtu  { "address": "80:E1:27:66:36:CB", "mtu": 517 }

// 1. subscribe to RX (indications) and RPC-status (notify) up front.
//    Both queues are independent and live on the one pinned connection.
bt_gatt_subscribe    { "address": "80:E1:27:66:36:CB",
                       "service": "8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000",
                       "characteristic": "8fe5b3d5-2e7f-4a98-2a48-7acc61fe0000", // RX
                       "mode": "indicate" }                                       // CCCD 0x0002
bt_gatt_subscribe    { "address": "80:E1:27:66:36:CB",
                       "service": "8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000",
                       "characteristic": "8fe5b3d5-2e7f-4a98-2a48-7acc64fe0000", // status
                       "mode": "notify" }                                         // CCCD 0x0001

// 2. activate the RPC session: write 01 00 00 00 to the RPC-status char.
//    (This is the BLE activation write — NOT the start_rpc_session string.)
bt_gatt_write        { "address": "80:E1:27:66:36:CB",
                       "service": "8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000",
                       "characteristic": "8fe5b3d5-2e7f-4a98-2a48-7acc64fe0000",
                       "value_hex": "01000000" }

// 3. write the app_start / NFC-read PB.Main frame to TX and collect the reply.
//    The module can't see protobuf has_next, so bound the collection with
//    idle_timeout_ms / max_bytes; length_delimited reassembles PB.Main frames
//    that span several indications back into whole `frames`.
bt_gatt_write_wait   { "address": "80:E1:27:66:36:CB",
                       "tx_service": "8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000",
                       "tx_characteristic": "8fe5b3d5-2e7f-4a98-2a48-7acc62fe0000", // TX
                       "value_hex": "…app_start(/ext/nfc app) PB.Main…",
                       "rx_service": "8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000",
                       "rx_characteristic": "8fe5b3d5-2e7f-4a98-2a48-7acc61fe0000", // RX
                       "idle_timeout_ms": 3000,
                       "decode": "length_delimited" }
// -> { "tx_written": true, "frames": ["…PB.Main…"], "overflow_count": 0, ... }

// 4. drain any trailing RX frames the same way (repeat until frames stop /
//    the agent sees has_next = 0 in the decoded PB.Main).
bt_gatt_notifications_poll { "address": "80:E1:27:66:36:CB",
                             "service": "8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000",
                             "characteristic": "8fe5b3d5-2e7f-4a98-2a48-7acc61fe0000",
                             "decode": "length_delimited" }

// 5. read the saved card via a Storage.Read PB.Main over the same TX/RX pair
bt_gatt_write_wait   { "address": "80:E1:27:66:36:CB",
                       "tx_service": "8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000",
                       "tx_characteristic": "8fe5b3d5-2e7f-4a98-2a48-7acc62fe0000",
                       "value_hex": "…storage_read(/ext/nfc/MyCard.nfc) PB.Main…",
                       "rx_service": "8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000",
                       "rx_characteristic": "8fe5b3d5-2e7f-4a98-2a48-7acc61fe0000",
                       "max_bytes": 65536,
                       "decode": "length_delimited" }
// -> frames carry the file contents; the agent concatenates them until has_next = 0
```

Why this maps cleanly onto the shipped tools: the GATT connection is **pinned
per address**, so the subscribe / activate / write / poll calls all reuse one
live link; `bt_gatt_subscribe` writes the concrete CCCD value for the mode
(indicate `0x0002` on RX, notify `0x0001` on status), with `auto` picking
indicate when the characteristic advertises it; `bt_gatt_write`/`_write_wait`
send **exactly** the bytes given (no implicit framing or `start_rpc_session`);
and `decode: "length_delimited"` reassembles the varint-prefixed `PB.Main`
frames across indications.

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
