package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private object FakeBattery : BatterySource {
    override fun snapshot() =
        BatterySnapshot(levelPercent = 87, state = "charging", powerSource = "usb")
}

class BatteryModuleTest {

    private val module = BatteryModule(FakeBattery)

    private fun rpc(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    @Test
    fun toolsListAdvertisesGetBatteryStatus() {
        val response = module.handle(rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))!!
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("get_battery_status", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun getBatteryStatusReportsTheBattery() {
        val response = module.handle(
            rpc(
                """{"jsonrpc":"2.0","id":2,"method":"tools/call",
                   "params":{"name":"get_battery_status","arguments":{}}}""",
            ),
        )!!
        val result = response["result"]!!.jsonObject
        assertFalse(result["isError"]?.jsonPrimitive?.content.toBoolean())
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        val status = Json.parseToJsonElement(text).jsonObject
        assertEquals(87, status["level_percent"]?.jsonPrimitive?.content?.toInt())
        assertEquals("charging", status["state"]?.jsonPrimitive?.content)
        assertEquals("usb", status["power_source"]?.jsonPrimitive?.content)
    }

    @Test
    fun unknownToolIsAnInBandToolError() {
        val response = module.handle(
            rpc(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call",
                   "params":{"name":"set_battery_level","arguments":{}}}""",
            ),
        )!!
        val result = response["result"]!!.jsonObject
        assertTrue(result["isError"]?.jsonPrimitive?.content.toBoolean())
    }
}
