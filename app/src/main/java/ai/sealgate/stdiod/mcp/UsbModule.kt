package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** One USB device attached over USB-OTG, as `usb_list_devices` reports it. */
data class UsbDeviceInfo(
    /** Stable id, e.g. `/dev/bus/usb/001/002` (UsbDevice.deviceName). */
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    /** Human-readable strings; may be null without permission or if absent. */
    val manufacturer: String?,
    val product: String?,
    val serial: String?,
    /** USB device class code (bDeviceClass). */
    val deviceClass: Int,
    val interfaceCount: Int,
    /** Whether the app already holds per-device runtime permission. */
    val hasPermission: Boolean,
)

/** One endpoint of a claimed interface, as `usb_open` reports it. */
data class UsbEndpointInfo(
    /** Endpoint address (bEndpointAddress); top bit set means direction IN. */
    val address: Int,
    /** `in` | `out`. */
    val direction: String,
    /** `bulk` | `interrupt` | `control` | `iso`. */
    val type: String,
    val maxPacketSize: Int,
)

/** Result of `usb_open`. On failure [error] is set and [endpoints] is empty. */
data class UsbOpenResult(
    val endpoints: List<UsbEndpointInfo> = emptyList(),
    val error: String? = null,
)

/**
 * Result of a bulk/control transfer. [value] holds the bytes read on an IN
 * transfer (null on OUT); [bytesTransferred] is the count either way. On failure
 * [error] carries the reason and the payload fields are unset.
 */
data class UsbTransferResult(
    val bytesTransferred: Int = 0,
    val value: ByteArray? = null,
    val error: String? = null,
)

/**
 * Generic success/failure result for actions carrying no payload (close). A null
 * [error] means success.
 */
data class UsbOpResult(val error: String? = null)

/**
 * Result of `usb_request_permission`. USB host permission is granted per-device
 * via a system dialog, so a fresh request only reports that it was [requested]
 * (the async grant lands later); an already-permitted device reports [granted].
 */
data class UsbPermissionResult(
    val requested: Boolean = false,
    val granted: Boolean = false,
    val error: String? = null,
)

/**
 * What the USB tools reach hardware through. Behind an interface so the module
 * is testable on the JVM, away from `UsbManager`, per-device permission dialogs
 * and blocking transfers. Open `UsbDeviceConnection`s (and the claimed
 * interface) are held by [UsbDeviceInfo.deviceName] inside the source across
 * tool calls, so `usb_open` -> transfers -> `usb_close` reference one session.
 */
interface UsbSource {
    /** Whether the device advertises USB host (OTG) support at all. */
    val hostSupported: Boolean

    /** Attached devices; degrades gracefully (null strings) without permission. */
    fun listDevices(): List<UsbDeviceInfo>

    /**
     * Trigger the per-device runtime-permission dialog for [deviceName]. Returns
     * immediately: the grant is async and user-driven. If already permitted,
     * reports `granted = true` without showing a dialog.
     */
    fun requestPermission(deviceName: String): UsbPermissionResult

    /**
     * Open a connection to [deviceName] and claim its interface at
     * [interfaceIndex] (force = true). Caches the session. Returns the claimed
     * interface's endpoints. Errors when the device is unknown, permission is
     * missing, or the open/claim fails.
     */
    fun open(deviceName: String, interfaceIndex: Int): UsbOpenResult

    /**
     * Bulk transfer on the endpoint with [endpointAddress] of the claimed
     * interface. When the endpoint is OUT, [payload] is written; when IN,
     * [length] bytes are read into a fresh buffer. Errors when the device is not
     * open, the endpoint is unknown, or the transfer fails / times out
     * (negative return).
     */
    fun bulkTransfer(
        deviceName: String,
        endpointAddress: Int,
        payload: ByteArray?,
        length: Int,
        timeoutMs: Int,
    ): UsbTransferResult

    /**
     * Control transfer on endpoint 0. Direction is implied by the top bit of
     * [requestType]: IN reads up to [length] bytes, OUT writes [payload]. Errors
     * when the device is not open or the transfer fails / times out.
     */
    fun controlTransfer(
        deviceName: String,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        payload: ByteArray?,
        length: Int,
        timeoutMs: Int,
    ): UsbTransferResult

    /** Release the interface and close the connection to [deviceName]; evict it.
     *  Idempotent: closing a device that is not open still succeeds. */
    fun close(deviceName: String): UsbOpResult
}

