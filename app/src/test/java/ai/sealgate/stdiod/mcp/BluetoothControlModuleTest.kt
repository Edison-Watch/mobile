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

/**
 * Configurable fake for the write/control surface. Each control method returns
 * a canned result and records the arguments it was called with, so the tests
 * exercise the module's arg parsing, JSON shaping and error mapping without any
 * Android/hardware.
 */
private class FakeBluetoothControl(
    override val adapterPresent: Boolean = true,
    override val enabled: Boolean = true,
    override val hasConnectPermission: Boolean = true,
    override val hasScanPermission: Boolean = true,
    private val scanResult: ScanResult = ScanResult(),
    private val pairResult: BtOpResult = BtOpResult(),
    private val unpairResult: BtOpResult = BtOpResult(),
    private val gattConnectResult: GattServicesResult = GattServicesResult(),
    private val gattServicesResult: GattServicesResult = GattServicesResult(),
    private val gattReadResult: GattReadResult = GattReadResult(value = ByteArray(0)),
    private val gattWriteResult: BtOpResult = BtOpResult(),
    private val gattDisconnectResult: BtOpResult = BtOpResult(),
    private val sppConnectResult: BtOpResult = BtOpResult(),
    private val sppSendResult: BtOpResult = BtOpResult(),
    private val sppRecvResult: SppRecvResult = SppRecvResult(),
    private val sppDisconnectResult: BtOpResult = BtOpResult(),
) : BluetoothControlSource {

    var lastWriteValue: ByteArray? = null
    var lastWriteWithResponse: Boolean? = null
    var lastSendValue: ByteArray? = null
    var lastScanTimeout: Long? = null

    override fun bondedDevices(): List<BondedDevice> = emptyList()

    override fun scan(timeoutMs: Long): ScanResult {
        lastScanTimeout = timeoutMs
        return scanResult
    }

    override fun pair(address: String, timeoutMs: Long) = pairResult
    override fun unpair(address: String) = unpairResult
    override fun gattConnect(address: String, timeoutMs: Long) = gattConnectResult
    override fun gattServices(address: String) = gattServicesResult
    override fun gattRead(address: String, service: String, characteristic: String, timeoutMs: Long) = gattReadResult

    override fun gattWrite(
        address: String,
        service: String,
        characteristic: String,
        value: ByteArray,
        withResponse: Boolean,
        timeoutMs: Long,
    ): BtOpResult {
        lastWriteValue = value
        lastWriteWithResponse = withResponse
        return gattWriteResult
    }

    override fun gattDisconnect(address: String) = gattDisconnectResult
    override fun gattRequestMtu(address: String, mtu: Int, timeoutMs: Long) = GattMtuResult(mtu = mtu)
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
    override fun sppConnect(address: String, uuid: String, timeoutMs: Long) = sppConnectResult

    override fun sppSend(address: String, value: ByteArray, timeoutMs: Long): BtOpResult {
        lastSendValue = value
        return sppSendResult
    }

    override fun sppRecv(address: String, timeoutMs: Long, maxBytes: Int) = sppRecvResult
    override fun sppDisconnect(address: String) = sppDisconnectResult
}

class BluetoothControlModuleTest {

    private val addr = "AA:BB:CC:DD:EE:FF"

