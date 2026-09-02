package ai.sealgate.stdiod.mcp

import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileBashRuntimeTest {
    @Test
    fun sleepSupportsAnimationPacingAndRejectsUnsafeDurations() {
        val source = File("src/main/assets/mobile-bash-runtime.js").readText()
        val runtime = QuickJsMobileBashRuntime({ source }, MobileCommandRouter(emptyList()))
        try {
            val startedAt = System.nanoTime()
            val paced = runtime.execute("printf before; sleep 0.01s .01; printf after")
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            assertEquals(0, paced.exitCode)
            assertEquals("beforeafter", paced.stdout)
            assertTrue("sleep returned too early after ${'$'}elapsedMillis ms", elapsedMillis >= 10)

            val invalid = runtime.execute("sleep forever")
            assertTrue(invalid.exitCode != 0)
            assertTrue(invalid.stderr.contains("invalid time interval 'forever'"))

            val capped = runtime.execute("sleep 30s 0.1s")
            assertTrue(capped.exitCode != 0)
            assertTrue(capped.stderr.contains("limit of 30 seconds"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun composesVirtualFilesUtilitiesAndAndroidCommandsWithoutHostAccess() {
        val router = MobileCommandRouter(
            listOf(
                BatteryModule(
                    object : BatterySource {
                        override fun snapshot() = BatterySnapshot(87, "charging", "usb")
                    },
                ),
            ),
        )
        val source = File("src/main/assets/mobile-bash-runtime.js").readText()
        val runtime = QuickJsMobileBashRuntime({ source }, router)
        try {
            val first = runtime.execute("printf 'a\\nb\\n' | grep b > /tmp/result; cat /tmp/result")
            assertEquals(0, first.exitCode)
            assertEquals("b\n", first.stdout)

            val persisted = runtime.execute("cat /tmp/result | wc -l")
            assertEquals("1\n", persisted.stdout)

            val toolbox = runtime.execute(
                "printf 'alpha\\nbeta\\n' | sed 's/beta/gamma/' | awk '/gamma/ { print toupper(${ '$' }0) }' | rg GAMMA",
            )
            assertEquals(0, toolbox.exitCode)
            assertEquals("1:GAMMA\n", toolbox.stdout)

            val device = runtime.execute("battery status | jq '.level_percent'")
            assertEquals(0, device.exitCode)
            assertEquals("87\n", device.stdout)

            val unavailableComputer = runtime.execute("computer status")
            assertTrue(unavailableComputer.exitCode != 0)
            assertTrue(unavailableComputer.stderr.contains("computer: command not found"))

            assertEquals("set\n", runtime.execute("export TEMP=set; echo \"${'$'}TEMP\"").stdout)
            assertEquals("unset\n", runtime.execute("echo \"${'$'}{TEMP:-unset}\"").stdout)

            val hostFile = runtime.execute("cat /etc/passwd")
            assertTrue(hostFile.exitCode != 0)
            assertTrue(hostFile.stderr.contains("No such file"))

            listOf(
                "curl https://example.com",
                "wget https://example.com",
                "python -c 'print(1)'",
                "node -e 'console.log(1)'",
                "js-exec '1 + 1'",
                "sqlite3 /tmp/db 'select 1'",
                "tar -cf /tmp/archive.tar /tmp/result",
            ).forEach { forbidden ->
                val denied = runtime.execute(forbidden)
                assertTrue("$forbidden unexpectedly ran", denied.exitCode != 0)
                assertEquals("$forbidden did not use the policy-denied exit code", 126, denied.exitCode)
                assertTrue(
                    "$forbidden was not denied by policy; stderr=${denied.stderr}",
                    denied.stderr.contains("blocked by Mobile Bash policy"),
                )
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun typedMcpSupplementsCrossQuickJsAsOpaqueTokens() {
        val probe = object : BaseMcpModule() {
            override val name = "computer"
            override fun toolDescriptors(): JsonElement = buildJsonArray {
                add(buildJsonObject {
                    put("name", JsonPrimitive("computer_observe"))
                    put("description", JsonPrimitive("probe"))
                    put("inputSchema", buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("properties", buildJsonObject {})
                    })
                })
            }
            override fun callTool(id: JsonElement, toolName: String, arguments: JsonObject): JsonObject =
                JsonRpc.toolResult(
                    id,
                    listOf(JsonRpc.textContent("{\"observationId\":\"obs_1\"}"), JsonRpc.imageContent("aA==", "image/png")),
                    buildJsonObject { put("observationId", JsonPrimitive("obs_1")) },
                )
        }
        val source = File("src/main/assets/mobile-bash-runtime.js").readText()
        val runtime = QuickJsMobileBashRuntime({ source }, MobileCommandRouter(listOf(probe)))
        try {
            val result = runtime.execute("computer observe")
            assertEquals(0, result.exitCode)
            assertEquals(1, result.supplements.size)
            assertEquals("\"image\"", result.supplements.single().content.single()["type"].toString())
            assertEquals("\"obs_1\"", result.supplements.single().structuredContent!!["observationId"].toString())
        } finally {
            runtime.close()
        }
    }
}
