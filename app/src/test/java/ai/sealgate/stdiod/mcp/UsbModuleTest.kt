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
 * Records the last control-transfer call so a test can assert the module hands
 * its parsed args straight through to the source.
 */
private data class ControlCall(
    val deviceName: String,
    val requestType: Int,
    val request: Int,
    val value: Int,
    val index: Int,
    val payload: ByteArray?,
    val length: Int,
    val timeoutMs: Int,
)

private class FakeUsb(
    override val hostSupported: Boolean = true,
    private val permitted: Boolean = true,
    private val opened: Boolean = true,
) : UsbSource {

    var lastControl: ControlCall? = null
    val controlReply = byteArrayOf(0xde.toByte(), 0xad.toByte())
    val bulkInReply = byteArrayOf(0x01, 0x02, 0x03)

    override fun listDevices(): List<UsbDeviceInfo> = listOf(
        UsbDeviceInfo(
            deviceName = "/dev/bus/usb/001/002",
            vendorId = 0x1234,
            productId = 0x5678,
            manufacturer = if (permitted) "Acme" else null,
            product = if (permitted) "Widget" else null,
            serial = if (permitted) "SN-1" else null,
            deviceClass = 0,
            interfaceCount = 1,
            hasPermission = permitted,
        ),
    )

    override fun requestPermission(deviceName: String): UsbPermissionResult =
        if (permitted) UsbPermissionResult(granted = true) else UsbPermissionResult(requested = true)

    override fun open(deviceName: String, interfaceIndex: Int): UsbOpenResult {
        if (!permitted) {
            return UsbOpenResult(
                error = "permission not granted for device $deviceName: call usb_request_permission and approve the on-device dialog",
            )
        }
        return UsbOpenResult(
            endpoints = listOf(
                UsbEndpointInfo(address = 0x81, direction = "in", type = "bulk", maxPacketSize = 64),
                UsbEndpointInfo(address = 0x01, direction = "out", type = "bulk", maxPacketSize = 64),
            ),
        )
    }

    override fun bulkTransfer(
        deviceName: String,
        endpointAddress: Int,
        payload: ByteArray?,
        length: Int,
        timeoutMs: Int,
    ): UsbTransferResult {
        if (!opened) return UsbTransferResult(error = "not open: call usb_open first for device $deviceName")
        val isIn = endpointAddress and UsbModule.USB_DIR_IN != 0
        return if (isIn) {
            UsbTransferResult(bytesTransferred = bulkInReply.size, value = bulkInReply)
        } else {
            UsbTransferResult(bytesTransferred = payload?.size ?: 0)
        }
    }

    override fun controlTransfer(
        deviceName: String,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        payload: ByteArray?,
        length: Int,
        timeoutMs: Int,
    ): UsbTransferResult {
        if (!opened) return UsbTransferResult(error = "not open: call usb_open first for device $deviceName")
        lastControl = ControlCall(deviceName, requestType, request, value, index, payload, length, timeoutMs)
        val isIn = requestType and UsbModule.USB_DIR_IN != 0
        return if (isIn) {
            UsbTransferResult(bytesTransferred = controlReply.size, value = controlReply)
        } else {
            UsbTransferResult(bytesTransferred = payload?.size ?: 0)
        }
    }

    override fun close(deviceName: String): UsbOpResult = UsbOpResult()
}

class UsbModuleTest {

    private fun rpc(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun call(module: UsbModule, name: String, args: String = "{}"): JsonObject {
        val response = module.handle(
            rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""),
        )!!
        return response["result"]!!.jsonObject
    }

    private fun isError(result: JsonObject): Boolean =
        result["isError"]?.jsonPrimitive?.content.toBoolean()

    private fun text(result: JsonObject): String =
        result["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content!!

    @Test
    fun toolsListAdvertisesTheFullOrderedToolSet() {
        val module = UsbModule(FakeUsb())
        val response = module.handle(rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))!!
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray
        val names = tools.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertEquals(
            listOf(
                "usb_list_devices",
                "usb_request_permission",
                "usb_open",
                "usb_bulk_transfer",
                "usb_control_transfer",
                "usb_close",
            ),
            names,
        )
    }

