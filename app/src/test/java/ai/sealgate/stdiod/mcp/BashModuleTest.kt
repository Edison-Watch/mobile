package ai.sealgate.stdiod.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
