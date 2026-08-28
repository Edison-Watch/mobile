package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** One paired device, as `list_bonded_devices` reports it. */
data class BondedDevice(
    /** Device name, or null when unnamed. */
    val name: String?,
    /** `classic` | `le` | `dual` | `unknown`. */
    val type: String,
)

/** One device seen during a BLE scan, as `bt_scan` reports it. */
data class ScannedDevice(
    /** Hardware address, e.g. `AA:BB:CC:DD:EE:FF`. */
    val address: String,
    /** Advertised name, or null when not advertised / unreadable. */
    val name: String?,
    /** `classic` | `le` | `dual` | `unknown`. */
    val type: String,
    /** Signal strength in dBm. */
    val rssi: Int,
)

/** A GATT characteristic and the operations it advertises. */
data class GattCharacteristic(
    val uuid: String,
    /** Any of `read` | `write` | `write_no_response` | `notify` | `indicate`. */
    val properties: List<String>,
)

/** A GATT service and its characteristics. */
data class GattService(
    val uuid: String,
    val characteristics: List<GattCharacteristic>,
)

/**
 * Result of a BLE scan. On failure [error] carries a user-facing reason and
 * [devices] is empty.
 */
data class ScanResult(
    val devices: List<ScannedDevice> = emptyList(),
    val error: String? = null,
)

/**
 * Result of connecting to / enumerating a GATT peripheral. On failure [error]
 * is set and [services] is empty.
 */
data class GattServicesResult(
    val services: List<GattService> = emptyList(),
    val error: String? = null,
)

/** Result of a GATT characteristic read. On success [value] holds the bytes. */
data class GattReadResult(
    val value: ByteArray? = null,
    val error: String? = null,
)

/** Result of draining buffered SPP inbound bytes. */
data class SppRecvResult(
    val value: ByteArray = ByteArray(0),
    val error: String? = null,
)

/**
 * Generic success/failure result for control actions that carry no payload.
 * A null [error] means success.
 */
data class BtOpResult(val error: String? = null)

/**
 * What the read-only bluetooth tools report. Behind an interface so the module
 * is testable on the JVM, away from `BluetoothAdapter` and permission checks.
 */
interface BluetoothSource {
    /** Whether the device has a bluetooth adapter at all. */
    val adapterPresent: Boolean

    /** Whether the adapter is switched on. */
    val enabled: Boolean

    /** Whether the app holds `BLUETOOTH_CONNECT` (always true before API 31). */
    val hasConnectPermission: Boolean

    /** Paired devices; only called when [hasConnectPermission]. */
    fun bondedDevices(): List<BondedDevice>
}

/**
 * The write/control surface layered on top of [BluetoothSource]. Every method
 * blocks up to `timeoutMs` and returns a plain domain result (a payload or a
 * `String` error reason) - all the callback/blocking-IO async lives inside the
 * Android implementation, never in the module. Live GATT/RFCOMM connections are
 * held by address inside the source across tool calls.
 */
interface BluetoothControlSource : BluetoothSource {
    /** Whether the app holds `BLUETOOTH_SCAN` (always true before API 31). */
    val hasScanPermission: Boolean

    /** BLE discovery for [timeoutMs]; returns the devices seen. */
    fun scan(timeoutMs: Long): ScanResult

    /** Bond with [address]; already-bonded is idempotent success. */
    fun pair(address: String, timeoutMs: Long): BtOpResult

    /** Remove the bond with [address] (reflective `removeBond`). */
    fun unpair(address: String): BtOpResult

    /** Open a GATT connection to [address] and discover its services. */
    fun gattConnect(address: String, timeoutMs: Long): GattServicesResult

    /** Services/characteristics of an already-connected GATT peripheral. */
    fun gattServices(address: String): GattServicesResult

    /** Read one characteristic's current value. */
    fun gattRead(address: String, service: String, characteristic: String, timeoutMs: Long): GattReadResult

    /** Write [value] to one characteristic. */
    fun gattWrite(
        address: String,
        service: String,
        characteristic: String,
        value: ByteArray,
        withResponse: Boolean,
        timeoutMs: Long,
    ): BtOpResult

    /** Close and evict the GATT connection to [address]. */
    fun gattDisconnect(address: String): BtOpResult

