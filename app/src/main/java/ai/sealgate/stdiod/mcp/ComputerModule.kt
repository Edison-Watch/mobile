package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ComputerScreenshot(
    val dataBase64: String,
    val mimeType: String,
)

/** One computer-use result. Actions return the post-action observation in [payload]. */
data class ComputerOperationResult(
    val payload: JsonObject,
    val screenshot: ComputerScreenshot? = null,
    val error: String? = null,
)

/** Android is behind this interface so selectors, CLI mapping, and MCP results are JVM-testable. */
interface ComputerSource {
    fun status(): ComputerOperationResult
    fun observe(): ComputerOperationResult
    fun click(nodeId: String): ComputerOperationResult
    fun setText(nodeId: String, text: String): ComputerOperationResult
    fun tap(x: Int, y: Int, durationMillis: Int): ComputerOperationResult
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Int): ComputerOperationResult
    fun globalAction(action: String): ComputerOperationResult
    fun openApp(packageName: String): ComputerOperationResult
}

/** Cross-app Android UI observation and control, exposed only through Mobile Bash. */
class ComputerModule(private val source: ComputerSource) : BaseMcpModule() {
    override val name: String = NAME

    override fun toolDescriptors(): JsonElement = buildJsonArray {
        add(descriptor("computer_status", "Report whether private computer control and its accessibility service are enabled."))
        add(descriptor("computer_observe", "Capture the current screen and accessibility tree in one observation."))
        add(
            descriptor(
                "computer_click",
                "Click a node from the most recent observation and return a fresh screenshot and accessibility tree.",
                properties = mapOf("node_id" to stringProp("Snapshot-qualified node ID, for example obs_1:n17.")),
                required = listOf("node_id"),
            ),
        )
        add(
            descriptor(
                "computer_set_text",
                "Set editable text on a node from the most recent observation and return a fresh observation.",
                properties = mapOf(
                    "node_id" to stringProp("Snapshot-qualified node ID."),
                    "text" to stringProp("Text to enter."),
                ),
                required = listOf("node_id", "text"),
            ),
        )
        add(
            descriptor(
                "computer_tap",
                "Tap screen coordinates and return a fresh observation.",
                properties = mapOf(
                    "x" to intProp("X coordinate in physical display pixels."),
                    "y" to intProp("Y coordinate in physical display pixels."),
                    "duration_ms" to intProp("Tap duration in milliseconds; defaults to 80."),
                ),
                required = listOf("x", "y"),
            ),
        )
        add(
            descriptor(
                "computer_swipe",
                "Swipe between screen coordinates and return a fresh observation.",
                properties = mapOf(
                    "start_x" to intProp("Starting X coordinate."),
                    "start_y" to intProp("Starting Y coordinate."),
                    "end_x" to intProp("Ending X coordinate."),
                    "end_y" to intProp("Ending Y coordinate."),
                    "duration_ms" to intProp("Gesture duration in milliseconds; defaults to 400."),
                ),
                required = listOf("start_x", "start_y", "end_x", "end_y"),
            ),
        )
        add(
            descriptor(
                "computer_global",
                "Perform an Android global action (back, home, recents, notifications, or quick_settings) and return a fresh observation.",
                properties = mapOf("action" to stringProp("back | home | recents | notifications | quick_settings")),
                required = listOf("action"),
            ),
        )
        add(
            descriptor(
                "computer_open_app",
                "Open an installed app by package name and return a fresh observation.",
                properties = mapOf("package_name" to stringProp("Android application package name.")),
                required = listOf("package_name"),
            ),
        )
    }

    override fun callTool(id: JsonElement, toolName: String, arguments: JsonObject): JsonObject {
        val result = when (toolName) {
            "computer_status" -> source.status()
            "computer_observe" -> source.observe()
            "computer_click" -> source.click(
                stringArg(arguments, "node_id") ?: return missing(id, "node_id"),
            )
            "computer_set_text" -> source.setText(
                stringArg(arguments, "node_id") ?: return missing(id, "node_id"),
                stringArg(arguments, "text") ?: return missing(id, "text"),
            )
            "computer_tap" -> source.tap(
                intArg(arguments, "x") ?: return missing(id, "x"),
                intArg(arguments, "y") ?: return missing(id, "y"),
                intArg(arguments, "duration_ms") ?: DEFAULT_TAP_MILLIS,
            )
            "computer_swipe" -> source.swipe(
                intArg(arguments, "start_x") ?: return missing(id, "start_x"),
                intArg(arguments, "start_y") ?: return missing(id, "start_y"),
                intArg(arguments, "end_x") ?: return missing(id, "end_x"),
                intArg(arguments, "end_y") ?: return missing(id, "end_y"),
                intArg(arguments, "duration_ms") ?: DEFAULT_SWIPE_MILLIS,
            )
            "computer_global" -> source.globalAction(
                stringArg(arguments, "action") ?: return missing(id, "action"),
            )
            "computer_open_app" -> source.openApp(
                stringArg(arguments, "package_name") ?: return missing(id, "package_name"),
            )
            else -> return JsonRpc.textToolResult(id, "unknown tool: $toolName", isError = true)
        }
        val content = buildList {
            add(JsonRpc.textContent(result.payload.toString()))
            result.screenshot?.let { add(JsonRpc.imageContent(it.dataBase64, it.mimeType)) }
        }
        return JsonRpc.toolResult(
            id = id,
            content = content,
            structuredContent = result.payload,
            isError = result.error != null,
        )
    }

    private fun missing(id: JsonElement, key: String): JsonObject =
        JsonRpc.textToolResult(id, "$key is required", isError = true)

    private fun stringArg(arguments: JsonObject, key: String): String? =
        arguments[key]?.let { it as? JsonPrimitive }?.takeIf(JsonPrimitive::isString)?.content

    private fun intArg(arguments: JsonObject, key: String): Int? =
        (arguments[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun descriptor(
        name: String,
        description: String,
        properties: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("description", JsonPrimitive(description))
        put(
            "inputSchema",
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(properties))
                if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                put("additionalProperties", JsonPrimitive(false))
            },
        )
    }

    private fun stringProp(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
    }

    private fun intProp(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("description", JsonPrimitive(description))
    }

    companion object {
        const val NAME = "computer"
        private const val DEFAULT_TAP_MILLIS = 80
        private const val DEFAULT_SWIPE_MILLIS = 400
    }
}
