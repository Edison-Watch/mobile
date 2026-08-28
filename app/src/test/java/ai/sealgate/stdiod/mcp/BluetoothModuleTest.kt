package ai.sealgate.stdiod.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeBluetooth(
    override val adapterPresent: Boolean = true,
    override val enabled: Boolean = true,
    override val hasConnectPermission: Boolean = true,
    private val bonded: List<BondedDevice> = emptyList(),
) : BluetoothSource {
    override fun bondedDevices() = bonded
}

class BluetoothModuleTest {

    private fun rpc(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun call(source: BluetoothSource, toolName: String): JsonObject {
        val response = BluetoothModule(source).handle(
            rpc(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call",
                   "params":{"name":"$toolName","arguments":{}}}""",
            ),
        )!!
        return response["result"]!!.jsonObject
    }

    private fun textOf(result: JsonObject): String =
        result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!

    @Test
    fun toolsListAdvertisesBothTools() {
        val module = BluetoothModule(FakeBluetooth())
        val response = module.handle(rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))!!
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(
            listOf("get_bluetooth_status", "list_bonded_devices"),
            tools.map { it.jsonObject["name"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun getBluetoothStatusReportsTheAdapter() {
        val result = call(FakeBluetooth(adapterPresent = true, enabled = true), "get_bluetooth_status")
        assertFalse(result["isError"]?.jsonPrimitive?.content.toBoolean())
        val status = Json.parseToJsonElement(textOf(result)).jsonObject
        assertEquals(true, status["adapter_present"]?.jsonPrimitive?.content.toBoolean())
        assertEquals(true, status["enabled"]?.jsonPrimitive?.content.toBoolean())
    }

    @Test
    fun getBluetoothStatusWorksWithoutConnectPermission() {
        val result = call(FakeBluetooth(hasConnectPermission = false), "get_bluetooth_status")
        assertFalse(result["isError"]?.jsonPrimitive?.content.toBoolean())
    }

    @Test
    fun listBondedDevicesReportsNameAndType() {
        val result = call(
            FakeBluetooth(
                bonded = listOf(
                    BondedDevice(name = "Pixel Buds", type = "le"),
                    BondedDevice(name = null, type = "classic"),
                ),
            ),
            "list_bonded_devices",
        )
        assertFalse(result["isError"]?.jsonPrimitive?.content.toBoolean())
        val devices = Json.parseToJsonElement(textOf(result)).jsonObject["devices"]!!.jsonArray
        assertEquals(2, devices.size)
        assertEquals("Pixel Buds", devices[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("le", devices[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, devices[1].jsonObject["name"])
        assertEquals("classic", devices[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun listBondedDevicesWithoutPermissionIsAnInBandToolError() {
        val response = BluetoothModule(FakeBluetooth(hasConnectPermission = false)).handle(
            rpc(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call",
                   "params":{"name":"list_bonded_devices","arguments":{}}}""",
            ),
        )!!
        // A missing permission is an in-band tool result, never a JSON-RPC error.
        assertNull(response["error"])
        val result = response["result"]!!.jsonObject
        assertTrue(result["isError"]?.jsonPrimitive?.content.toBoolean())
        assertTrue(textOf(result).contains("Nearby devices"))
    }

    @Test
    fun listBondedDevicesWithoutAnAdapterIsAnInBandToolError() {
        val result = call(FakeBluetooth(adapterPresent = false), "list_bonded_devices")
        assertTrue(result["isError"]?.jsonPrimitive?.content.toBoolean())
    }
}
