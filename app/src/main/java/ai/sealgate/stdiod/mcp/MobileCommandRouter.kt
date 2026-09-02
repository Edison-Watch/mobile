package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Result contract consumed by a just-bash custom command. */
data class ShellCommandResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
) {
    fun toJson(): String = buildJsonObject {
        put("stdout", JsonPrimitive(stdout))
        put("stderr", JsonPrimitive(stderr))
        put("exitCode", JsonPrimitive(exitCode))
    }.toString()
}

/**
 * Maps ergonomic CLI commands onto the existing MCP modules. The modules remain
 * the single source of truth for validation, permissions, Android calls and JSON
 * output; Mobile Bash changes discovery and argument presentation.
 */
class MobileCommandRouter(modules: List<BaseMcpModule>) {
    private val modulesByName = modules.associateBy(BaseMcpModule::name)
    private val descriptorsByTool = modules.flatMap { module ->
        module.bridgeToolDescriptors().jsonArray.map { descriptor ->
            descriptor.jsonObject["name"]!!.jsonPrimitive.content to descriptor.jsonObject
        }
    }.toMap()

    init {
        val mappedTools = SPECS.map(CommandSpec::tool).toSet()
        val unmappedTools = descriptorsByTool.keys - mappedTools
        require(unmappedTools.isEmpty()) {
            "Android tools missing Bash CLI mappings: ${unmappedTools.sorted()}"
        }
    }

    fun executeJson(requestJson: String): String = try {
        val request = BashJson.parseToJsonElement(requestJson).jsonObject
        val namespace = request["namespace"]?.jsonPrimitive?.content.orEmpty()
        val args = request["args"]?.jsonArray?.map(JsonElement::jsonPrimitive)?.map { it.content }.orEmpty()
        execute(namespace, args).toJson()
    } catch (error: Exception) {
        ShellCommandResult(stderr = "mobile command bridge: ${error.message ?: "invalid request"}\n", exitCode = 1).toJson()
    }