/**
 * USB host (USB-OTG) control module. Enumerates devices plugged into the phone's
 * USB port and drives them with raw bulk/control transfers - the protocol-
 * agnostic primitives an agent can layer CDC-ACM/FTDI serial, HID, or any
 * vendor protocol on top of. A USB-OTG adapter is required, and most devices
 * need per-device runtime permission approved on the phone (usb_request_permission)
 * before any I/O.
 *
 * The module owns arg parsing, hex codec, direction inference and JSON shaping
 * (all JVM-testable); the [UsbSource] owns `UsbManager` access and holds live
 * connections by device name.
 */
class UsbModule(private val source: UsbSource) : BaseMcpModule() {

    override val name: String = NAME

    override fun toolDescriptors(): JsonElement = buildJsonArray {
        add(
            descriptor(
                "usb_list_devices",
                "List USB devices attached to this Android phone over USB-OTG. Returns each device's stable device_name (use it as the id in other usb_* tools), vendor_id/product_id, string descriptors (manufacturer/product/serial, which may be null without permission), device_class, interface_count and has_permission. A USB-OTG adapter is required; devices show has_permission=false until usb_request_permission is approved on the phone.",
            ),
        )
        add(
            descriptor(
                "usb_request_permission",
                "Request the per-device runtime permission needed to talk to a USB device. Shows a system dialog on the phone. Returns immediately with requested=true (the grant is async and user-driven) or granted=true if already permitted. After approving on-device, re-check via usb_list_devices has_permission or just retry the I/O tool.",
            ) {
                stringProp("device_name", "Device id from usb_list_devices, e.g. \"/dev/bus/usb/001/002\".", required = true)
            },
        )
        add(
            descriptor(
                "usb_open",
                "Open a USB device and claim one of its interfaces, holding the connection for later usb_bulk_transfer / usb_control_transfer. Returns the claimed interface's endpoints (address, direction, type, max_packet_size). Errors in-band if permission is missing (call usb_request_permission first) or the open/claim fails.",
            ) {
                stringProp("device_name", "Device id from usb_list_devices.", required = true)
                intProp("interface_index", "Which interface to claim (default 0).")
            },
        )
        add(
            descriptor(
                "usb_bulk_transfer",
                "Perform a bulk transfer on an endpoint of a claimed interface. Direction is taken from the endpoint address (top bit set = IN). OUT: provide value_hex (the bytes to write); returns bytes_transferred. IN: provide length (bytes to read); returns value_hex and bytes_transferred. The device must be open (usb_open) first.",
            ) {
                stringProp("device_name", "Device id of an opened device.", required = true)
                intProp("endpoint_address", "Endpoint address (bEndpointAddress) from usb_open, e.g. 129 (0x81) for IN.", required = true)
                stringProp("value_hex", "Bytes to write, as a hex string (e.g. \"01ff\"). Required for an OUT endpoint.")
                intProp("length", "Maximum bytes to read for an IN endpoint (default 64).")
                intProp("timeout_ms", "Transfer timeout in ms (default 1000).")
            },
        )
        add(
            descriptor(
                "usb_control_transfer",
                "Perform a control transfer on endpoint 0 - the generic device-control primitive (SET/GET descriptor, class/vendor requests, CDC line coding, etc.). Direction is implied by request_type's top bit: IN (0x80 set) reads up to length bytes and returns value_hex; OUT writes value_hex. The device must be open (usb_open) first.",
            ) {
                stringProp("device_name", "Device id of an opened device.", required = true)
                intProp("request_type", "bmRequestType byte; top bit (0x80) set means an IN (device-to-host) transfer.", required = true)
                intProp("request", "bRequest byte.", required = true)
                intProp("value", "wValue field.", required = true)
                intProp("index", "wIndex field.", required = true)
                stringProp("value_hex", "Bytes to write, as a hex string. Used for an OUT (host-to-device) transfer.")
                intProp("length", "Maximum bytes to read for an IN transfer (default 64).")
                intProp("timeout_ms", "Transfer timeout in ms (default 1000).")
            },
        )
        add(
            descriptor(
                "usb_close",
                "Release the claimed interface and close the held connection to a USB device. Idempotent.",
            ) {
                stringProp("device_name", "Device id of an opened device.", required = true)
            },
        )
    }