    /** Open an RFCOMM/SPP socket to [address] on [uuid] and hold it. */
    fun sppConnect(address: String, uuid: String, timeoutMs: Long): BtOpResult

    /** Write [value] to the SPP socket's output stream. */
    fun sppSend(address: String, value: ByteArray, timeoutMs: Long): BtOpResult

    /** Drain buffered inbound SPP bytes (waiting up to [timeoutMs] for some). */
    fun sppRecv(address: String, timeoutMs: Long, maxBytes: Int): SppRecvResult

    /** Close and evict the SPP socket to [address]. */
    fun sppDisconnect(address: String): BtOpResult
}

/**
 * Bluetooth hardware module. Two read-only tools plus the Tier-1 write/control
 * surface a normal, unprivileged APK is permitted: BLE scan, pair/unpair, GATT
 * read/write, and classic RFCOMM/SPP streaming. Adapter enable/disable is
 * deliberately absent - Android forbids it for third-party apps (no-op since
 * API 33) - as is any profile injection into locked devices.
 *
 * Depends on the richer [BluetoothControlSource]; the module owns arg parsing,
 * JSON shaping and error mapping (all JVM-testable), while the source owns the
 * async->sync bridging.
 */
class BluetoothModule(private val source: BluetoothControlSource) : BaseMcpModule() {

    override val name: String = NAME

    override fun toolDescriptors(): JsonElement = buildJsonArray {
        add(descriptor("get_bluetooth_status", "Report whether this Android device has a bluetooth adapter and whether it is enabled."))
        add(descriptor("list_bonded_devices", "List the bluetooth devices paired with this Android device (name and type)."))
        add(
            descriptor(
                "bt_scan",
                "Scan for nearby Bluetooth Low Energy (BLE) peripherals. Returns discovered devices with address, name, type and RSSI. Requires the \"Nearby devices\" scan permission.",
            ) {
                intProp("timeout_ms", "How long to scan, in ms (default 8000, capped at 30000).")
            },
        )
        add(
            descriptor("bt_pair", "Pair (bond) with a bluetooth device by address. Idempotent: an already-paired device succeeds.") {
                stringProp("address", "Device hardware address, e.g. \"AA:BB:CC:DD:EE:FF\".", required = true)
                intProp("timeout_ms", "How long to wait for bonding, in ms.")
            },
        )
        add(
            descriptor("bt_unpair", "Remove the pairing (bond) with a bluetooth device by address.") {
                stringProp("address", "Device hardware address.", required = true)
            },
        )
        add(
            descriptor(
                "bt_gatt_connect",
                "Connect to a BLE peripheral over GATT and discover its services. The connection is held open for later bt_gatt_read/bt_gatt_write. Returns the service and characteristic UUIDs.",
            ) {
                stringProp("address", "Device hardware address.", required = true)
                intProp("timeout_ms", "How long to wait for connect + discovery, in ms.")
            },
        )
        add(
            descriptor("bt_gatt_services", "List the services and characteristics (with their properties) of an already-connected GATT peripheral.") {
                stringProp("address", "Device hardware address of a connected peripheral.", required = true)
            },
        )
        add(
            descriptor("bt_gatt_read", "Read a GATT characteristic's value from a connected peripheral. Returns the value as a lowercase hex string.") {
                stringProp("address", "Device hardware address of a connected peripheral.", required = true)
                stringProp("service", "Service UUID.", required = true)
                stringProp("characteristic", "Characteristic UUID.", required = true)
                intProp("timeout_ms", "How long to wait for the read, in ms.")
            },
        )
        add(
            descriptor("bt_gatt_write", "Write bytes to a GATT characteristic on a connected peripheral.") {
                stringProp("address", "Device hardware address of a connected peripheral.", required = true)
                stringProp("service", "Service UUID.", required = true)
                stringProp("characteristic", "Characteristic UUID.", required = true)
                stringProp("value_hex", "Bytes to write, as a hex string (e.g. \"01ff\").", required = true)
                boolProp("with_response", "Whether to request a write response (default true).")
                intProp("timeout_ms", "How long to wait for the write, in ms.")
            },
        )
        add(
            descriptor("bt_gatt_disconnect", "Close the held GATT connection to a peripheral.") {
                stringProp("address", "Device hardware address.", required = true)
            },
        )
        add(
            descriptor(
                "bt_spp_connect",
                "Open a classic Bluetooth serial (RFCOMM/SPP) connection to a device and hold it open. Inbound bytes are buffered for bt_spp_recv.",
            ) {
                stringProp("address", "Device hardware address.", required = true)
                stringProp("uuid", "RFCOMM service UUID (default SPP 00001101-0000-1000-8000-00805F9B34FB).")
                intProp("timeout_ms", "How long to wait for connect, in ms.")
            },
        )
        add(
            descriptor("bt_spp_send", "Send bytes over an open classic serial (SPP) connection.") {
                stringProp("address", "Device hardware address of a connected SPP device.", required = true)
                stringProp("value_hex", "Bytes to send, as a hex string.")
                stringProp("text", "Alternatively, UTF-8 text to send (used only if value_hex is absent).")
                intProp("timeout_ms", "How long to wait for the write, in ms.")
            },
        )
        add(
            descriptor("bt_spp_recv", "Drain buffered inbound bytes from an open classic serial (SPP) connection. Returns a lowercase hex string.") {
                stringProp("address", "Device hardware address of a connected SPP device.", required = true)
                intProp("timeout_ms", "How long to wait for at least some data, in ms (default 2000).")
                intProp("max_bytes", "Maximum number of bytes to return.")
            },
        )
        add(
            descriptor("bt_spp_disconnect", "Close the held classic serial (SPP) connection to a device.") {
                stringProp("address", "Device hardware address.", required = true)
            },
        )
    }

