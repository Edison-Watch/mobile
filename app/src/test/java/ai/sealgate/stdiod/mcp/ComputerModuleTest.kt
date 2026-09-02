package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ComputerModuleTest {
    @Test
    fun observationReturnsTextImageAndStructuredTree() {
        val module = ComputerModule(FakeComputerSource())
        val response = module.handle(request("computer_observe", "{}"))!!["result"]!!.jsonObject
        val content = response["content"]!!.jsonArray

        assertFalse(response["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", content[1].jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals(
            "obs_1",
            response["structuredContent"]!!.jsonObject["observationId"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun routerMapsComputerArgumentsAndRetainsTypedSupplement() {
        val source = FakeComputerSource()
        val router = MobileCommandRouter(listOf(ComputerModule(source)))

        val result = router.execute("computer", listOf("set-text", "obs_1:n3", "hello world"))

        assertEquals(0, result.exitCode)
        assertEquals("obs_1:n3", source.nodeId)
        assertEquals("hello world", source.text)
        val supplement = router.takeSupplements(listOf(result.supplementToken!!)).single()
        assertEquals("image", supplement.content.single()["type"]!!.jsonPrimitive.content)
        assertEquals("obs_1", supplement.structuredContent!!["observationId"]!!.jsonPrimitive.content)
    }

    private class FakeComputerSource : ComputerSource {
        var nodeId = ""
        var text = ""

        private fun result() = ComputerOperationResult(
            payload = buildJsonObject {
                put("observationId", JsonPrimitive("obs_1"))
                put("accessibilityTree", buildJsonObject { put("nodes", kotlinx.serialization.json.buildJsonArray {}) })
            },
            screenshot = ComputerScreenshot("aGVsbG8=", "image/jpeg"),
        )

        override fun status() = result()
        override fun observe() = result()
        override fun click(nodeId: String): ComputerOperationResult = result().also { this.nodeId = nodeId }
        override fun setText(nodeId: String, text: String): ComputerOperationResult = result().also {
            this.nodeId = nodeId
            this.text = text
        }
        override fun tap(x: Int, y: Int, durationMillis: Int) = result()
        override fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Int) = result()
        override fun globalAction(action: String) = result()
        override fun openApp(packageName: String) = result()
    }

    private fun request(tool: String, arguments: String): JsonObject = Json.parseToJsonElement(
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
    ).jsonObject
}
