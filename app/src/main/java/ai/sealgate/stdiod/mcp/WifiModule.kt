package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** One reading of the wifi radio, as `get_wifi_status` reports it. */
data class WifiSnapshot(
    /** Whether the wifi radio is switched on. */
    val enabled: Boolean,
    /** Whether the active network connection goes over wifi. */
    val connected: Boolean,
    /** Current link speed, or null when not connected / not reported. */
    val linkSpeedMbps: Int?,
    /**
     * Network SSID, or null when unobtainable - on modern Android reading
     * the SSID needs location permission and comes back as `<unknown ssid>`
     * without it.
     */
    val ssid: String?,
)

/**
 * What `get_wifi_status` reports. Behind an interface so the module is
 * testable on the JVM, away from `WifiManager`/`ConnectivityManager`.
 */
interface WifiSource {
    fun snapshot(): WifiSnapshot
}

/** Wifi hardware module: one read-only tool, no arguments. */
class WifiModule(private val source: WifiSource) : BaseMcpModule() {

    override val name: String = NAME

    override fun toolDescriptors(): JsonElement = buildJsonArray {
        add(
            buildJsonObject {
                put("name", JsonPrimitive("get_wifi_status"))
                put(
                    "description",
                    JsonPrimitive(
                        "Report whether wifi is enabled and connected on this Android device, " +
                            "with link speed and SSID where obtainable.",
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
        if (toolName != "get_wifi_status") {
            return JsonRpc.textToolResult(id, "unknown tool: $toolName", isError = true)
        }
        val snapshot = source.snapshot()
        val status = buildJsonObject {
            put("enabled", JsonPrimitive(snapshot.enabled))
            put("connected", JsonPrimitive(snapshot.connected))
            put("link_speed_mbps", snapshot.linkSpeedMbps?.let(::JsonPrimitive) ?: JsonNull)
            // The SSID is hidden from apps without location permission; say so
            // in-band instead of failing the whole tool call.
            put(
                "ssid",
                snapshot.ssid?.let(::JsonPrimitive)
                    ?: if (snapshot.connected) {
                        JsonPrimitive("unavailable (needs location permission)")
                    } else {
                        JsonNull
                    },
            )
        }
        return JsonRpc.textToolResult(id, status.toString())
    }

    companion object {
        const val NAME = "wifi"
    }
}
