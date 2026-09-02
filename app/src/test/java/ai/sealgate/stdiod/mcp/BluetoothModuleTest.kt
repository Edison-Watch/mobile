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
    override val hasScanPermission: Boolean = true,
    private val bonded: List<BondedDevice> = emptyList(),
) : BluetoothControlSource {
    override fun bondedDevices() = bonded

    // The read-only tools never reach these; stub them for the interface.
    override fun scan(timeoutMs: Long) = ScanResult()
    override fun pair(address: String, timeoutMs: Long) = BtOpResult()
    override fun unpair(address: String) = BtOpResult()
    override fun gattConnect(address: String, timeoutMs: Long) = GattServicesResult()
    override fun gattServices(address: String) = GattServicesResult()
    override fun gattRead(address: String, service: String, characteristic: String, timeoutMs: Long) = GattReadResult()
    override fun gattWrite(
        address: String,
        service: String,
        characteristic: String,
        value: ByteArray,
        withResponse: Boolean,
        timeoutMs: Long,
    ) = BtOpResult()
    override fun gattDisconnect(address: String) = BtOpResult()
    override fun gattRequestMtu(address: String, mtu: Int, timeoutMs: Long) = GattMtuResult()
    override fun gattSubscribe(address: String, service: String, characteristic: String, mode: String, timeoutMs: Long) =
        GattSubscribeResult()
    override fun gattNotificationsPoll(
        address: String,
        service: String,
        characteristic: String,
        maxEvents: Int,
        idleTimeoutMs: Long,
        maxBytes: Int,
        decode: String,
    ) = GattNotificationsResult()
    override fun gattUnsubscribe(address: String, service: String, characteristic: String) = BtOpResult()
    override fun gattWriteWait(
        address: String,
        txService: String,
        txCharacteristic: String,
        value: ByteArray,
        rxService: String,
        rxCharacteristic: String,
        withResponse: Boolean,
        timeoutMs: Long,
        idleTimeoutMs: Long,
        maxBytes: Int,
        decode: String,
    ) = GattWriteWaitResult()
    override fun sppConnect(address: String, uuid: String, timeoutMs: Long) = BtOpResult()
    override fun sppSend(address: String, value: ByteArray, timeoutMs: Long) = BtOpResult()
    override fun sppRecv(address: String, timeoutMs: Long, maxBytes: Int) = SppRecvResult()
    override fun sppDisconnect(address: String) = BtOpResult()
}

class BluetoothModuleTest {

    private fun rpc(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun call(source: BluetoothControlSource, toolName: String): JsonObject {
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
    fun toolsListAdvertisesAllTools() {
        val module = BluetoothModule(FakeBluetooth())
        val response = module.handle(rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))!!
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(
            listOf(
                "get_bluetooth_status",
                "list_bonded_devices",
                "bt_scan",
                "bt_pair",
                "bt_unpair",
                "bt_gatt_connect",
                "bt_gatt_services",
                "bt_gatt_read",
                "bt_gatt_write",
                "bt_gatt_write_sequence",
                "bt_gatt_request_mtu",
                "bt_gatt_subscribe",
                "bt_gatt_notifications_poll",
                "bt_gatt_unsubscribe",
                "bt_gatt_write_wait",
                "bt_gatt_disconnect",
                "bt_spp_connect",
                "bt_spp_send",
                "bt_spp_recv",
                "bt_spp_disconnect",
            ),
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
