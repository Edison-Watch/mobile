package ai.sealgate.stdiod.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class MobileCommandRouterTest {
    @Test
    fun statusCommandDelegatesToExistingModuleAsJson() {
        val router = MobileCommandRouter(
            listOf(batteryModule(BatterySnapshot(87, "charging", "usb"))),
        )

        val result = router.execute("battery", listOf("status"))

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("\"level_percent\":87"))
    }

    @Test
    fun positionalAndTypedOptionsUseTheMcpSchema() {
        val probe = BluetoothWriteProbe()
        val router = MobileCommandRouter(listOf(probe))

        val result = router.execute(
            "bluetooth",
            listOf(
                "gatt", "write", "AA:BB", "service-id", "characteristic-id", "01ff",
                "--no-with-response", "--timeout-ms", "1234",
            ),
        )

        assertEquals(0, result.exitCode)
        assertEquals("AA:BB", probe.arguments["address"]!!.jsonPrimitive.content)
        assertEquals("false", probe.arguments["with_response"]!!.jsonPrimitive.content)
        assertEquals("1234", probe.arguments["timeout_ms"]!!.jsonPrimitive.content)
    }

    @Test
    fun helpIsDiscoverableAndUnknownOptionsFail() {
        val router = MobileCommandRouter(listOf(batteryModule(BatterySnapshot(50, "discharging", "none"))))
        assertTrue(router.execute("battery", emptyList()).stdout.contains("status"))

        val failure = router.execute("battery", listOf("status", "--host-file", "/etc/passwd"))
        assertEquals(1, failure.exitCode)
        assertTrue(failure.stderr.contains("unknown option"))
    }

    @Test
    fun bluetoothWriteAcceptsNoResponseAliasesAndSendShorthand() {
        val probe = BluetoothWriteProbe()
        val router = MobileCommandRouter(listOf(probe))

        val withoutResponse = router.execute(
            "bluetooth",
            listOf("gatt", "write", "AA:BB", "service-id", "characteristic-id", "01", "--without-response"),
        )
        assertEquals(0, withoutResponse.exitCode)
        assertEquals("false", probe.arguments["with_response"]!!.jsonPrimitive.content)

        val noResponse = router.execute(
            "bluetooth",
            listOf("gatt", "send", "02", "--no-response"),
        )
        assertEquals(0, noResponse.exitCode)
        assertEquals("02", probe.arguments["value_hex"]!!.jsonPrimitive.content)
        assertEquals("false", probe.arguments["with_response"]!!.jsonPrimitive.content)
    }

    @Test
    fun bluetoothWriteSequencePassesJsonAndTimingOptionsToTheModule() {
        val probe = BluetoothWriteProbe()
        val router = MobileCommandRouter(listOf(probe))
        val sequence = """[{"packets":["01","0203"],"hold_ms":250}]"""

        val result = router.execute(
            "bluetooth",
            listOf(
                "gatt", "write-sequence", sequence,
                "--repeat", "3", "--service", "fa", "--characteristic", "fa02", "--no-response",
            ),
        )

        assertEquals(0, result.exitCode)
        assertEquals("bt_gatt_write_sequence", probe.toolName)
        assertEquals(sequence, probe.arguments["sequence_json"]!!.jsonPrimitive.content)
        assertEquals("3", probe.arguments["repeat"]!!.jsonPrimitive.content)
        assertEquals("fa", probe.arguments["service"]!!.jsonPrimitive.content)
        assertEquals("fa02", probe.arguments["characteristic"]!!.jsonPrimitive.content)
        assertEquals("false", probe.arguments["with_response"]!!.jsonPrimitive.content)
    }

    private class BluetoothWriteProbe : BaseMcpModule() {
        override val name = BluetoothModule.NAME
        var toolName: String = ""
        var arguments: JsonObject = JsonObject(emptyMap())

        override fun toolDescriptors(): JsonElement = buildJsonArray {
            listOf("bt_gatt_write", "bt_gatt_write_sequence").forEach { descriptorName ->
                add(buildJsonObject {
                    put("name", JsonPrimitive(descriptorName))
                    put("description", JsonPrimitive("probe"))
                    put(
                        "inputSchema",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "properties",
                                buildJsonObject {
                                    listOf("address", "service", "characteristic", "value_hex", "sequence_json").forEach {
                                        put(it, buildJsonObject { put("type", JsonPrimitive("string")) })
                                    }
                                    put("with_response", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                                    put("timeout_ms", buildJsonObject { put("type", JsonPrimitive("integer")) })
                                    put("repeat", buildJsonObject { put("type", JsonPrimitive("integer")) })
                                },
                            )
                        },
                    )
                })
            }
        }

        override fun callTool(id: JsonElement, toolName: String, arguments: JsonObject): JsonObject {
            this.toolName = toolName
            this.arguments = arguments
            return JsonRpc.textToolResult(id, "{}")
        }
    }

    private fun batteryModule(snapshot: BatterySnapshot) = BatteryModule(
        object : BatterySource {
            override fun snapshot(): BatterySnapshot = snapshot
        },
    )
}