    override fun callTool(id: JsonElement, toolName: String, arguments: JsonObject): JsonObject =
        when (toolName) {
            "usb_list_devices" -> usbListDevices(id)
            "usb_request_permission" -> usbRequestPermission(id, arguments)
            "usb_open" -> usbOpen(id, arguments)
            "usb_bulk_transfer" -> usbBulkTransfer(id, arguments)
            "usb_control_transfer" -> usbControlTransfer(id, arguments)
            "usb_close" -> usbClose(id, arguments)
            else -> err(id, "unknown tool: $toolName")
        }

    // -- Tools --------------------------------------------------------------

    private fun usbListDevices(id: JsonElement): JsonObject {
        // No host support just means nothing enumerates; report an empty list
        // rather than an error so the agent can still probe capability.
        val devices = if (source.hostSupported) source.listDevices() else emptyList()
        val payload = buildJsonObject {
            put("host_supported", JsonPrimitive(source.hostSupported))
            put(
                "devices",
                buildJsonArray {
                    devices.forEach { device ->
                        add(
                            buildJsonObject {
                                put("device_name", JsonPrimitive(device.deviceName))
                                put("vendor_id", JsonPrimitive(device.vendorId))
                                put("product_id", JsonPrimitive(device.productId))
                                put("manufacturer", device.manufacturer?.let(::JsonPrimitive) ?: JsonNull)
                                put("product", device.product?.let(::JsonPrimitive) ?: JsonNull)
                                put("serial", device.serial?.let(::JsonPrimitive) ?: JsonNull)
                                put("device_class", JsonPrimitive(device.deviceClass))
                                put("interface_count", JsonPrimitive(device.interfaceCount))
                                put("has_permission", JsonPrimitive(device.hasPermission))
                            },
                        )
                    }
                },
            )
        }
        return JsonRpc.textToolResult(id, payload.toString())
    }

    private fun usbRequestPermission(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val deviceName = stringArg(arguments, "device_name") ?: return missing(id, "device_name")
        val result = source.requestPermission(deviceName)
        result.error?.let { return err(id, it) }
        val payload = buildJsonObject {
            put("device_name", JsonPrimitive(deviceName))
            if (result.granted) {
                put("granted", JsonPrimitive(true))
            } else {
                put("requested", JsonPrimitive(true))
            }
        }
        return JsonRpc.textToolResult(id, payload.toString())
    }

    private fun usbOpen(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val deviceName = stringArg(arguments, "device_name") ?: return missing(id, "device_name")
        val interfaceIndex = intArg(arguments, "interface_index")?.takeIf { it >= 0 } ?: 0
        val result = source.open(deviceName, interfaceIndex)
        result.error?.let { return err(id, it) }
        val payload = buildJsonObject {
            put("device_name", JsonPrimitive(deviceName))
            put("interface_index", JsonPrimitive(interfaceIndex))
            put(
                "endpoints",
                buildJsonArray {
                    result.endpoints.forEach { ep ->
                        add(
                            buildJsonObject {
                                put("address", JsonPrimitive(ep.address))
                                put("direction", JsonPrimitive(ep.direction))
                                put("type", JsonPrimitive(ep.type))
                                put("max_packet_size", JsonPrimitive(ep.maxPacketSize))
                            },
                        )
                    }
                },
            )
        }
        return JsonRpc.textToolResult(id, payload.toString())
    }

    private fun usbBulkTransfer(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val deviceName = stringArg(arguments, "device_name") ?: return missing(id, "device_name")
        val endpointAddress = intArg(arguments, "endpoint_address") ?: return missing(id, "endpoint_address")
        val timeout = clampTimeout(intArg(arguments, "timeout_ms"))
        // Direction is carried by the endpoint address's top bit; no need to ask
        // the source what kind of endpoint this is before parsing args.
        val isIn = endpointAddress and USB_DIR_IN != 0
        val payload: ByteArray?
        val length: Int
        if (isIn) {
            payload = null
            length = intArg(arguments, "length")?.takeIf { it > 0 } ?: DEFAULT_READ_LENGTH
        } else {
            val valueHex = stringArg(arguments, "value_hex")
                ?: return err(id, "missing required argument: value_hex (required to write to an OUT endpoint)")
            payload = fromHex(valueHex) ?: return invalidHex(id, valueHex)
            length = payload.size
        }
        val result = source.bulkTransfer(deviceName, endpointAddress, payload, length, timeout)
        result.error?.let { return err(id, it) }
        return JsonRpc.textToolResult(id, transferPayload(deviceName, endpointAddress, isIn, result).toString())
    }

