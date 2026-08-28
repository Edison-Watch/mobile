package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * An in-process MCP server built into the app.
 *
 * A phone can't spawn `npx`/`uvx` subprocesses the way the desktop daemon
 * does, so on mobile each "stdio server" is a Kotlin module that answers the
 * MCP JSON-RPC methods directly. The tunnel routes `mcp_frame` bodies here
 * by server name and sends whatever [handle] returns back to the backend.
 */
interface LocalMcpModule {
    /** Stable server name the backend addresses this module by. */
    val name: String

    /**
     * Handle one JSON-RPC message (request or notification). Returns the
     * JSON-RPC response body, or null for notifications (no reply).
     */
    fun handle(message: JsonObject): JsonObject?
}

/** Shared JSON-RPC plumbing for [LocalMcpModule] implementations. */
object JsonRpc {
    const val METHOD_NOT_FOUND = -32601

    fun idOf(message: JsonObject): JsonElement? = message["id"]

    fun methodOf(message: JsonObject): String? =
        (message["method"] as? JsonPrimitive)?.content

    fun result(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
        put("result", result)
    }

    fun error(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", JsonPrimitive(code))
                put("message", JsonPrimitive(message))
            },
        )
    }

    fun paramsOf(message: JsonObject): JsonObject? = message["params"] as? JsonObject

    fun stringParam(params: JsonObject?, key: String): String? =
        params?.get(key)?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }

    /** `params.arguments` of a `tools/call`, or empty. */
    fun argumentsOf(params: JsonObject?): JsonObject =
        (params?.get("arguments") as? JsonObject) ?: buildJsonObject {}

    /** A `tools/call` result wrapping one text content block. */
    fun textToolResult(id: JsonElement, text: String, isError: Boolean = false): JsonObject =
        result(
            id,
            buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(text))
                            },
                        )
                    },
                )
                put("isError", JsonPrimitive(isError))
            },
        )
}

/**
 * Base class handling the MCP lifecycle methods every module answers the
 * same way; subclasses supply the tool surface.
 */
abstract class BaseMcpModule : LocalMcpModule {
    /** `tools` array for `tools/list`. */
    protected abstract fun toolDescriptors(): JsonElement

    /** Handle `tools/call` for [toolName]; return the full JSON-RPC response. */
    protected abstract fun callTool(id: JsonElement, toolName: String, arguments: JsonObject): JsonObject

    override fun handle(message: JsonObject): JsonObject? {
        val method = JsonRpc.methodOf(message) ?: return null
        val id = JsonRpc.idOf(message)
        // Notifications (no id) never get a reply, whatever the method.
        if (id == null || id is JsonNull) {
            return null
        }
        return when (method) {
            "initialize" -> {
                val params = JsonRpc.paramsOf(message)
                // Echo the client's requested protocol version: the backend
                // (FastMCP) treats a matching echo as acceptance.
                val protocolVersion =
                    JsonRpc.stringParam(params, "protocolVersion") ?: "2025-06-18"
                JsonRpc.result(
                    id,
                    buildJsonObject {
                        put("protocolVersion", JsonPrimitive(protocolVersion))
                        put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                        put(
                            "serverInfo",
                            buildJsonObject {
                                put("name", JsonPrimitive(name))
                                put("version", JsonPrimitive("0.1.0"))
                            },
                        )
                    },
                )
            }
            "ping" -> JsonRpc.result(id, buildJsonObject {})
            "tools/list" -> JsonRpc.result(id, buildJsonObject { put("tools", toolDescriptors()) })
            "tools/call" -> {
                val params = JsonRpc.paramsOf(message)
                val toolName = JsonRpc.stringParam(params, "name")
                    ?: return JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "tools/call without a tool name")
                callTool(id, toolName, JsonRpc.argumentsOf(params))
            }
            else -> JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "unknown method: $method")
        }
    }
}
