package ai.sealgate.stdiod.mcp

import android.util.Log
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileBashDeviceTest {
    @Test
    fun packagedRuntimeCallsRealAndroidCapabilitiesInOneVirtualSession() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val modules = listOf(
            DeviceInfoModule(AndroidDeviceInfo),
            BatteryModule(AndroidBatterySource(context)),
            WifiModule(AndroidWifiSource(context)),
            BluetoothModule(AndroidBluetoothSource(context)),
            UsbModule(AndroidUsbSource(context)),
        )
        val source = context.assets.open("mobile-bash-runtime.js").bufferedReader().use { it.readText() }
        val runtime = QuickJsMobileBashRuntime({ source }, MobileCommandRouter(modules))
        try {
            val shell = runtime.execute("printf 'alpha\\nbeta\\n' > /tmp/lines; grep beta /tmp/lines")
            assertEquals(0, shell.exitCode)
            assertEquals("beta\n", shell.stdout)

            val device = runtime.execute("device info")
            assertEquals(0, device.exitCode)
            assertTrue(Json.parseToJsonElement(device.stdout).jsonObject.containsKey("model"))

            val battery = runtime.execute("battery status")
            assertEquals(0, battery.exitCode)
            assertTrue(Json.parseToJsonElement(battery.stdout).jsonObject.containsKey("level_percent"))

            val wifi = runtime.execute("wifi status")
            assertEquals(0, wifi.exitCode)
            assertTrue(Json.parseToJsonElement(wifi.stdout).jsonObject.containsKey("enabled"))

            val bluetooth = runtime.execute("bluetooth status")
            assertEquals(0, bluetooth.exitCode)
            assertTrue(Json.parseToJsonElement(bluetooth.stdout).jsonObject.containsKey("adapter_present"))

            val usb = runtime.execute("usb list")
            assertEquals(0, usb.exitCode)
            assertTrue(Json.parseToJsonElement(usb.stdout).jsonObject["devices"]?.jsonArray != null)

            val mcp = BashModule(runtimeFactory = { runtime })
            val response = mcp.handle(
                Json.parseToJsonElement(
                    """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"run","arguments":{"script":"cat /tmp/lines | wc -l"}}}""",
                ).jsonObject,
            )!!
            val result = response["result"]!!.jsonObject
            assertEquals("2\n", result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        } finally {
            runtime.close()
            modules.filterIsInstance<AutoCloseable>().forEach(AutoCloseable::close)
        }
    }

    /**
     * Opt-in hardware smoke-test hook. It is skipped during the normal suite;
     * pass `-e mobile_bash_script '...'` to the instrumentation runner to
     * exercise an explicit command through the packaged runtime on a device.
     */
    @Test
    fun optionalMobileBashHardwareSmokeTest() {
        val arguments = InstrumentationRegistry.getArguments()
        val encodedScript = arguments.getString("mobile_bash_script_base64").orEmpty()
        val script = if (encodedScript.isNotBlank()) {
            val decoded = runCatching { Base64.decode(encodedScript, Base64.DEFAULT) }.getOrNull()
            assumeTrue("mobile_bash_script_base64 was not valid Base64", decoded != null)
            String(decoded!!, Charsets.UTF_8)
        } else {
            arguments.getString("mobile_bash_script").orEmpty()
        }
        assumeTrue("mobile_bash_script or mobile_bash_script_base64 was not provided", script.isNotBlank())

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val modules = listOf(
            DeviceInfoModule(AndroidDeviceInfo),
            BatteryModule(AndroidBatterySource(context)),
            WifiModule(AndroidWifiSource(context)),
            BluetoothModule(AndroidBluetoothSource(context)),
            UsbModule(AndroidUsbSource(context)),
        )
        val source = context.assets.open("mobile-bash-runtime.js").bufferedReader().use { it.readText() }
        val runtime = QuickJsMobileBashRuntime({ source }, MobileCommandRouter(modules))
        try {
            val result = runtime.execute(script)
            Log.i(SMOKE_TAG, "exit=${result.exitCode} stdout=${result.stdout} stderr=${result.stderr}")
            assertEquals(result.stderr.ifBlank { result.stdout }, 0, result.exitCode)
        } finally {
            runtime.close()
            modules.filterIsInstance<AutoCloseable>().forEach(AutoCloseable::close)
        }
    }

    companion object {
        private const val SMOKE_TAG = "MobileBashSmoke"
    }
}
