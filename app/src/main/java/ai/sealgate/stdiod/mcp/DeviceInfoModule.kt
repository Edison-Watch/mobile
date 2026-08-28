package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * What `get_device_info` reports. Behind an interface so the module is
 * testable on the JVM, where `android.os.Build` is stubbed.
 */
interface DeviceInfoSource {
    val manufacturer: String
    val model: String
    val osVersion: String
    val sdkInt: Int
}

/** The walking-skeleton hardware module: one tool, no arguments. */
class DeviceInfoModule(private val source: DeviceInfoSource) : BaseMcpModule() {

    override val name: String = NAME

    override fun toolDescriptors(): JsonElement = buildJsonArray {
        add(
            buildJsonObject {
                put("name", JsonPrimitive("get_device_info"))
                put(
                    "description",
                    JsonPrimitive(
                        "Report this Android device's manufacturer, model, and OS version.",
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
        if (toolName != "get_device_info") {
            return JsonRpc.textToolResult(id, "unknown tool: $toolName", isError = true)
        }
        val info = buildJsonObject {
            put("manufacturer", JsonPrimitive(source.manufacturer))
            put("model", JsonPrimitive(source.model))
            put("os", JsonPrimitive("android"))
            put("os_version", JsonPrimitive(source.osVersion))
            put("sdk_int", JsonPrimitive(source.sdkInt))
        }
        return JsonRpc.textToolResult(id, info.toString())
    }

    companion object {
        const val NAME = "deviceinfo"
    }
}
