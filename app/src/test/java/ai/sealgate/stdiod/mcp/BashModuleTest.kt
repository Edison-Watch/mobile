package ai.sealgate.stdiod.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class BashModuleTest {
    @Test
    fun exposesExactlyOneRunToolAndExecutesScript() {
        val runtime = FakeRuntime(BashExecutionResult("hello\n", "", 0))
        val module = BashModule(runtimeFactory = { runtime })
        assertEquals("mobilebash", module.name)

        val listed = module.handle(request(1, "tools/list"))!!
        val tools = listed["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("run", tools.single().jsonObject["name"]!!.jsonPrimitive.content)

        val called = module.handle(request(2, "tools/call", """{"name":"run","arguments":{"script":"echo hello"}}"""))!!
        val result = called["result"]!!.jsonObject
        assertFalse(result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("hello\n", result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("echo hello", runtime.lastScript)
    }

    @Test
    fun reportsExitCodeAndStderrAsToolError() {
        val module = BashModule(runtimeFactory = { FakeRuntime(BashExecutionResult("partial\n", "bad flag\n", 2)) })
        val called = module.handle(request(3, "tools/call", """{"name":"run","arguments":{"script":"bad"}}"""))!!
        val result = called["result"]!!.jsonObject
        val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(text.contains("partial"))
        assertTrue(text.contains("[stderr]"))
        assertTrue(text.contains("[exit code: 2]"))
    }

    @Test
    fun labelsStderrWhenThereIsNoStdout() {
        val module = BashModule(runtimeFactory = { FakeRuntime(BashExecutionResult("", "only stderr\n", 1)) })
        val called = module.handle(request(30, "tools/call", """{"name":"run","arguments":{"script":"bad"}}"""))!!
        val text = called["result"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

        assertTrue(text.startsWith("[stderr]\nonly stderr\n"))
    }

    @Test
    fun rejectsOversizedScriptsBeforeStartingRuntime() {
        var created = false
        val module = BashModule(runtimeFactory = {
            created = true
            FakeRuntime(BashExecutionResult("", "", 0))
        })
        val script = "x".repeat(BashModule.MAX_SCRIPT_BYTES + 1)
        val args = """{"name":"run","arguments":{"script":${kotlinx.serialization.json.JsonPrimitive(script)}}}"""
        val called = module.handle(request(4, "tools/call", args))!!

        assertTrue(called["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(created)
    }

    @Test
    fun returnsNativeMcpImagesAndStructuredCommandResults() {
        val structured = buildJsonObject { put("observationId", JsonPrimitive("obs_1")) }
        val image = JsonRpc.imageContent("aGVsbG8=", "image/png")
        val runtime = FakeRuntime(
            BashExecutionResult(
                stdout = "observed\n",
                stderr = "",
                exitCode = 0,
                supplements = listOf(MobileCommandSupplement(listOf(image), structured)),
            ),
        )
        val module = BashModule(runtimeFactory = { runtime })

        val called = module.handle(request(5, "tools/call", """{"name":"run","arguments":{"script":"computer observe"}}"""))!!
        val result = called["result"]!!.jsonObject
        val content = result["content"]!!.jsonArray

        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", content[1].jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals(
            "obs_1",
            result["structuredContent"]!!.jsonObject["commandResults"]!!.jsonArray
                .single().jsonObject["observationId"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun omitsTypedAttachmentsThatWouldExceedTheMcpResultBudget() {
        val oversizedImage = JsonRpc.imageContent(
            "a".repeat(BashModule.MAX_MCP_RESULT_BYTES),
            "image/jpeg",
        )
        val runtime = FakeRuntime(
            BashExecutionResult(
                stdout = "observed\n",
                stderr = "",
                exitCode = 0,
                supplements = listOf(
                    MobileCommandSupplement(
                        listOf(oversizedImage),
                        buildJsonObject { put("observationId", JsonPrimitive("obs_too_large")) },
                    ),
                ),
            ),
        )

        val called = BashModule(runtimeFactory = { runtime }).handle(
            request(6, "tools/call", """{"name":"run","arguments":{"script":"computer observe"}}"""),
        )!!
        val result = called["result"]!!.jsonObject
        val content = result["content"]!!.jsonArray

        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1, content.size)
        assertTrue(content.single().jsonObject["text"]!!.jsonPrimitive.content.contains("attachments omitted"))
        assertFalse(result.containsKey("structuredContent"))
    }

    @Test
    fun truncatesJsonEscapedTextToTheSerializedMcpBudget() {
        val escapedOutput = "\u0000".repeat(1024 * 1024)
        val called = BashModule(
            runtimeFactory = { FakeRuntime(BashExecutionResult(escapedOutput, "", 0)) },
        ).handle(request(7, "tools/call", """{"name":"run","arguments":{"script":"escaped"}}"""))!!
        val result = called["result"]!!.jsonObject
        val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(text.endsWith("[output truncated: MCP result exceeds 4 MiB]\n"))
        assertTrue(called.toString().toByteArray(Charsets.UTF_8).size <= BashModule.MAX_MCP_RESULT_BYTES)
    }

    @Test
    fun serializedBudgetIncludesTheRequestId() {
        val requestId = "i".repeat(128 * 1024)
        val module = BashModule(
            runtimeFactory = { FakeRuntime(BashExecutionResult("\u0000".repeat(1024 * 1024), "", 0)) },
        )
        val called = module.handle(
            Json.parseToJsonElement(
                """{"jsonrpc":"2.0","id":${JsonPrimitive(requestId)},"method":"tools/call","params":{"name":"run","arguments":{"script":"escaped"}}}""",
            ).jsonObject,
        )!!

        assertEquals(requestId, called["id"]!!.jsonPrimitive.content)
        assertTrue(called.toString().toByteArray(Charsets.UTF_8).size <= BashModule.MAX_MCP_RESULT_BYTES)
    }

    @Test
    fun truncationPreservesFailureDiagnostics() {
        val called = BashModule(
            runtimeFactory = {
                FakeRuntime(
                    BashExecutionResult(
                        stdout = "\u0000".repeat(1024 * 1024),
                        stderr = "critical failure\n",
                        exitCode = 9,
                    ),
                )
            },
        ).handle(request(8, "tools/call", """{"name":"run","arguments":{"script":"fail"}}"""))!!
        val result = called["result"]!!.jsonObject
        val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(text.contains("[output truncated: MCP result exceeds 4 MiB]"))
        assertTrue(text.endsWith("[stderr]\ncritical failure\n[exit code: 9]"))
        assertTrue(called.toString().toByteArray(Charsets.UTF_8).size <= BashModule.MAX_MCP_RESULT_BYTES)
    }

    @Test
    fun rejectsAnUnrepresentableRequestIdBeforeExecutingTheScript() {
        val runtime = FakeRuntime(BashExecutionResult("should not run", "", 0))
        val message = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive("i".repeat(BashModule.MAX_MCP_RESULT_BYTES)))
            put("method", JsonPrimitive("tools/call"))
            put(
                "params",
                buildJsonObject {
                    put("name", JsonPrimitive("run"))
                    put("arguments", buildJsonObject { put("script", JsonPrimitive("computer global home")) })
                },
            )
        }

        val response = BashModule(runtimeFactory = { runtime }).handle(message)!!

        assertEquals(JsonNull, response["id"])
        assertTrue(response["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("request id is too large"))
        assertEquals(null, runtime.lastScript)
    }

    private class FakeRuntime(private val result: BashExecutionResult) : MobileBashRuntime {
        var lastScript: String? = null
        override fun execute(script: String): BashExecutionResult {
            lastScript = script
            return result
        }
        override fun close() = Unit
    }

    private fun request(id: Int, method: String, params: String? = null) = Json.parseToJsonElement(
        """{"jsonrpc":"2.0","id":$id,"method":"$method"${params?.let { ",\"params\":$it" } ?: ""}}""",
    ).jsonObject
}
