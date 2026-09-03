package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** One captured still photo, carried as a native MCP image block (never stdout). */
data class CameraPhoto(
    val dataBase64: String,
    val mimeType: String,
)

/** One camera result. `snap` returns the photo in [photo]; failures carry [error]. */
data class CameraOperationResult(
    val payload: JsonObject,
    val photo: CameraPhoto? = null,
    val error: String? = null,
)

/** Validated `camera snap` arguments. Bounds keep captures inside the 4 MiB typed result. */
data class CameraSnapOptions(
    val lens: String,
    val flash: String,
    val zoom: Double?,
    val width: Int?,
    val height: Int?,
    val quality: Int?,
)

/** Android is behind this interface so selectors, validation, and MCP results are JVM-testable. */
interface CameraSource {
    fun status(): CameraOperationResult
    fun list(): CameraOperationResult
    fun snap(options: CameraSnapOptions): CameraOperationResult
}

/** Still-photo camera capture, exposed only through Mobile Bash (`camera ...`). */
class CameraModule(private val source: CameraSource) : BaseMcpModule() {
    override val name: String = NAME

    override fun toolDescriptors(): JsonElement = buildJsonArray {
        add(descriptor("camera_status", "Report whether camera capture is enabled, permitted, and available."))
        add(descriptor("camera_list", "List the front and back cameras with flash and resolution details."))
        add(
            descriptor(
                "camera_snap",
                "Capture a still photo and return it as a native MCP image with JSON metadata.",
                properties = mapOf(
                    "lens" to stringProp("front | back. Defaults to back."),
                    "flash" to stringProp("off | on | auto. Defaults to auto."),
                    "zoom" to numberProp("Digital zoom factor, 1.0 (no zoom) to 8.0. Defaults to 1.0."),
                    "width" to intProp("Requested capture width in pixels, 64 to 4096. Defaults to a bounded size."),
                    "height" to intProp("Requested capture height in pixels, 64 to 4096. Defaults to a bounded size."),
                    "quality" to intProp("JPEG quality, 1 to 100. Defaults to 85."),
                ),
            ),
        )
    }

    override fun callTool(id: JsonElement, toolName: String, arguments: JsonObject): JsonObject {
        when (toolName) {
            "camera_status" -> return done(id, source.status())
            "camera_list" -> return done(id, source.list())
            "camera_snap" -> {
                val options = snapOptions(arguments)
                    ?: return JsonRpc.textToolResult(id, snapOptionsError(arguments), isError = true)
                return done(id, source.snap(options))
            }
            else -> return JsonRpc.textToolResult(id, "unknown tool: $toolName", isError = true)
        }
    }

    private fun done(id: JsonElement, result: CameraOperationResult): JsonObject {
        val photo = result.photo
        if (photo == null) {
            return JsonRpc.textToolResult(id, result.payload.toString(), isError = result.error != null)
        }
        val content = buildList {
            add(JsonRpc.textContent(result.payload.toString()))
            add(JsonRpc.imageContent(photo.dataBase64, photo.mimeType))
        }
        return JsonRpc.toolResult(
            id = id,
            content = content,
            structuredContent = result.payload,
            isError = result.error != null,
        )
    }

    private fun snapOptions(arguments: JsonObject): CameraSnapOptions? {
        val lens = arguments["lens"]?.let(::stringContent) ?: "back"
        if (lens != "front" && lens != "back") return null
        val flash = arguments["flash"]?.let(::stringContent) ?: "auto"
        if (flash != "off" && flash != "on" && flash != "auto") return null
        val zoom = arguments["zoom"]?.let(::doubleContent)
        if (arguments.containsKey("zoom") && zoom == null) return null
        if (zoom != null && (zoom < 1.0 || zoom > MAX_ZOOM)) return null
        val width = arguments["width"]?.let(::intContent)
        if (arguments.containsKey("width") && width == null) return null
        if (width != null && (width < MIN_DIMENSION || width > MAX_DIMENSION)) return null
        val height = arguments["height"]?.let(::intContent)
        if (arguments.containsKey("height") && height == null) return null
        if (height != null && (height < MIN_DIMENSION || height > MAX_DIMENSION)) return null
        val quality = arguments["quality"]?.let(::intContent)
        if (arguments.containsKey("quality") && quality == null) return null
        if (quality != null && (quality < MIN_QUALITY || quality > MAX_QUALITY)) return null
        return CameraSnapOptions(lens, flash, zoom, width, height, quality)
    }

    private fun snapOptionsError(arguments: JsonObject): String {
        val lens = arguments["lens"]?.let(::stringContent)
        if (lens != null && lens != "front" && lens != "back") return "lens must be front or back"
        val flash = arguments["flash"]?.let(::stringContent)
        if (flash != null && flash != "off" && flash != "on" && flash != "auto") {
            return "flash must be off, on, or auto"
        }
        if (arguments.containsKey("zoom")) {
            val zoom = arguments["zoom"]?.let(::doubleContent)
            if (zoom == null || zoom < 1.0 || zoom > MAX_ZOOM) return "zoom must be a number from 1.0 to 8.0"
        }
        for (key in listOf("width", "height")) {
            if (arguments.containsKey(key)) {
                val value = arguments[key]?.let(::intContent)
                if (value == null || value < MIN_DIMENSION || value > MAX_DIMENSION) {
                    return "width and height must be integers from 64 to 4096"
                }
            }
        }
        if (arguments.containsKey("quality")) {
            val quality = arguments["quality"]?.let(::intContent)
            if (quality == null || quality < MIN_QUALITY || quality > MAX_QUALITY) {
                return "quality must be an integer from 1 to 100"
            }
        }
        return "invalid camera_snap arguments"
    }

    private fun stringContent(element: JsonElement): String? =
        (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun doubleContent(element: JsonElement): Double? =
        (element as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun intContent(element: JsonElement): Int? =
        (element as? JsonPrimitive)?.content?.toIntOrNull()

    private fun descriptor(
        name: String,
        description: String,
        properties: Map<String, JsonObject> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("description", JsonPrimitive(description))
        put(
            "inputSchema",
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(properties))
                put("additionalProperties", JsonPrimitive(false))
            },
        )
    }

    private fun stringProp(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
    }

    private fun numberProp(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("number"))
        put("description", JsonPrimitive(description))
    }

    private fun intProp(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("description", JsonPrimitive(description))
    }

    companion object {
        const val NAME = "camera"
        private const val MAX_ZOOM = 8.0
        private const val MIN_DIMENSION = 64
        private const val MAX_DIMENSION = 4096
        private const val MIN_QUALITY = 1
        private const val MAX_QUALITY = 100
    }
}