    private fun rpc(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun call(source: BluetoothControlSource, toolName: String, arguments: String = "{}"): JsonObject {
        val response = BluetoothModule(source).handle(
            rpc(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call",
                   "params":{"name":"$toolName","arguments":$arguments}}""",
            ),
        )!!
        // Control preconditions and failures are always in-band, never JSON-RPC errors.
        assertNull(response["error"])
        return response["result"]!!.jsonObject
    }

    private fun textOf(result: JsonObject): String =
        result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!

    private fun isError(result: JsonObject): Boolean =
        result["isError"]?.jsonPrimitive?.content.toBoolean()

    @Test
    fun gattWriteSucceeds() {
        val fake = FakeBluetoothControl(gattWriteResult = BtOpResult())
        val result = call(
            fake,
            "bt_gatt_write",
            """{"address":"$addr","service":"180f","characteristic":"2a19","value_hex":"01ff"}""",
        )
        assertFalse(isError(result))
        // The module decoded the hex to bytes before handing them to the source.
        assertEquals(listOf(0x01, 0xff), fake.lastWriteValue!!.map { it.toInt() and 0xff })
        assertEquals(true, fake.lastWriteWithResponse)
        val payload = Json.parseToJsonElement(textOf(result)).jsonObject
        assertEquals(2, payload["bytes"]?.jsonPrimitive?.content?.toInt())
        assertEquals(true, payload["written"]?.jsonPrimitive?.content.toBoolean())
    }

    @Test
    fun gattWriteWithBadHexIsAnInBandError() {
        val result = call(
            FakeBluetoothControl(),
            "bt_gatt_write",
            """{"address":"$addr","service":"180f","characteristic":"2a19","value_hex":"xyz"}""",
        )
        assertTrue(isError(result))
        assertTrue(textOf(result).contains("value_hex"))
    }

    @Test
    fun gattWriteHonoursWithResponseFalse() {
        val fake = FakeBluetoothControl()
        call(
            fake,
            "bt_gatt_write",
            """{"address":"$addr","service":"180f","characteristic":"2a19","value_hex":"00","with_response":false}""",
        )
        assertEquals(false, fake.lastWriteWithResponse)
    }

    @Test
    fun scanWithoutScanPermissionIsAnInBandError() {
        val response = BluetoothModule(FakeBluetoothControl(hasScanPermission = false)).handle(
            rpc(
                """{"jsonrpc":"2.0","id":7,"method":"tools/call",
                   "params":{"name":"bt_scan","arguments":{}}}""",
            ),
        )!!
        // A missing permission is an in-band tool result, never a JSON-RPC error.
        assertNull(response["error"])
        val result = response["result"]!!.jsonObject
        assertTrue(isError(result))
        assertTrue(textOf(result).contains("scan permission"))
    }

    @Test
    fun scanReportsDiscoveredDevicesAndClampsTimeout() {
        val fake = FakeBluetoothControl(
            scanResult = ScanResult(
                devices = listOf(ScannedDevice(address = addr, name = "Widget", type = "le", rssi = -55)),
            ),
        )
        val result = call(fake, "bt_scan", """{"timeout_ms":999999}""")
        assertFalse(isError(result))
        assertEquals(30_000L, fake.lastScanTimeout)
        val devices = Json.parseToJsonElement(textOf(result)).jsonObject["devices"]!!.jsonArray
        assertEquals(1, devices.size)
        assertEquals(addr, devices[0].jsonObject["address"]?.jsonPrimitive?.content)
        assertEquals("Widget", devices[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals(-55, devices[0].jsonObject["rssi"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun gattReadWhenNotConnectedIsAnInBandError() {
        val fake = FakeBluetoothControl(
            gattReadResult = GattReadResult(error = "not connected: call bt_gatt_connect first for this device"),
        )
        val result = call(
            fake,
            "bt_gatt_read",
            """{"address":"$addr","service":"180f","characteristic":"2a19"}""",
        )
        assertTrue(isError(result))
        assertTrue(textOf(result).contains("not connected"))
    }

    @Test
    fun gattReadReturnsLowercaseHex() {
        val fake = FakeBluetoothControl(
            gattReadResult = GattReadResult(value = byteArrayOf(0x0a, 0xFF.toByte(), 0x10)),
        )
        val result = call(
            fake,
            "bt_gatt_read",
            """{"address":"$addr","service":"180f","characteristic":"2a19"}""",
        )
        assertFalse(isError(result))
        val payload = Json.parseToJsonElement(textOf(result)).jsonObject
        assertEquals("0aff10", payload["value_hex"]?.jsonPrimitive?.content)
    }

    @Test
    fun timeoutFromSourceMapsToInBandError() {
        val fake = FakeBluetoothControl(
            gattConnectResult = GattServicesResult(error = "timed out connecting to $addr"),
        )
        val result = call(fake, "bt_gatt_connect", """{"address":"$addr"}""")
        assertTrue(isError(result))
        assertTrue(textOf(result).contains("timed out"))
    }

    @Test
    fun invalidAddressIsAnInBandError() {
        val result = call(FakeBluetoothControl(), "bt_gatt_connect", """{"address":"nope"}""")
        assertTrue(isError(result))
        assertTrue(textOf(result).contains("invalid device address"))
    }

    @Test
    fun controlToolWithoutConnectPermissionIsAnInBandError() {
        val result = call(
            FakeBluetoothControl(hasConnectPermission = false),
            "bt_pair",
            """{"address":"$addr"}""",
        )
        assertTrue(isError(result))
        assertTrue(textOf(result).contains("Nearby devices"))
    }

    @Test
    fun controlToolWithBluetoothOffIsAnInBandError() {
        val result = call(FakeBluetoothControl(enabled = false), "bt_pair", """{"address":"$addr"}""")
        assertTrue(isError(result))
        assertTrue(textOf(result).contains("turned off"))
    }

    @Test
    fun pairIsIdempotentSuccess() {
        val result = call(FakeBluetoothControl(pairResult = BtOpResult()), "bt_pair", """{"address":"$addr"}""")
        assertFalse(isError(result))
        val payload = Json.parseToJsonElement(textOf(result)).jsonObject
        assertEquals(true, payload["bonded"]?.jsonPrimitive?.content.toBoolean())
    }

    @Test
    fun sppSendAcceptsTextAndRecvReturnsHex() {
        val fake = FakeBluetoothControl(
            sppRecvResult = SppRecvResult(value = byteArrayOf(0x68, 0x69)),
        )
        val sent = call(fake, "bt_spp_send", """{"address":"$addr","text":"AB"}""")
        assertFalse(isError(sent))
        assertEquals(listOf(0x41, 0x42), fake.lastSendValue!!.map { it.toInt() and 0xff })

        val received = call(fake, "bt_spp_recv", """{"address":"$addr"}""")
        assertFalse(isError(received))
        assertEquals("6869", Json.parseToJsonElement(textOf(received)).jsonObject["value_hex"]?.jsonPrimitive?.content)
    }

    @Test
    fun gattConnectListsServices() {
        val fake = FakeBluetoothControl(
            gattConnectResult = GattServicesResult(
                services = listOf(
                    GattService(
                        uuid = "0000180f-0000-1000-8000-00805f9b34fb",
                        characteristics = listOf(
                            GattCharacteristic("00002a19-0000-1000-8000-00805f9b34fb", listOf("read", "notify")),
                        ),
                    ),
                ),
            ),
        )
        val result = call(fake, "bt_gatt_connect", """{"address":"$addr"}""")
        assertFalse(isError(result))
        val payload = Json.parseToJsonElement(textOf(result)).jsonObject
        assertEquals(true, payload["connected"]?.jsonPrimitive?.content.toBoolean())
        val services = payload["services"]!!.jsonArray
        assertEquals(1, services.size)
        val characteristics = services[0].jsonObject["characteristics"]!!.jsonArray
        assertEquals(
            listOf("read", "notify"),
            characteristics[0].jsonObject["properties"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
