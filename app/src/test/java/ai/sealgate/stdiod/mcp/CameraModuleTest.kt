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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModuleTest {
    @Test
    fun snapReturnsTextImageAndStructuredMetadata() {
        val module = CameraModule(FakeCameraSource())
        val response = module.handle(request("camera_snap", """{"lens":"back"}"""))!!["result"]!!.jsonObject
        val content = response["content"]!!.jsonArray

        assertFalse(response["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", content[1].jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals(
            "back",
            response["structuredContent"]!!.jsonObject["lens"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun snapDefaultsToBackLensAndAutoFlash() {
        val source = FakeCameraSource()
        CameraModule(source).handle(request("camera_snap", "{}"))

        assertEquals("back", source.options?.lens)
        assertEquals("auto", source.options?.flash)
    }

    @Test
    fun snapParsesNumericOptionsLeniently() {
        val source = FakeCameraSource()
        val response = CameraModule(source).handle(
            request("camera_snap", """{"zoom":2.5,"width":1280,"height":720,"quality":70}"""),
        )!!["result"]!!.jsonObject

        assertFalse(response["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2.5, source.options?.zoom!!, 0.0)
        assertEquals(1280, source.options?.width)
        assertEquals(720, source.options?.height)
        assertEquals(70, source.options?.quality)
    }

    @Test
    fun invalidSnapArgumentsFailInBand() {
        val module = CameraModule(FakeCameraSource())

        val badLens = module.handle(request("camera_snap", """{"lens":"sideways"}"""))!!["result"]!!.jsonObject
        assertTrue(badLens["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(textOf(badLens).contains("lens must be front or back"))

        val badZoom = module.handle(request("camera_snap", """{"zoom":9.0}"""))!!["result"]!!.jsonObject
        assertTrue(badZoom["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(textOf(badZoom).contains("zoom"))

        val badQuality = module.handle(request("camera_snap", """{"quality":101}"""))!!["result"]!!.jsonObject
        assertTrue(badQuality["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(textOf(badQuality).contains("quality"))
    }

    @Test
    fun sourceErrorsStayInBandWithoutAnImageBlock() {
        val module = CameraModule(FakeCameraSource(error = "camera capture is disabled in Mobile Tunnel"))
        val response = module.handle(request("camera_snap", "{}"))!!["result"]!!.jsonObject
        val content = response["content"]!!.jsonArray

        assertTrue(response["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1, content.size)
        assertEquals("text", content.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(response["structuredContent"]?.jsonObject?.get("lens"))
    }

    @Test
    fun routerMapsSnapFlagsAndRetainsImageSupplement() {
        val source = FakeCameraSource()
        val router = MobileCommandRouter(listOf(CameraModule(source)))

        val result = router.execute(
            "camera",
            listOf("snap", "--lens", "front", "--flash", "off", "--zoom", "2.0", "--quality", "70"),
        )

        assertEquals(0, result.exitCode)
        assertEquals("front", source.options?.lens)
        assertEquals("off", source.options?.flash)
        assertEquals(2.0, source.options?.zoom!!, 0.0)
        assertEquals(70, source.options?.quality)
        val supplement = router.takeSupplements(listOf(result.supplementToken!!)).single()
        assertEquals("image", supplement.content.single()["type"]!!.jsonPrimitive.content)
        assertEquals("front", supplement.structuredContent!!["lens"]!!.jsonPrimitive.content)
    }

    @Test
    fun routerExposesCameraHelp() {
        val router = MobileCommandRouter(listOf(CameraModule(FakeCameraSource())))

        assertTrue(router.execute("camera", emptyList()).stdout.contains("snap"))
        assertTrue(router.execute("camera", listOf("snap", "--help")).stdout.contains("--lens"))
    }

    private class FakeCameraSource(val error: String? = null) : CameraSource {
        var options: CameraSnapOptions? = null

        private fun payload(lens: String) = buildJsonObject {
            put("ok", JsonPrimitive(error == null))
            put("lens", JsonPrimitive(lens))
            if (error != null) put("error", JsonPrimitive(error))
        }

        override fun status(): CameraOperationResult = CameraOperationResult(payload("back"))

        override fun list(): CameraOperationResult = CameraOperationResult(payload("back"))

        override fun snap(options: CameraSnapOptions): CameraOperationResult {
            this.options = options
            return if (error == null) {
                CameraOperationResult(
                    payload = payload(options.lens),
                    photo = CameraPhoto("aGVsbG8=", "image/jpeg"),
                )
            } else {
                CameraOperationResult(payload = payload(options.lens), error = error)
            }
        }
    }

    private fun textOf(response: JsonObject): String =
        response["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    private fun request(tool: String, arguments: String): JsonObject = Json.parseToJsonElement(
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
    ).jsonObject
}