    override fun callTool(id: JsonElement, toolName: String, arguments: JsonObject): JsonObject =
        when (toolName) {
            "get_bluetooth_status" -> getBluetoothStatus(id)
            "list_bonded_devices" -> listBondedDevices(id)
            "bt_scan" -> btScan(id, arguments)
            "bt_pair" -> btPair(id, arguments)
            "bt_unpair" -> btUnpair(id, arguments)
            "bt_gatt_connect" -> btGattConnect(id, arguments)
            "bt_gatt_services" -> btGattServices(id, arguments)
            "bt_gatt_read" -> btGattRead(id, arguments)
            "bt_gatt_write" -> btGattWrite(id, arguments)
            "bt_gatt_disconnect" -> btGattDisconnect(id, arguments)
            "bt_spp_connect" -> btSppConnect(id, arguments)
            "bt_spp_send" -> btSppSend(id, arguments)
            "bt_spp_recv" -> btSppRecv(id, arguments)
            "bt_spp_disconnect" -> btSppDisconnect(id, arguments)
            else -> JsonRpc.textToolResult(id, "unknown tool: $toolName", isError = true)
        }

    // -- Read-only tools (unchanged behaviour) ------------------------------

    private fun getBluetoothStatus(id: JsonElement): JsonObject {
        val status = buildJsonObject {
            put("adapter_present", JsonPrimitive(source.adapterPresent))
            put("enabled", JsonPrimitive(source.adapterPresent && source.enabled))
        }
        return JsonRpc.textToolResult(id, status.toString())
    }

    private fun listBondedDevices(id: JsonElement): JsonObject {
        if (!source.adapterPresent) {
            return err(id, "this device has no bluetooth adapter")
        }
        // A permission problem is a normal, user-fixable outcome: report it
        // in-band (isError) rather than as a JSON-RPC error, and never crash.
        if (!source.hasConnectPermission) {
            return err(id, CONNECT_PERMISSION_MESSAGE)
        }
        val devices = buildJsonObject {
            put(
                "devices",
                buildJsonArray {
                    source.bondedDevices().forEach { device ->
                        add(
                            buildJsonObject {
                                put("name", device.name?.let(::JsonPrimitive) ?: JsonNull)
                                put("type", JsonPrimitive(device.type))
                            },
                        )
                    }
                },
            )
        }
        return JsonRpc.textToolResult(id, devices.toString())
    }

    // -- Scan / pair --------------------------------------------------------

