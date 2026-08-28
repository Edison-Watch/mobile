package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private object FakeDevice : DeviceInfoSource {
    override val manufacturer = "Google"
    override val model = "Pixel 9"
    override val osVersion = "15"
    override val sdkInt = 35
}

class DeviceInfoModuleTest {

    private val module = DeviceInfoModule(FakeDevice)

    private fun rpc(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    @Test
    fun initializeEchoesTheRequestedProtocolVersion() {
        val response = module.handle(
            rpc(
                """{"jsonrpc":"2.0","id":1,"method":"initialize",
                   "params":{"protocolVersion":"2025-03-26","capabilities":{}}}""",
            ),
        )!!
        val result = response["result"]!!.jsonObject
        assertEquals("2025-03-26", result["protocolVersion"]?.jsonPrimitive?.content)
        assertEquals("deviceinfo", result["serverInfo"]!!.jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun initializedNotificationGetsNoReply() {
        assertNull(module.handle(rpc("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")))
    }

    @Test
    fun toolsListAdvertisesGetDeviceInfo() {
        val response = module.handle(rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))!!
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("get_device_info", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun getDeviceInfoReportsTheDevice() {
        val response = module.handle(
            rpc(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call",
                   "params":{"name":"get_device_info","arguments":{}}}""",
            ),
        )!!
        val result = response["result"]!!.jsonObject
        assertFalse(result["isError"]?.jsonPrimitive?.content.toBoolean())
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        val info = Json.parseToJsonElement(text).jsonObject
        assertEquals("Google", info["manufacturer"]?.jsonPrimitive?.content)
        assertEquals("Pixel 9", info["model"]?.jsonPrimitive?.content)
        assertEquals("android", info["os"]?.jsonPrimitive?.content)
        assertEquals("15", info["os_version"]?.jsonPrimitive?.content)
        assertEquals(35, info["sdk_int"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun unknownToolIsAnInBandToolError() {
        val response = module.handle(
            rpc(
                """{"jsonrpc":"2.0","id":4,"method":"tools/call",
                   "params":{"name":"reboot","arguments":{}}}""",
            ),
        )!!
        val result = response["result"]!!.jsonObject
        assertTrue(result["isError"]?.jsonPrimitive?.content.toBoolean())
    }

    @Test
    fun unknownMethodIsAJsonRpcError() {
        val response = module.handle(rpc("""{"jsonrpc":"2.0","id":5,"method":"resources/list"}"""))!!
        val error = response["error"]!!.jsonObject
        assertEquals(JsonRpc.METHOD_NOT_FOUND, error["code"]?.jsonPrimitive?.content?.toInt())
    }
}
