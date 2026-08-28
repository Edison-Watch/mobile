package ai.sealgate.stdiod.tunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Kotlin twin of the stdiod tunnel wire protocol.
 *
 * The source of truth is the vendored JSON Schema at
 * `schema/tunnel-protocol.json` (canonical copy:
 * `crates/stdiod/schema/tunnel-protocol.json` in Edison-Watch/app). The
 * golden fixtures under `schema/golden-frames/` are round-tripped through
 * these types in `GoldenFramesTest`, mirroring the Rust `golden_frames.rs`
 * and Python `test_golden_frames.py` suites, so drift from the other two
 * implementations fails the build here rather than parsing on a device.
 */

/** Version this client speaks and advertises in `client_hello`. */
const val PROTOCOL_VERSION = 2

@Serializable
sealed interface TunnelFrame

@Serializable
@SerialName("client_hello")
data class ClientHello(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("device_id") val deviceId: String,
    val hostname: String,
    val label: String,
    /** Always "android" from this client; the backend accepts a wider enum. */
    val os: String,
    @SerialName("client_version") val clientVersion: String,
    @SerialName("currently_running") val currentlyRunning: List<String> = emptyList(),
) : TunnelFrame

@Serializable
data class DesiredServer(
    @SerialName("server_id") val serverId: String,
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    @SerialName("working_dir") val workingDir: String? = null,
    val enabled: Boolean,
)

@Serializable
@SerialName("server_hello")
data class ServerHello(
    @SerialName("protocol_version") val protocolVersion: Int,
    val servers: List<DesiredServer> = emptyList(),
) : TunnelFrame

@Serializable
@SerialName("desired_state_update")
data class DesiredStateUpdate(
    val added: List<DesiredServer> = emptyList(),
    val updated: List<DesiredServer> = emptyList(),
    val removed: List<String> = emptyList(),
) : TunnelFrame

/**
 * Symmetric MCP JSON-RPC envelope. [frame] is the JSON-RPC body verbatim;
 * the tunnel never inspects it beyond routing on [serverId].
 */
@Serializable
@SerialName("mcp_frame")
data class McpFrame(
    @SerialName("server_id") val serverId: String,
    val frame: JsonObject,
) : TunnelFrame

@Serializable
@SerialName("server_spawn_result")
data class ServerSpawnResult(
    @SerialName("server_id") val serverId: String,
    val ok: Boolean,
    val error: String? = null,
) : TunnelFrame

@Serializable
@SerialName("server_env_update")
data class ServerEnvUpdate(
    @SerialName("server_id") val serverId: String,
    val env: Map<String, String> = emptyMap(),
) : TunnelFrame

@Serializable
@SerialName("server_spec_update")
data class ServerSpecUpdate(
    @SerialName("server_id") val serverId: String,
    val env: Map<String, String>? = null,
    @SerialName("templated_args") val templatedArgs: Map<String, String>? = null,
) : TunnelFrame

@Serializable
@SerialName("tunnel_error")
data class TunnelError(
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("related_jsonrpc_id") val relatedJsonrpcId: JsonRpcId? = null,
    val code: String,
    val message: String,
) : TunnelFrame

@Serializable
@SerialName("ping")
data object Ping : TunnelFrame

@Serializable
@SerialName("pong")
data object Pong : TunnelFrame

/**
 * The wire `Json` for tunnel frames.
 *
 * - `classDiscriminator "type"` matches the schema's tagged union.
 * - `explicitNulls = false` omits absent optionals; peers treat `null` and
 *   absent as equivalent (see the `normalize` step in every golden test).
 * - `encodeDefaults = true` keeps always-required list/map fields (e.g.
 *   `currently_running`) on the wire even when empty, as the schema requires.
 * - `ignoreUnknownKeys = false` (the default): an unknown frame type or field
 *   is a protocol error we want loud in tests; the client catches parse
 *   failures at the socket boundary instead of crashing the tunnel.
 */
val TunnelJson: Json = Json {
    classDiscriminator = "type"
    explicitNulls = false
    encodeDefaults = true
}

fun parseTunnelFrame(text: String): TunnelFrame =
    TunnelJson.decodeFromString(TunnelFrame.serializer(), text)

fun encodeTunnelFrame(frame: TunnelFrame): String =
    TunnelJson.encodeToString(TunnelFrame.serializer(), frame)
