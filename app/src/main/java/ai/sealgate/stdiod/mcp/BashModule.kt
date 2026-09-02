package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** The aggregate Mobile Bash MCP server: one tool, one transient virtual shell. */
class BashModule(
    runtimeFactory: () -> MobileBashRuntime,
    private val closeCapabilities: () -> Unit = {},
) : BaseMcpModule(), AutoCloseable {
    override val name: String = NAME

    private val runtimeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED, runtimeFactory)
    private val runtime: MobileBashRuntime by runtimeDelegate

    override fun toolDescriptors(): JsonElement = buildJsonArray {
        add(
            buildJsonObject {
                put("name", JsonPrimitive(TOOL_NAME))
                put(
                    "description",
                    JsonPrimitive(
                        "Execute a script in a restricted, in-memory virtual Bash environment on this Android device. " +
                            "Use device, battery, wifi, bluetooth, and usb commands for Android capabilities; run each " +
                            "namespace with --help for discovery. Files last only for the current tunnel run. There is " +
                            "no Android filesystem, process, language-runtime, or network access.",
                    ),
                )
                put(
                    "inputSchema",
                    buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "script",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                        put("description", JsonPrimitive("Bash script to execute."))
                                    },
                                )
                            },
                        )
                        put("required", buildJsonArray { add(JsonPrimitive("script")) })
                        put("additionalProperties", JsonPrimitive(false))
                    },
                )
            },
        )
    }

    override fun callTool(
        id: JsonElement,
        toolName: String,
        arguments: JsonObject,
    ): JsonObject {
        if (toolName != TOOL_NAME) {
            return JsonRpc.textToolResult(id, "unknown tool: $toolName", isError = true)
        }
        val script = JsonRpc.stringParam(arguments, "script")
            ?: return JsonRpc.textToolResult(id, "script must be a string", isError = true)
        if (script.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_BYTES) {
            return JsonRpc.textToolResult(
                id,
                "script exceeds the ${MAX_SCRIPT_BYTES / 1024} KiB input limit",
                isError = true,
            )
        }

        val result = runtime.execute(script)
        val text = buildString {
            if (result.stdout.isNotEmpty()) append(result.stdout)
            if (result.stderr.isNotEmpty()) {
                if (isNotEmpty() && !endsWith('\n')) append('\n')
                append("[stderr]\n")
                append(result.stderr)
            }
            if (result.exitCode != 0) {
                if (isNotEmpty() && !endsWith('\n')) append('\n')
                append("[exit code: ${result.exitCode}]")
            } else if (isEmpty()) {
                append("Command completed successfully.")
            }
        }
        return JsonRpc.textToolResult(id, text, isError = result.exitCode != 0)
    }

    override fun close() {
        if (runtimeDelegate.isInitialized()) runtime.close()
        closeCapabilities()
    }

    companion object {
        // SealGate addresses local daemon modules by the alphanumeric MCP
        // prefix, while the dashboard may show a separate display name.
        const val NAME = "mobilebash"
        const val TOOL_NAME = "run"
        const val MAX_SCRIPT_BYTES = 64 * 1024
    }
}
