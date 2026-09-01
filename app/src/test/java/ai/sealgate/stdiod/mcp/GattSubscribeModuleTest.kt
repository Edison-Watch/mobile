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
 * Fake for the GATT notify/indicate surface. It returns canned subscribe /
 * poll / write_wait results and, for `length_delimited`, runs the same varint
 * reassembler the real Android source uses, so the module's arg parsing, JSON
 * shaping and framing are exercised without any hardware.
 */
private class FakeSubscribe(
    override val adapterPresent: Boolean = true,
    override val enabled: Boolean = true,
    override val hasConnectPermission: Boolean = true,
    override val hasScanPermission: Boolean = true,
    private val subscribeResult: GattSubscribeResult = GattSubscribeResult(mode = "notify", cccdWritten = true),
    private val pollEvents: List<GattNotification> = emptyList(),
    private val pollOverflow: Int = 0,
    private val hasSubscription: Boolean = true,
    private val writeWaitEvents: List<GattNotification> = emptyList(),
) : BluetoothControlSource {

    var lastMtuRequested: Int? = null
    var lastPollDecode: String? = null
    var lastWriteWaitValue: ByteArray? = null
    var lastSubscribeMode: String? = null

    override fun bondedDevices(): List<BondedDevice> = emptyList()
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

    override fun gattRequestMtu(address: String, mtu: Int, timeoutMs: Long): GattMtuResult {
        lastMtuRequested = mtu
        // Mimic a peer that grants exactly what was asked (post-clamp).
        return GattMtuResult(mtu = mtu)
    }

    override fun gattSubscribe(
        address: String,
        service: String,
        characteristic: String,
        mode: String,
        timeoutMs: Long,
    ): GattSubscribeResult {
        lastSubscribeMode = mode
        return subscribeResult
    }

    override fun gattNotificationsPoll(
        address: String,
        service: String,
        characteristic: String,
        maxEvents: Int,
        idleTimeoutMs: Long,
        maxBytes: Int,
        decode: String,
    ): GattNotificationsResult {
        lastPollDecode = decode
        if (!hasSubscription) {
            return GattNotificationsResult(error = "no active subscription for $characteristic on $address")
        }
        val frames = if (decode == "length_delimited") reassemble(pollEvents) else emptyList()
        return GattNotificationsResult(events = pollEvents, overflowCount = pollOverflow, frames = frames)
    }

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
    ): GattWriteWaitResult {
        lastWriteWaitValue = value
        val frames = if (decode == "length_delimited") reassemble(writeWaitEvents) else emptyList()
        return GattWriteWaitResult(
            events = writeWaitEvents,
            frames = frames,
            txWritten = true,
            timedOut = writeWaitEvents.isEmpty(),
        )
    }

    override fun sppConnect(address: String, uuid: String, timeoutMs: Long) = BtOpResult()
    override fun sppSend(address: String, value: ByteArray, timeoutMs: Long) = BtOpResult()
    override fun sppRecv(address: String, timeoutMs: Long, maxBytes: Int) = SppRecvResult()
    override fun sppDisconnect(address: String) = BtOpResult()

    private fun reassemble(events: List<GattNotification>): List<ByteArray> {
        var combined = ByteArray(0)
        for (event in events) combined += event.value
        return BluetoothModule.parseLengthDelimited(combined).frames
    }
}

class GattSubscribeModuleTest {

    private val addr = "AA:BB:CC:DD:EE:FF"

