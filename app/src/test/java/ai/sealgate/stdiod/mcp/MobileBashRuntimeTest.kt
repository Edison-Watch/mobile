package ai.sealgate.stdiod.mcp

import java.io.File
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
                assertTrue(
                    "$forbidden was not excluded from the command set; stderr=${denied.stderr}",
                    denied.stderr.contains("not found") ||
                        denied.stderr.contains("command not available in browser environments"),
                )
            }
        } finally {
            runtime.close()
        }
    }
}