    @Test
    fun listDevicesShapesDeviceJsonIncludingHasPermission() {
        val module = UsbModule(FakeUsb())
        val result = call(module, "usb_list_devices")
        assertFalse(isError(result))
        val payload = Json.parseToJsonElement(text(result)).jsonObject
        assertTrue(payload["host_supported"]?.jsonPrimitive?.content.toBoolean())
        val device = payload["devices"]!!.jsonArray[0].jsonObject
        assertEquals("/dev/bus/usb/001/002", device["device_name"]?.jsonPrimitive?.content)
        assertEquals(0x1234, device["vendor_id"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0x5678, device["product_id"]?.jsonPrimitive?.content?.toInt())
        assertEquals("Acme", device["manufacturer"]?.jsonPrimitive?.content)
        assertEquals(1, device["interface_count"]?.jsonPrimitive?.content?.toInt())
        assertTrue(device["has_permission"]?.jsonPrimitive?.content.toBoolean())
    }

    @Test
    fun ioWithNoOpenConnectionIsAnInBandError() {
        val module = UsbModule(FakeUsb(opened = false))
        val result = call(
            module,
            "usb_bulk_transfer",
            """{"device_name":"/dev/bus/usb/001/002","endpoint_address":129,"length":8}""",
        )
        assertTrue(isError(result))
        assertTrue(text(result).contains("not open"))
    }

    @Test
    fun bulkOutWithBadHexIsAnInBandError() {
        val module = UsbModule(FakeUsb())
        val result = call(
            module,
            "usb_bulk_transfer",
            """{"device_name":"/dev/bus/usb/001/002","endpoint_address":1,"value_hex":"zzz"}""",
        )
        assertTrue(isError(result))
        assertTrue(text(result).contains("invalid value_hex"))
    }

    @Test
    fun bulkInReturnsValueHex() {
        val module = UsbModule(FakeUsb())
        val result = call(
            module,
            "usb_bulk_transfer",
            """{"device_name":"/dev/bus/usb/001/002","endpoint_address":129,"length":8}""",
        )
        assertFalse(isError(result))
        val payload = Json.parseToJsonElement(text(result)).jsonObject
        assertEquals("010203", payload["value_hex"]?.jsonPrimitive?.content)
        assertEquals(3, payload["bytes_transferred"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun bulkOutReportsBytesTransferredAndNoValueHex() {
        val module = UsbModule(FakeUsb())
        val result = call(
            module,
            "usb_bulk_transfer",
            """{"device_name":"/dev/bus/usb/001/002","endpoint_address":1,"value_hex":"01ff"}""",
        )
        assertFalse(isError(result))
        val payload = Json.parseToJsonElement(text(result)).jsonObject
        assertEquals(2, payload["bytes_transferred"]?.jsonPrimitive?.content?.toInt())
        assertNull(payload["value_hex"])
    }

    @Test
    fun openWithoutPermissionIsAnInBandError() {
        val module = UsbModule(FakeUsb(permitted = false))
        val result = call(module, "usb_open", """{"device_name":"/dev/bus/usb/001/002"}""")
        assertTrue(isError(result))
        assertTrue(text(result).contains("permission not granted"))
    }

    @Test
    fun requestPermissionReportsGrantedWhenAlreadyPermitted() {
        val module = UsbModule(FakeUsb(permitted = true))
        val result = call(module, "usb_request_permission", """{"device_name":"/dev/bus/usb/001/002"}""")
        assertFalse(isError(result))
        val payload = Json.parseToJsonElement(text(result)).jsonObject
        assertTrue(payload["granted"]?.jsonPrimitive?.content.toBoolean())
    }

    @Test
    fun controlTransferRoundTripsArgsAndReturnsThePayload() {
        val fake = FakeUsb()
        val module = UsbModule(fake)
        // request_type 0xC0 = IN | vendor | device.
        val result = call(
            module,
            "usb_control_transfer",
            """{"device_name":"/dev/bus/usb/001/002","request_type":192,"request":1,"value":2,"index":3,"length":16,"timeout_ms":500}""",
        )
        assertFalse(isError(result))
        val call = fake.lastControl!!
        assertEquals("/dev/bus/usb/001/002", call.deviceName)
        assertEquals(192, call.requestType)
        assertEquals(1, call.request)
        assertEquals(2, call.value)
        assertEquals(3, call.index)
        assertEquals(16, call.length)
        assertEquals(500, call.timeoutMs)
        val payload = Json.parseToJsonElement(text(result)).jsonObject
        assertEquals("dead", payload["value_hex"]?.jsonPrimitive?.content)
        assertEquals(2, payload["bytes_transferred"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun unknownToolIsAnInBandError() {
        val module = UsbModule(FakeUsb())
        val result = call(module, "usb_reboot")
        assertTrue(isError(result))
    }
}