    private fun rpc(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun call(source: BluetoothControlSource, toolName: String, arguments: String = "{}"): JsonObject {
        val response = BluetoothModule(source).handle(
            rpc(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call",
                   "params":{"name":"$toolName","arguments":$arguments}}""",
            ),
        )!!
        // Preconditions and failures are always in-band, never JSON-RPC errors.
        assertNull(response["error"])
        return response["result"]!!.jsonObject
    }

    private fun textOf(result: JsonObject): String =
        result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!

    private fun isError(result: JsonObject): Boolean =
        result["isError"]?.jsonPrimitive?.content.toBoolean()

    private fun event(value: ByteArray, seq: Long): GattNotification =
        GattNotification(
            timestampMs = 1_700_000_000_000L + seq,
            address = addr,
            service = "180f",
            characteristic = "2a19",
            value = value,
            seq = seq,
        )

    @Test
    fun subscribeReturnsAckWithModeAndCccd() {
        val fake = FakeSubscribe(subscribeResult = GattSubscribeResult(mode = "indicate", cccdWritten = true))
        val result = call(
            fake,
            "bt_gatt_subscribe",
            """{"address":"$addr","service":"180f","characteristic":"2a19","mode":"auto"}""",
        )
        assertFalse(isError(result))
        assertEquals("auto", fake.lastSubscribeMode)
        val payload = Json.parseToJsonElement(textOf(result)).jsonObject
        assertEquals(true, payload["subscribed"]?.jsonPrimitive?.content.toBoolean())
        assertEquals("indicate", payload["mode"]?.jsonPrimitive?.content)
        assertEquals(true, payload["cccd_written"]?.jsonPrimitive?.content.toBoolean())
    }

    @Test
    fun subscribeOnCharacteristicWithoutNotifyOrIndicateIsAnInBandError() {
        val fake = FakeSubscribe(
            subscribeResult = GattSubscribeResult(
                error = "characteristic 2a19 advertises neither notify nor indicate",
            ),
        )
        val result = call(
            fake,
            "bt_gatt_subscribe",
            """{"address":"$addr","service":"180f","characteristic":"2a19"}""",
        )
        assertTrue(isError(result))
        assertTrue(textOf(result).contains("neither notify nor indicate"))
    }

    @Test
    fun subscribeEnableFalseUnsubscribes() {
        val result = call(
            FakeSubscribe(),
            "bt_gatt_subscribe",
            """{"address":"$addr","service":"180f","characteristic":"2a19","enable":false}""",
        )
        assertFalse(isError(result))
        val payload = Json.parseToJsonElement(textOf(result)).jsonObject
        assertEquals(true, payload["unsubscribed"]?.jsonPrimitive?.content.toBoolean())
    }

    @Test
    fun pollReturnsQueuedEventsAndOverflow() {
        val fake = FakeSubscribe(
            pollEvents = listOf(event(byteArrayOf(0xab.toByte(), 0xcd.toByte()), seq = 1)),
            pollOverflow = 3,
        )
        val result = call(
            fake,
            "bt_gatt_notifications_poll",
            """{"address":"$addr","service":"180f","characteristic":"2a19"}""",
        )
        assertFalse(isError(result))
        val payload = Json.parseToJsonElement(textOf(result)).jsonObject
        assertEquals(3, payload["overflow_count"]?.jsonPrimitive?.content?.toInt())
        val events = payload["events"]!!.jsonArray
        assertEquals(1, events.size)
        assertEquals("abcd", events[0].jsonObject["value_hex"]?.jsonPrimitive?.content)
        assertEquals(2, events[0].jsonObject["value_len"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1, events[0].jsonObject["seq"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun pollWithUtf8ReturnFormatIncludesUtf8() {
        val fake = FakeSubscribe(pollEvents = listOf(event("hi".toByteArray(Charsets.UTF_8), seq = 1)))
        val result = call(
            fake,
            "bt_gatt_notifications_poll",
            """{"address":"$addr","service":"180f","characteristic":"2a19","return_format":"utf8"}""",
        )
        val events = Json.parseToJsonElement(textOf(result)).jsonObject["events"]!!.jsonArray
        assertEquals("hi", events[0].jsonObject["value_utf8"]?.jsonPrimitive?.content)
    }

    @Test
    fun pollWithoutActiveSubscriptionIsAnInBandError() {
        val result = call(
            FakeSubscribe(hasSubscription = false),
            "bt_gatt_notifications_poll",
            """{"address":"$addr","service":"180f","characteristic":"2a19"}""",
        )
        assertTrue(isError(result))
        assertTrue(textOf(result).contains("no active subscription"))
    }

    @Test
    fun writeWaitReturnsCollectedEventsAndTxWritten() {
        val fake = FakeSubscribe(writeWaitEvents = listOf(event(byteArrayOf(0x01, 0x02), seq = 1)))
        val result = call(
            fake,
            "bt_gatt_write_wait",
            """{"address":"$addr","tx_service":"180f","tx_characteristic":"2a1a",
                "value_hex":"aa","rx_service":"180f","rx_characteristic":"2a19"}""",
        )
        assertFalse(isError(result))
        assertEquals(listOf(0xaa), fake.lastWriteWaitValue!!.map { it.toInt() and 0xff })
        val payload = Json.parseToJsonElement(textOf(result)).jsonObject
        assertEquals(true, payload["tx_written"]?.jsonPrimitive?.content.toBoolean())
        assertEquals(1, payload["events"]!!.jsonArray.size)
    }

    @Test
    fun writeWaitWithBadHexIsAnInBandError() {
        val result = call(
            FakeSubscribe(),
            "bt_gatt_write_wait",
            """{"address":"$addr","tx_service":"180f","tx_characteristic":"2a1a",
                "value_hex":"zz","rx_service":"180f","rx_characteristic":"2a19"}""",
        )
        assertTrue(isError(result))
        assertTrue(textOf(result).contains("value_hex"))
    }

    @Test
    fun writeWaitRequireReplyTurnsTimeoutIntoError() {
        val result = call(
            FakeSubscribe(writeWaitEvents = emptyList()),
            "bt_gatt_write_wait",
            """{"address":"$addr","tx_service":"180f","tx_characteristic":"2a1a",
                "value_hex":"aa","rx_service":"180f","rx_characteristic":"2a19","require_reply":true}""",
        )
        assertTrue(isError(result))
        assertTrue(textOf(result).contains("timed out waiting for a GATT reply"))
    }

    @Test
    fun requestMtuClampsAboveRangeAndReturnsNegotiated() {
        val fake = FakeSubscribe()
        val result = call(fake, "bt_gatt_request_mtu", """{"address":"$addr","mtu":99999}""")
        assertFalse(isError(result))
        assertEquals(517, fake.lastMtuRequested)
        val payload = Json.parseToJsonElement(textOf(result)).jsonObject
        assertEquals(517, payload["requested_mtu"]?.jsonPrimitive?.content?.toInt())
        assertEquals(517, payload["negotiated_mtu"]?.jsonPrimitive?.content?.toInt())
        assertEquals(517, payload["mtu"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun requestMtuClampsBelowRange() {
        val fake = FakeSubscribe()
        call(fake, "bt_gatt_request_mtu", """{"address":"$addr","mtu":1}""")
        assertEquals(23, fake.lastMtuRequested)
    }

    @Test
    fun lengthDelimitedReassemblyAcrossTwoEventsYieldsOneFrame() {
        // One 5-byte frame (varint length 0x05) split across two notifications.
        val fake = FakeSubscribe(
            pollEvents = listOf(
                event(byteArrayOf(0x05, 0x11, 0x22), seq = 1),
                event(byteArrayOf(0x33, 0x44, 0x55), seq = 2),
            ),
        )
        val result = call(
            fake,
            "bt_gatt_notifications_poll",
            """{"address":"$addr","service":"180f","characteristic":"2a19","decode":"length_delimited"}""",
        )
        assertFalse(isError(result))
        assertEquals("length_delimited", fake.lastPollDecode)
        val frames = Json.parseToJsonElement(textOf(result)).jsonObject["frames"]!!.jsonArray
        assertEquals(1, frames.size)
        assertEquals("1122334455", frames[0].jsonPrimitive.content)
    }

    @Test
    fun parseLengthDelimitedBuffersIncompleteRemainder() {
        // Length says 4 bytes but only 2 are present -> no frame, remainder kept.
        val parsed = BluetoothModule.parseLengthDelimited(byteArrayOf(0x04, 0xaa.toByte(), 0xbb.toByte()))
        assertTrue(parsed.frames.isEmpty())
        assertEquals(3, parsed.remainder.size)
    }
}
