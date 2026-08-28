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

/**
 * What the bluetooth tools report. Behind an interface so the module is
 * testable on the JVM, away from `BluetoothAdapter` and permission checks.
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

/** Bluetooth hardware module: two read-only tools, no arguments. */
class BluetoothModule(private val source: BluetoothSource) : BaseMcpModule() {

    override val name: String = NAME

    override fun toolDescriptors(): JsonElement = buildJsonArray {
        add(
            descriptor(
                "get_bluetooth_status",
                "Report whether this Android device has a bluetooth adapter and whether it is enabled.",
            ),
        )
        add(
            descriptor(
                "list_bonded_devices",
                "List the bluetooth devices paired with this Android device (name and type).",
            ),
        )
    }

    private fun descriptor(toolName: String, description: String): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(toolName))
        put("description", JsonPrimitive(description))
        put(
            "inputSchema",
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {})
                put("additionalProperties", JsonPrimitive(false))
            },
        )
    }

    override fun callTool(id: JsonElement, toolName: String, arguments: JsonObject): JsonObject =
        when (toolName) {
            "get_bluetooth_status" -> {
                val status = buildJsonObject {
                    put("adapter_present", JsonPrimitive(source.adapterPresent))
                    put("enabled", JsonPrimitive(source.adapterPresent && source.enabled))
                }
                JsonRpc.textToolResult(id, status.toString())
            }
            "list_bonded_devices" -> listBondedDevices(id)
            else -> JsonRpc.textToolResult(id, "unknown tool: $toolName", isError = true)
        }

    private fun listBondedDevices(id: JsonElement): JsonObject {
        if (!source.adapterPresent) {
            return JsonRpc.textToolResult(id, "this device has no bluetooth adapter", isError = true)
        }
        // A permission problem is a normal, user-fixable outcome: report it
        // in-band (isError) rather than as a JSON-RPC error, and never crash.
        if (!source.hasConnectPermission) {
            return JsonRpc.textToolResult(
                id,
                "bluetooth permission not granted: grant the \"Nearby devices\" permission " +
                    "to the SealGate tunnel app in Android settings, then retry",
                isError = true,
            )
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

    companion object {
        const val NAME = "bluetooth"
    }
}