    private fun usbControlTransfer(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val deviceName = stringArg(arguments, "device_name") ?: return missing(id, "device_name")
        val requestType = intArg(arguments, "request_type") ?: return missing(id, "request_type")
        val request = intArg(arguments, "request") ?: return missing(id, "request")
        val value = intArg(arguments, "value") ?: return missing(id, "value")
        val index = intArg(arguments, "index") ?: return missing(id, "index")
        val timeout = clampTimeout(intArg(arguments, "timeout_ms"))
        val isIn = requestType and USB_DIR_IN != 0
        val payload: ByteArray?
        val length: Int
        if (isIn) {
            payload = null
            length = intArg(arguments, "length")?.takeIf { it > 0 } ?: DEFAULT_READ_LENGTH
        } else {
            // OUT control transfers commonly carry no data stage (e.g. a bare
            // vendor request); treat an absent value_hex as an empty payload.
            val valueHex = stringArg(arguments, "value_hex")
            payload = if (valueHex == null) ByteArray(0) else fromHex(valueHex) ?: return invalidHex(id, valueHex)
            length = payload.size
        }
        val result = source.controlTransfer(deviceName, requestType, request, value, index, payload, length, timeout)
        result.error?.let { return err(id, it) }
        val base = transferPayload(deviceName, endpointAddress = null, isIn = isIn, result = result)
        val payloadJson = buildJsonObject {
            put("device_name", JsonPrimitive(deviceName))
            put("request_type", JsonPrimitive(requestType))
            base.forEach { (k, v) -> if (k != "device_name") put(k, v) }
        }
        return JsonRpc.textToolResult(id, payloadJson.toString())
    }

    private fun usbClose(id: JsonElement, arguments: JsonObject): JsonObject {
        preflight(id)?.let { return it }
        val deviceName = stringArg(arguments, "device_name") ?: return missing(id, "device_name")
        val result = source.close(deviceName)
        result.error?.let { return err(id, it) }
        val payload = buildJsonObject {
            put("device_name", JsonPrimitive(deviceName))
            put("closed", JsonPrimitive(true))
        }
        return JsonRpc.textToolResult(id, payload.toString())
    }

    // -- Shared -------------------------------------------------------------

    /** Precondition for the I/O tools: this phone must support USB host mode. */
    private fun preflight(id: JsonElement): JsonObject? {
        if (!source.hostSupported) {
            return err(id, "this device does not support USB host (OTG) mode: a phone with USB-OTG and an OTG adapter is required")
        }
        return null
    }

    private fun transferPayload(
        deviceName: String,
        endpointAddress: Int?,
        isIn: Boolean,
        result: UsbTransferResult,
    ): JsonObject = buildJsonObject {
        put("device_name", JsonPrimitive(deviceName))
        if (endpointAddress != null) put("endpoint_address", JsonPrimitive(endpointAddress))
        put("bytes_transferred", JsonPrimitive(result.bytesTransferred))
        if (isIn) put("value_hex", JsonPrimitive(toHex(result.value ?: ByteArray(0))))
    }

    private fun err(id: JsonElement, message: String): JsonObject =
        JsonRpc.textToolResult(id, message, isError = true)

    private fun missing(id: JsonElement, key: String): JsonObject =
        err(id, "missing required argument: $key")

    private fun invalidHex(id: JsonElement, hex: String): JsonObject =
        err(id, "invalid value_hex: expected an even-length hex string, got \"$hex\"")

    // -- Tool-descriptor DSL ------------------------------------------------

    private class SchemaBuilder {
        val properties = mutableMapOf<String, JsonObject>()
        val required = mutableListOf<String>()

        fun stringProp(name: String, description: String, required: Boolean = false) =
            prop(name, "string", description, required)

        fun intProp(name: String, description: String, required: Boolean = false) =
            prop(name, "integer", description, required)

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

    private fun clampTimeout(value: Int?): Int {
        val chosen = value?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_MS
        return chosen.coerceIn(1, MAX_TIMEOUT_MS)
    }

    companion object {
        const val NAME = "usb"

        /** USB direction bit (bEndpointAddress / bmRequestType top bit): set = IN. */
        const val USB_DIR_IN = 0x80

        private const val DEFAULT_READ_LENGTH = 64
        private const val DEFAULT_TIMEOUT_MS = 1000
        private const val MAX_TIMEOUT_MS = 60_000

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
