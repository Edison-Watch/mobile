package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
                            "Use device, battery, wifi, bluetooth, usb, and (in private builds) computer commands for Android capabilities; run each " +
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
        val contentBudget = (MAX_MCP_RESULT_BYTES - MCP_ENVELOPE_RESERVE_BYTES).toLong() -
            id.toString().toByteArray(Charsets.UTF_8).size.toLong()
        if (contentBudget <= serializedTextBytes(OUTPUT_TRUNCATED_NOTICE)) {
            return JsonRpc.error(JsonNull, INVALID_REQUEST, "request id is too large")
        }
        val fittedText = fitTextToResultBudget(
            text = text,
            budget = contentBudget,
            preserveTail = result.exitCode != 0,
        )
        val acceptedSupplements = mutableListOf<MobileCommandSupplement>()
        var resultBytes = serializedTextBytes(fittedText.text)
        var omittedSupplements = 0
        result.supplements.forEach { supplement ->
            val supplementBytes = supplement.serializedBytes()
            if (resultBytes + supplementBytes <= contentBudget) {
                acceptedSupplements += supplement
                resultBytes += supplementBytes
            } else {
                omittedSupplements++
            }
        }
        val finalText = buildString {
            append(fittedText.text)
            if (omittedSupplements > 0) {
                append("\n[computer attachments omitted: $omittedSupplements; MCP result exceeds 4 MiB]\n")
            }
        }
        val typedContent = buildList {
            add(JsonRpc.textContent(finalText))
            acceptedSupplements.flatMapTo(this) { it.content }
        }
        val structured = acceptedSupplements
            .mapNotNull(MobileCommandSupplement::structuredContent)
            .takeIf(List<JsonObject>::isNotEmpty)
            ?.let { commandResults ->
                buildJsonObject {
                    put("exitCode", JsonPrimitive(result.exitCode))
                    put("commandResults", buildJsonArray { commandResults.forEach(::add) })
                }
            }
        return JsonRpc.toolResult(
            id = id,
            content = typedContent,
            structuredContent = structured,
            isError = result.exitCode != 0 || fittedText.truncated || omittedSupplements > 0,
        )
    }

    private fun fitTextToResultBudget(text: String, budget: Long, preserveTail: Boolean): FittedText {
        if (serializedTextBytes(text) <= budget) return FittedText(text, truncated = false)

        var low = 0
        var high = text.length
        while (low < high) {
            val keptCharacters = low + (high - low + 1) / 2
            val candidate = truncatedText(text, keptCharacters, preserveTail)
            if (serializedTextBytes(candidate) <= budget) {
                low = keptCharacters
            } else {
                high = keptCharacters - 1
            }
        }
        return FittedText(truncatedText(text, low, preserveTail), truncated = true)
    }

    private fun truncatedText(text: String, keptCharacters: Int, preserveTail: Boolean): String {
        if (!preserveTail) return safePrefix(text, keptCharacters) + OUTPUT_TRUNCATED_NOTICE
        val prefixCharacters = (keptCharacters + 1) / 2
        val suffixCharacters = keptCharacters / 2
        return safePrefix(text, prefixCharacters) +
            OUTPUT_TRUNCATED_NOTICE +
            safeSuffix(text, text.length - suffixCharacters)
    }

    private fun serializedTextBytes(text: String): Long =
        JsonRpc.textContent(text).toString().toByteArray(Charsets.UTF_8).size.toLong()

    private fun safePrefix(text: String, requestedEnd: Int): String {
        var end = requestedEnd.coerceIn(0, text.length)
        if (
            end in 1 until text.length &&
            text[end - 1].isHighSurrogate() &&
            text[end].isLowSurrogate()
        ) {
            end--
        }
        return text.substring(0, end)
    }

    private fun safeSuffix(text: String, requestedStart: Int): String {
        var start = requestedStart.coerceIn(0, text.length)
        if (
            start in 1 until text.length &&
            text[start - 1].isHighSurrogate() &&
            text[start].isLowSurrogate()
        ) {
            start++
        }
        return text.substring(start)
    }

    private data class FittedText(val text: String, val truncated: Boolean)

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
        const val MAX_MCP_RESULT_BYTES = 4 * 1024 * 1024
        private const val MCP_ENVELOPE_RESERVE_BYTES = 64 * 1024
        private const val INVALID_REQUEST = -32600
        private const val OUTPUT_TRUNCATED_NOTICE = "\n[output truncated: MCP result exceeds 4 MiB]\n"
    }
}
