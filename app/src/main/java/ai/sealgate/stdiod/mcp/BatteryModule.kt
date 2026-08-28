package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** One reading of the battery, as `get_battery_status` reports it. */
data class BatterySnapshot(
    /** 0-100, or null when the level can't be read. */
    val levelPercent: Int?,
    /** `charging` | `discharging` | `full` | `not_charging` | `unknown`. */
    val state: String,
    /** `ac` | `usb` | `wireless` | `none`. */
    val powerSource: String,
)

/**
 * What `get_battery_status` reports. Behind an interface so the module is
 * testable on the JVM, away from `BatteryManager` and sticky intents.
 */
interface BatterySource {
    fun snapshot(): BatterySnapshot
}

/** Battery hardware module: one read-only tool, no arguments. */
class BatteryModule(private val source: BatterySource) : BaseMcpModule() {

    override val name: String = NAME

    override fun toolDescriptors(): JsonElement = buildJsonArray {
        add(
            buildJsonObject {
                put("name", JsonPrimitive("get_battery_status"))
                put(
                    "description",
                    JsonPrimitive(
                        "Report this Android device's battery level, charging state, and power source.",
                    ),
                )
                put(
                    "inputSchema",
                    buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("properties", buildJsonObject {})
                        put("additionalProperties", JsonPrimitive(false))
                    },
                )
            },
        )
    }

    override fun callTool(id: JsonElement, toolName: String, arguments: JsonObject): JsonObject {
        if (toolName != "get_battery_status") {
            return JsonRpc.textToolResult(id, "unknown tool: $toolName", isError = true)
        }
        val snapshot = source.snapshot()
        val status = buildJsonObject {
            put("level_percent", snapshot.levelPercent?.let(::JsonPrimitive) ?: JsonNull)
            put("state", JsonPrimitive(snapshot.state))
            put("power_source", JsonPrimitive(snapshot.powerSource))
        }
        return JsonRpc.textToolResult(id, status.toString())
    }

    companion object {
        const val NAME = "battery"
    }
}