    private fun btScan(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id, needScan = true)?.let { return it }
        val timeout = clampTimeout(intArg(arguments, "timeout_ms"), default = 8000, max = 30_000)
        val result = source.scan(timeout)
        result.error?.let { return err(id, it) }
        val payload = buildJsonObject {
            put(
                "devices",
                buildJsonArray {
                    result.devices.forEach { device ->
                        add(
                            buildJsonObject {
                                put("address", JsonPrimitive(device.address))
                                put("name", device.name?.let(::JsonPrimitive) ?: JsonNull)
                                put("type", JsonPrimitive(device.type))
                                put("rssi", JsonPrimitive(device.rssi))
                            },
                        )
                    }
                },
            )
        }
        return JsonRpc.textToolResult(id, payload.toString())
    }

    private fun btPair(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val address = address(id, arguments) ?: return invalidAddress(id, arguments)
        val timeout = clampTimeout(intArg(arguments, "timeout_ms"), default = 15_000, max = 60_000)
        return mapOp(id, source.pair(address, timeout)) {
            buildJsonObject {
                put("address", JsonPrimitive(address))
                put("bonded", JsonPrimitive(true))
            }
        }
    }

    private fun btUnpair(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val address = address(id, arguments) ?: return invalidAddress(id, arguments)
        return mapOp(id, source.unpair(address)) {
            buildJsonObject {
                put("address", JsonPrimitive(address))
                put("unpaired", JsonPrimitive(true))
            }
        }
    }

    // -- GATT ---------------------------------------------------------------

    private fun btGattConnect(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val address = address(id, arguments) ?: return invalidAddress(id, arguments)
        val timeout = clampTimeout(intArg(arguments, "timeout_ms"), default = 15_000, max = 60_000)
        val result = source.gattConnect(address, timeout)
        result.error?.let { return err(id, it) }
        return JsonRpc.textToolResult(id, gattPayload(address, result.services, connected = true).toString())
    }

    private fun btGattServices(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val address = address(id, arguments) ?: return invalidAddress(id, arguments)
        val result = source.gattServices(address)
        result.error?.let { return err(id, it) }
        return JsonRpc.textToolResult(id, gattPayload(address, result.services, connected = null).toString())
    }

    private fun btGattRead(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val address = address(id, arguments) ?: return invalidAddress(id, arguments)
        val service = stringArg(arguments, "service") ?: return err(id, "missing required argument: service")
        val characteristic = stringArg(arguments, "characteristic")
            ?: return err(id, "missing required argument: characteristic")
        val timeout = clampTimeout(intArg(arguments, "timeout_ms"), default = 10_000, max = 60_000)
        val result = source.gattRead(address, service, characteristic, timeout)
        result.error?.let { return err(id, it) }
        val payload = buildJsonObject {
            put("address", JsonPrimitive(address))
            put("service", JsonPrimitive(service))
            put("characteristic", JsonPrimitive(characteristic))
            put("value_hex", JsonPrimitive(toHex(result.value ?: ByteArray(0))))
        }
        return JsonRpc.textToolResult(id, payload.toString())
    }

    private fun btGattWrite(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val address = address(id, arguments) ?: return invalidAddress(id, arguments)
        val service = stringArg(arguments, "service") ?: return err(id, "missing required argument: service")
        val characteristic = stringArg(arguments, "characteristic")
            ?: return err(id, "missing required argument: characteristic")
        val valueHex = stringArg(arguments, "value_hex") ?: return err(id, "missing required argument: value_hex")
        val bytes = fromHex(valueHex) ?: return err(id, "invalid value_hex: expected an even-length hex string, got \"$valueHex\"")
        val withResponse = boolArg(arguments, "with_response") ?: true
        val timeout = clampTimeout(intArg(arguments, "timeout_ms"), default = 10_000, max = 60_000)
        return mapOp(id, source.gattWrite(address, service, characteristic, bytes, withResponse, timeout)) {
            buildJsonObject {
                put("address", JsonPrimitive(address))
                put("written", JsonPrimitive(true))
                put("bytes", JsonPrimitive(bytes.size))
            }
        }
    }

    private fun btGattDisconnect(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val address = address(id, arguments) ?: return invalidAddress(id, arguments)
        return mapOp(id, source.gattDisconnect(address)) {
            buildJsonObject {
                put("address", JsonPrimitive(address))
                put("disconnected", JsonPrimitive(true))
            }
        }
    }

    // -- Classic SPP --------------------------------------------------------

    private fun btSppConnect(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val address = address(id, arguments) ?: return invalidAddress(id, arguments)
        val uuid = stringArg(arguments, "uuid") ?: SPP_UUID
        val timeout = clampTimeout(intArg(arguments, "timeout_ms"), default = 15_000, max = 60_000)
        return mapOp(id, source.sppConnect(address, uuid, timeout)) {
            buildJsonObject {
                put("address", JsonPrimitive(address))
                put("connected", JsonPrimitive(true))
            }
        }
    }

    private fun btSppSend(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val address = address(id, arguments) ?: return invalidAddress(id, arguments)
        val valueHex = stringArg(arguments, "value_hex")
        val text = stringArg(arguments, "text")
        val bytes = when {
            valueHex != null -> fromHex(valueHex)
                ?: return err(id, "invalid value_hex: expected an even-length hex string, got \"$valueHex\"")
            text != null -> text.toByteArray(Charsets.UTF_8)
            else -> return err(id, "missing bytes to send: provide value_hex (hex string) or text")
        }
        val timeout = clampTimeout(intArg(arguments, "timeout_ms"), default = 5000, max = 60_000)
        return mapOp(id, source.sppSend(address, bytes, timeout)) {
            buildJsonObject {
                put("address", JsonPrimitive(address))
                put("sent", JsonPrimitive(true))
                put("bytes", JsonPrimitive(bytes.size))
            }
        }
    }

    private fun btSppRecv(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val address = address(id, arguments) ?: return invalidAddress(id, arguments)
        val timeout = clampTimeout(intArg(arguments, "timeout_ms"), default = 2000, max = 30_000)
        val maxBytes = intArg(arguments, "max_bytes")?.takeIf { it > 0 } ?: DEFAULT_MAX_RECV_BYTES
        val result = source.sppRecv(address, timeout, maxBytes)
        result.error?.let { return err(id, it) }
        val payload = buildJsonObject {
            put("address", JsonPrimitive(address))
            put("value_hex", JsonPrimitive(toHex(result.value)))
            put("bytes", JsonPrimitive(result.value.size))
        }
        return JsonRpc.textToolResult(id, payload.toString())
    }

    private fun btSppDisconnect(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val address = address(id, arguments) ?: return invalidAddress(id, arguments)
        return mapOp(id, source.sppDisconnect(address)) {
            buildJsonObject {
                put("address", JsonPrimitive(address))
                put("disconnected", JsonPrimitive(true))
            }
        }
    }

    // -- Shared preconditions + mapping -------------------------------------

    /**
     * Uniform preconditions for the control tools; returns an in-band error
     * response when one fails, or null when it is safe to proceed.
     */
    private fun preflight(id: JsonElement, needScan: Boolean = false): JsonObject? {
        if (!source.adapterPresent) {
            return err(id, "this device has no bluetooth adapter")
        }
        if (!source.enabled) {
            return err(id, "bluetooth is turned off: enable bluetooth on this Android device, then retry")
        }
        if (needScan) {
            if (!source.hasScanPermission) return err(id, SCAN_PERMISSION_MESSAGE)
        } else {
            if (!source.hasConnectPermission) return err(id, CONNECT_PERMISSION_MESSAGE)
        }
        return null
    }

    /** Success payload builder wrapper: null error -> success, else in-band error. */
    private fun mapOp(id: JsonElement, result: BtOpResult, payload: () -> JsonObject): JsonObject =
        result.error?.let { err(id, it) } ?: JsonRpc.textToolResult(id, payload().toString())

    private fun gattPayload(address: String, services: List<GattService>, connected: Boolean?): JsonObject =
        buildJsonObject {
            put("address", JsonPrimitive(address))
            if (connected != null) put("connected", JsonPrimitive(connected))
            put(
                "services",
                buildJsonArray {
                    services.forEach { service ->
                        add(
                            buildJsonObject {
                                put("uuid", JsonPrimitive(service.uuid))
                                put(
                                    "characteristics",
                                    buildJsonArray {
                                        service.characteristics.forEach { ch ->
                                            add(
                                                buildJsonObject {
                                                    put("uuid", JsonPrimitive(ch.uuid))
                                                    put(
                                                        "properties",
                                                        buildJsonArray { ch.properties.forEach { add(JsonPrimitive(it)) } },
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }

    private fun err(id: JsonElement, message: String): JsonObject =
        JsonRpc.textToolResult(id, message, isError = true)

    /** Validated, upper-cased device address, or null when missing/malformed. */
    private fun address(id: JsonElement, arguments: JsonObject): String? {
        val raw = stringArg(arguments, "address") ?: return null
        val normalized = raw.trim().uppercase()
        return if (ADDRESS_REGEX.matches(normalized)) normalized else null
    }

    private fun invalidAddress(id: JsonElement, arguments: JsonObject): JsonObject {
        val raw = stringArg(arguments, "address")
        return if (raw == null) {
            err(id, "missing required argument: address")
        } else {
            err(id, "invalid device address: expected \"AA:BB:CC:DD:EE:FF\", got \"$raw\"")
        }
    }

    // -- Tool-descriptor DSL ------------------------------------------------

    private class SchemaBuilder {
        val properties = mutableMapOf<String, JsonObject>()
        val required = mutableListOf<String>()

        fun stringProp(name: String, description: String, required: Boolean = false) =
            prop(name, "string", description, required)

        fun intProp(name: String, description: String, required: Boolean = false) =
            prop(name, "integer", description, required)

        fun boolProp(name: String, description: String, required: Boolean = false) =
            prop(name, "boolean", description, required)

        private fun prop(name: String, type: String, description: String, required: Boolean) {
            properties[name] = buildJsonObject {
                put("type", JsonPrimitive(type))
                put("description", JsonPrimitive(description))
            }
            if (required) this.required += name
        }
    }

    private fun descriptor(
        toolName: String,
        description: String,
        schema: (SchemaBuilder.() -> Unit)? = null,
    ): JsonObject {
        val builder = SchemaBuilder().apply { schema?.invoke(this) }
        return buildJsonObject {
            put("name", JsonPrimitive(toolName))
            put("description", JsonPrimitive(description))
            put(
                "inputSchema",
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "properties",
                        buildJsonObject { builder.properties.forEach { (k, v) -> put(k, v) } },
                    )
                    if (builder.required.isNotEmpty()) {
                        put("required", buildJsonArray { builder.required.forEach { add(JsonPrimitive(it)) } })
                    }
                    put("additionalProperties", JsonPrimitive(false))
                },
            )
        }
    }

    // -- Argument readers ---------------------------------------------------

    private fun stringArg(arguments: JsonObject, key: String): String? =
        (arguments[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun intArg(arguments: JsonObject, key: String): Int? =
        (arguments[key] as? JsonPrimitive)?.let { if (it.isString) null else it.content.toIntOrNull() }

    private fun boolArg(arguments: JsonObject, key: String): Boolean? =
        (arguments[key] as? JsonPrimitive)?.let { if (it.isString) null else it.content.toBooleanStrictOrNull() }

    private fun clampTimeout(value: Int?, default: Int, max: Int): Long {
        val chosen = value?.takeIf { it > 0 } ?: default
        return chosen.coerceIn(1, max).toLong()
    }

    companion object {
        const val NAME = "bluetooth"

        /** Canonical Serial Port Profile UUID. */
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

        private const val DEFAULT_MAX_RECV_BYTES = 4096

        private val ADDRESS_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        private const val CONNECT_PERMISSION_MESSAGE =
            "bluetooth permission not granted: grant the \"Nearby devices\" permission " +
                "to the SealGate tunnel app in Android settings, then retry"

        private const val SCAN_PERMISSION_MESSAGE =
            "bluetooth scan permission not granted: grant the \"Nearby devices\" permission " +
                "(BLUETOOTH_SCAN) to the SealGate tunnel app in Android settings, then retry"

        /** Lowercase hex encoding of [bytes]. */
        fun toHex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(HEX_DIGITS[v ushr 4])
                sb.append(HEX_DIGITS[v and 0x0F])
            }
            return sb.toString()
        }

        /** Decode a hex string to bytes, or null when it is not valid hex. */
        fun fromHex(hex: String): ByteArray? {
            val cleaned = hex.trim()
            if (cleaned.length % 2 != 0) return null
            val out = ByteArray(cleaned.length / 2)
            var i = 0
            while (i < cleaned.length) {
                val hi = Character.digit(cleaned[i], 16)
                val lo = Character.digit(cleaned[i + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i / 2] = ((hi shl 4) or lo).toByte()
                i += 2
            }
            return out
        }

        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
