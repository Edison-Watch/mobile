package ai.sealgate.stdiod.tunnel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round-trip every golden frame fixture through [TunnelFrame], mirroring the
 * Rust `golden_frames.rs` and Python `test_golden_frames.py` suites over the
 * same shared bytes in `schema/golden-frames/`. Nulls are normalized away
 * before comparing, matching the wire rule that optional fields may arrive
 * either as `null` or absent.
 */
class GoldenFramesTest {

    // Unit tests run with the module dir (app/) as the working directory;
    // walk up from there so the test doesn't care which level Gradle picked.
    private val goldenDir: File = run {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "schema/golden-frames")
            if (candidate.isDirectory) return@run candidate
            dir = dir.parentFile
        }
        error("schema/golden-frames not found above ${System.getProperty("user.dir")}")
    }

    private fun fixtures(): List<Pair<String, JsonObject>> {
        val files = goldenDir.listFiles { f -> f.extension == "json" }
        checkNotNull(files) { "cannot list ${goldenDir.absolutePath}" }
        check(files.isNotEmpty()) { "no fixtures in ${goldenDir.absolutePath}" }
        return files.sortedBy { it.name }.map { file ->
            file.name to (TunnelJson.parseToJsonElement(file.readText()) as JsonObject)
        }
    }

    private fun normalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.filterValues { it != kotlinx.serialization.json.JsonNull }
                .mapValues { (_, v) -> normalize(v) },
        )
        is JsonArray -> JsonArray(value.map(::normalize))
        else -> value
    }

    @Test
    fun everyFixtureRoundTrips() {
        for ((name, raw) in fixtures()) {
            val frame = try {
                TunnelJson.decodeFromJsonElement(TunnelFrame.serializer(), raw)
            } catch (e: IllegalArgumentException) {
                throw AssertionError("$name does not parse as TunnelFrame", e)
            }
            val reserialized = TunnelJson.encodeToJsonElement(TunnelFrame.serializer(), frame)
            assertEquals(
                "$name drifted through a round trip",
                normalize(raw),
                normalize(reserialized),
            )
        }
    }

    /**
     * Every frame type this client implements has at least one fixture, so a
     * new variant cannot land without shared golden bytes. Mirrors the
     * missing-variant check in the Rust suite.
     */
    @Test
    fun everyVariantHasAFixture(): Unit {
        val implemented = setOf(
            "client_hello",
            "server_hello",
            "desired_state_update",
            "mcp_frame",
            "server_spawn_result",
            "server_env_update",
            "server_spec_update",
            "tunnel_error",
            "ping",
            "pong",
        )
        val covered = fixtures()
            .map { (name, raw) ->
                val tag = (raw["type"] as? JsonPrimitive)?.content
                checkNotNull(tag) { "$name has no string `type` discriminator" }
            }
            .toSet()
        val missing = implemented - covered
        assertTrue("TunnelFrame variants with no golden fixture: $missing", missing.isEmpty())
    }

    @Test
    fun androidClientHelloParsesWithAndroidOs() {
        val raw = File(goldenDir, "client_hello_android.json").readText()
        val frame = parseTunnelFrame(raw)
        val hello = frame as ClientHello
        assertEquals("android", hello.os)
        assertEquals(PROTOCOL_VERSION, hello.protocolVersion)
    }

    @Test
    fun mcpFrameBodyIsPreservedVerbatim() {
        val raw = File(goldenDir, "mcp_frame_request.json").readText()
        val frame = parseTunnelFrame(raw) as McpFrame
        assertEquals("filesystem", frame.serverId)
        assertEquals("tools/call", frame.frame["method"]?.jsonPrimitive?.content)
        assertEquals(
            "read_file",
            frame.frame["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )
    }
}
