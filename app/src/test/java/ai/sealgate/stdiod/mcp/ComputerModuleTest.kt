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
import org.junit.Assert.assertTrue
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

    @Test
    fun nonPrimitiveCoordinatesReturnAnInBandError() {
        val response = ComputerModule(FakeComputerSource()).handle(
            request("computer_tap", """{"x":{},"y":20}"""),
        )!!["result"]!!.jsonObject

        assertTrue(response["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(response["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content.contains("x is required"))
    }

    @Test
    fun nonPrimitiveOptionalDurationUsesTheDocumentedDefault() {
        val source = FakeComputerSource()
        val response = ComputerModule(source).handle(
            request("computer_tap", """{"x":10,"y":20,"duration_ms":[]}"""),
        )!!["result"]!!.jsonObject

        assertFalse(response["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(80, source.tapDurationMillis)
    }

    @Test
    fun routerKeepsOnlyTheLatestPendingSupplement() {
        val source = FakeComputerSource().apply {
            screenshotData = "a".repeat((MobileCommandRouter.MAX_PENDING_SUPPLEMENT_BYTES / 2 + 1024).toInt())
        }
        val router = MobileCommandRouter(listOf(ComputerModule(source)))
        val request = """{"namespace":"computer","args":["observe"]}"""

        val first = Json.parseToJsonElement(router.executeJson(request)).jsonObject
        val second = Json.parseToJsonElement(router.executeJson(request)).jsonObject

        assertEquals(0, first["exitCode"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, second["exitCode"]!!.jsonPrimitive.content.toInt())
        val firstToken = first["supplementToken"]!!.jsonPrimitive.content
        val secondToken = second["supplementToken"]!!.jsonPrimitive.content
        val retained = router.takeSupplements(listOf(firstToken, secondToken)).single()
        assertEquals("obs_2", retained.structuredContent!!["observationId"]!!.jsonPrimitive.content)
        router.clearSupplements()
    }

    @Test
    fun repeatedComputerStatusDoesNotAccumulateOrReplaceTheLatestObservation() {
        val router = MobileCommandRouter(listOf(ComputerModule(FakeComputerSource())))

        val observation = router.execute("computer", listOf("observe"))
        val statuses = List(65) { router.execute("computer", listOf("status")) }

        assertEquals(0, observation.exitCode)
        assertTrue(statuses.all { it.exitCode == 0 })
        assertTrue(statuses.all { it.supplementToken == null })
        val supplement = router.takeSupplements(listOf(observation.supplementToken!!)).single()
        assertEquals("image", supplement.content.single()["type"]!!.jsonPrimitive.content)
        assertEquals("obs_1", supplement.structuredContent!!["observationId"]!!.jsonPrimitive.content)
    }

    @Test
    fun failedComputerReplacementPreservesThePreviousObservation() {
        val source = FakeComputerSource().apply { screenshotData = "a".repeat(256 * 1024) }
        val camera = object : CameraSource {
            private val result = CameraOperationResult(
                payload = buildJsonObject { put("lens", JsonPrimitive("back")) },
                photo = CameraPhoto("a".repeat(3 * 1024 * 1024), "image/jpeg"),
            )
            override fun status() = result
            override fun list() = result
            override fun snap(options: CameraSnapOptions) = result
        }
        val router = MobileCommandRouter(listOf(CameraModule(camera), ComputerModule(source)))
        val cameraResult = router.execute("camera", listOf("snap"))
        val firstObservation = router.execute("computer", listOf("observe"))
        source.screenshotData = "b".repeat(2 * 1024 * 1024)

        val failedReplacement = Json.parseToJsonElement(
            router.executeJson("""{"namespace":"computer","args":["observe"]}"""),
        ).jsonObject

        assertEquals(1, failedReplacement["exitCode"]!!.jsonPrimitive.content.toInt())
        val retained = router.takeSupplements(
            listOf(cameraResult.supplementToken!!, firstObservation.supplementToken!!),
        )
        assertEquals(2, retained.size)
        assertEquals("obs_1", retained.last().structuredContent!!["observationId"]!!.jsonPrimitive.content)
    }

    private class FakeComputerSource : ComputerSource {
        var nodeId = ""
        var text = ""
        var tapDurationMillis = 0
        var screenshotData = "aGVsbG8="
        var observationNumber = 0

        private fun result() = ComputerOperationResult(
            payload = buildJsonObject {
                put("observationId", JsonPrimitive("obs_${++observationNumber}"))
                put("accessibilityTree", buildJsonObject { put("nodes", kotlinx.serialization.json.buildJsonArray {}) })
            },
            screenshot = ComputerScreenshot(screenshotData, "image/jpeg"),
        )

        override fun status() = ComputerOperationResult(
            payload = buildJsonObject { put("enabled", JsonPrimitive(true)) },
        )
        override fun observe() = result()
        override fun click(nodeId: String): ComputerOperationResult = result().also { this.nodeId = nodeId }
        override fun setText(nodeId: String, text: String): ComputerOperationResult = result().also {
            this.nodeId = nodeId
            this.text = text
        }
        override fun tap(x: Int, y: Int, durationMillis: Int) = result().also {
            tapDurationMillis = durationMillis
        }
        override fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Int) = result()
        override fun globalAction(action: String) = result()
        override fun openApp(packageName: String) = result()
    }

    private fun request(tool: String, arguments: String): JsonObject = Json.parseToJsonElement(
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
    ).jsonObject
}