    fun execute(namespace: String, args: List<String>): ShellCommandResult {
        val candidates = SPECS.filter { it.namespace == namespace }
        if (candidates.isEmpty()) return fail("unknown Android command namespace: $namespace")
        if (args.isEmpty() || args == listOf("help") || args == listOf("--help") || args == listOf("-h")) {
            return ShellCommandResult(stdout = namespaceHelp(namespace, candidates))
        }

        val spec = candidates
            .filter { args.size >= it.path.size && args.take(it.path.size) == it.path }
            .maxByOrNull { it.path.size }
            ?: return fail("$namespace: unknown command '${args.first()}'\n${namespaceHelp(namespace, candidates)}")
        val remaining = args.drop(spec.path.size)
        if (remaining == listOf("--help") || remaining == listOf("-h")) {
            return ShellCommandResult(stdout = commandHelp(spec))
        }

        val descriptor = descriptorsByTool[spec.tool]
            ?: return fail("$namespace: command is unavailable in this app build: ${spec.path.joinToString(" ")}")
        val parsed = parseArguments(spec, descriptor, remaining)
        if (parsed.error != null) return fail("$namespace ${spec.path.joinToString(" ")}: ${parsed.error}\n")

        val module = modulesByName[spec.module]
            ?: return fail("$namespace: Android capability '${spec.module}' is unavailable")
        val response = module.handle(
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(1))
                put("method", JsonPrimitive("tools/call"))
                put(
                    "params",
                    buildJsonObject {
                        put("name", JsonPrimitive(spec.tool))
                        put("arguments", parsed.arguments)
                    },
                )
            },
        ) ?: return fail("$namespace: Android capability returned no response")

        val result = response["result"]?.jsonObject
        if (result == null) {
            val message = response["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: "Android capability returned an invalid response"
            return fail("$namespace: $message")
        }
        val isError = result["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        val rawText = result["content"]?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.content
            .orEmpty()
        val text = if (isError) rawText.useCliNames() else rawText
        return if (isError) {
            ShellCommandResult(stderr = text.ensureTrailingNewline(), exitCode = 1)
        } else {
            ShellCommandResult(stdout = text.ensureTrailingNewline())
        }
    }

    private fun parseArguments(
        spec: CommandSpec,
        descriptor: JsonObject,
        tokens: List<String>,
    ): ParsedArguments {
        val schema = descriptor["inputSchema"]?.jsonObject ?: JsonObject(emptyMap())
        val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val values = linkedMapOf<String, JsonElement>()
        val positionals = mutableListOf<String>()
        var optionsEnded = false
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (!optionsEnded && token == "--") {
                optionsEnded = true
                index++
                continue
            }
            if (!optionsEnded && (token == "--help" || token == "-h")) {
                return ParsedArguments(error = "--help must be used by itself\n${commandHelp(spec)}")
            }
            if (!optionsEnded && token.startsWith("--")) {
                val assignment = token.removePrefix("--")
                val explicitValue = assignment.substringAfter('=', missingDelimiterValue = "").takeIf { '=' in assignment }
                var cliName = assignment.substringBefore('=')
                if (cliName in setOf("without-response", "no-response")) {
                    if (explicitValue != null) {
                        return ParsedArguments(error = "--$cliName does not take a value")
                    }
                    if (properties["with_response"] == null) {
                        return ParsedArguments(error = "unknown option --$cliName")
                    }
                    values["with_response"] = JsonPrimitive(false)
                    index++
                    continue
                }
                val negated = cliName.startsWith("no-")
                if (negated) cliName = cliName.removePrefix("no-")
                val key = cliName.replace('-', '_')
                val property = properties[key]?.jsonObject
                    ?: return ParsedArguments(error = "unknown option --$cliName")
                val type = property["type"]?.jsonPrimitive?.content ?: "string"
                if (negated && type != "boolean") return ParsedArguments(error = "--no-$cliName is only valid for boolean options")
                val raw = when {
                    negated -> "false"
                    explicitValue != null -> explicitValue
                    type == "boolean" && tokens.getOrNull(index + 1) !in listOf("true", "false") -> "true"
                    else -> tokens.getOrNull(++index) ?: return ParsedArguments(error = "option --$cliName requires a value")
                }
                val converted = convertValue(raw, type)
                    ?: return ParsedArguments(error = "invalid $type value for --$cliName: $raw")
                values[key] = converted
            } else {
                positionals += token
            }
            index++
        }

        if (positionals.size > spec.positionals.size) {
            return ParsedArguments(error = "unexpected positional argument: ${positionals[spec.positionals.size]}")
        }
        positionals.forEachIndexed { positionalIndex, raw ->
            val key = spec.positionals[positionalIndex]
            if (key in values) return ParsedArguments(error = "$key was provided both positionally and as an option")
            val type = properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content ?: "string"
            values[key] = convertValue(raw, type)
                ?: return ParsedArguments(error = "invalid $type value for $key: $raw")
        }
        return ParsedArguments(arguments = JsonObject(values))
    }

    private fun convertValue(raw: String, type: String): JsonPrimitive? = when (type) {
        "integer" -> raw.toLongOrNull()?.let(::JsonPrimitive)
        "boolean" -> raw.toBooleanStrictOrNull()?.let(::JsonPrimitive)
        else -> JsonPrimitive(raw)
    }

    private fun namespaceHelp(namespace: String, candidates: List<CommandSpec>): String = buildString {
        append("Usage: $namespace <command> [options]\n\nCommands:\n")
        candidates.sortedBy { it.path.joinToString(" ") }.forEach { spec ->
            val description = descriptorsByTool[spec.tool]?.get("description")?.jsonPrimitive?.content.orEmpty().useCliNames()
            append("  ").append(spec.path.joinToString(" ").padEnd(28)).append(description).append('\n')
        }
        append("\nRun '$namespace <command> --help' for arguments.\n")
    }

    private fun commandHelp(spec: CommandSpec): String {
        val descriptor = descriptorsByTool[spec.tool] ?: return "Command unavailable.\n"
        val schema = descriptor["inputSchema"]?.jsonObject
        val properties = schema?.get("properties")?.jsonObject ?: JsonObject(emptyMap())
        val required = schema?.get("required")?.let { it as? JsonArray }
            ?.map { it.jsonPrimitive.content }
            ?.toSet()
            .orEmpty()
        return buildString {
            append("Usage: ").append(spec.namespace).append(' ').append(spec.path.joinToString(" "))
            spec.positionals.forEach { append(" [").append(it.replace('_', '-')).append(']') }
            if (properties.isNotEmpty()) append(" [options]")
            append("\n\n").append(descriptor["description"]?.jsonPrimitive?.content.orEmpty().useCliNames()).append("\n")
            if (properties.isNotEmpty()) {
                append("\nOptions:\n")
                properties.forEach { (name, value) ->
                    val property = value.jsonObject
                    val type = property["type"]?.jsonPrimitive?.content ?: "string"
                    val suffix = if (type == "boolean") "" else " <$type>"
                    val marker = if (name in required) " (required)" else ""
                    append("  --").append(name.replace('_', '-')).append(suffix).append(marker).append('\n')
                    property["description"]?.jsonPrimitive?.content?.let {
                        append("      ").append(it.useCliNames()).append('\n')
                    }
                }
            }
        }
    }

    private data class ParsedArguments(
        val arguments: JsonObject = JsonObject(emptyMap()),
        val error: String? = null,
    )

    private data class CommandSpec(
        val namespace: String,
        val path: List<String>,
        val module: String,
        val tool: String,
        val positionals: List<String> = emptyList(),
    )

    private fun fail(message: String): ShellCommandResult =
        ShellCommandResult(stderr = message.ensureTrailingNewline(), exitCode = 1)

    private fun String.ensureTrailingNewline(): String = if (isEmpty() || endsWith('\n')) this else "$this\n"

    private fun String.useCliNames(): String = CLI_BY_TOOL.entries
        .sortedByDescending { it.key.length }
        .fold(this) { text, (tool, cli) -> text.replace(tool, cli) }

    companion object {
        private val BashJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        private fun spec(
            namespace: String,
            path: String,
            module: String,
            tool: String,
            vararg positionals: String,
        ) = CommandSpec(namespace, path.split(' '), module, tool, positionals.toList())

        private val SPECS = listOf(
            spec("device", "info", "deviceinfo", "get_device_info"),
            spec("battery", "status", "battery", "get_battery_status"),
            spec("wifi", "status", "wifi", "get_wifi_status"),
            spec("bluetooth", "status", "bluetooth", "get_bluetooth_status"),
            spec("bluetooth", "bonded", "bluetooth", "list_bonded_devices"),
            spec("bluetooth", "scan", "bluetooth", "bt_scan"),
            spec("bluetooth", "pair", "bluetooth", "bt_pair", "address"),
            spec("bluetooth", "unpair", "bluetooth", "bt_unpair", "address"),
            spec("bluetooth", "gatt connect", "bluetooth", "bt_gatt_connect", "address"),
            spec("bluetooth", "gatt services", "bluetooth", "bt_gatt_services", "address"),
            spec("bluetooth", "gatt read", "bluetooth", "bt_gatt_read", "address", "service", "characteristic"),
            spec("bluetooth", "gatt write", "bluetooth", "bt_gatt_write", "address", "service", "characteristic", "value_hex"),
            spec("bluetooth", "gatt send", "bluetooth", "bt_gatt_write", "value_hex"),
            spec("bluetooth", "gatt write-sequence", "bluetooth", "bt_gatt_write_sequence", "sequence_json"),
            spec("bluetooth", "gatt request-mtu", "bluetooth", "bt_gatt_request_mtu", "address"),
            spec("bluetooth", "gatt subscribe", "bluetooth", "bt_gatt_subscribe", "address", "service", "characteristic"),
            spec("bluetooth", "gatt notifications-poll", "bluetooth", "bt_gatt_notifications_poll", "address", "service", "characteristic"),
            spec("bluetooth", "gatt unsubscribe", "bluetooth", "bt_gatt_unsubscribe", "address", "service", "characteristic"),
            spec("bluetooth", "gatt write-wait", "bluetooth", "bt_gatt_write_wait", "address"),
            spec("bluetooth", "gatt disconnect", "bluetooth", "bt_gatt_disconnect", "address"),
            spec("bluetooth", "spp connect", "bluetooth", "bt_spp_connect", "address"),
            spec("bluetooth", "spp send", "bluetooth", "bt_spp_send", "address", "text"),
            spec("bluetooth", "spp recv", "bluetooth", "bt_spp_recv", "address"),
            spec("bluetooth", "spp disconnect", "bluetooth", "bt_spp_disconnect", "address"),
            spec("usb", "list", "usb", "usb_list_devices"),
            spec("usb", "request-permission", "usb", "usb_request_permission", "device_name"),
            spec("usb", "open", "usb", "usb_open", "device_name"),
            spec("usb", "bulk-transfer", "usb", "usb_bulk_transfer", "device_name"),
            spec("usb", "control-transfer", "usb", "usb_control_transfer", "device_name"),
            spec("usb", "close", "usb", "usb_close", "device_name"),
        )

        private val CLI_BY_TOOL = buildMap {
            SPECS.forEach { spec ->
                if (spec.tool !in this) put(spec.tool, "${spec.namespace} ${spec.path.joinToString(" ")}")
            }
        }
    }
}
