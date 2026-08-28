package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

private class FakeWifi(private val snapshot: WifiSnapshot) : WifiSource {
    override fun snapshot() = snapshot
}

class WifiModuleTest {

    private fun rpc(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun callGetWifiStatus(source: WifiSource): JsonObject {
        val response = WifiModule(source).handle(
            rpc(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call",
                   "params":{"name":"get_wifi_status","arguments":{}}}""",
            ),
        )!!
        val result = response["result"]!!.jsonObject
        assertFalse(result["isError"]?.jsonPrimitive?.content.toBoolean())
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun toolsListAdvertisesGetWifiStatus() {
        val module = WifiModule(FakeWifi(WifiSnapshot(false, false, null, null)))
        val response = module.handle(rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))!!
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("get_wifi_status", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun getWifiStatusReportsAConnectedNetwork() {
        val status = callGetWifiStatus(
            FakeWifi(
                WifiSnapshot(enabled = true, connected = true, linkSpeedMbps = 433, ssid = "HomeNet"),
            ),
        )
        assertEquals(true, status["enabled"]?.jsonPrimitive?.content.toBoolean())
        assertEquals(true, status["connected"]?.jsonPrimitive?.content.toBoolean())
        assertEquals(433, status["link_speed_mbps"]?.jsonPrimitive?.content?.toInt())
        assertEquals("HomeNet", status["ssid"]?.jsonPrimitive?.content)
    }

    @Test
    fun hiddenSsidIsReportedAsNeedingLocationPermission() {
        val status = callGetWifiStatus(
            FakeWifi(
                WifiSnapshot(enabled = true, connected = true, linkSpeedMbps = 433, ssid = null),
            ),
        )
        assertEquals(
            "unavailable (needs location permission)",
            status["ssid"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun getWifiStatusReportsWifiOff() {
        val status = callGetWifiStatus(
            FakeWifi(
                WifiSnapshot(enabled = false, connected = false, linkSpeedMbps = null, ssid = null),
            ),
        )
        assertEquals(false, status["enabled"]?.jsonPrimitive?.content.toBoolean())
        assertEquals(false, status["connected"]?.jsonPrimitive?.content.toBoolean())
        assertEquals(JsonNull, status["link_speed_mbps"])
        assertEquals(JsonNull, status["ssid"])
    }
}
