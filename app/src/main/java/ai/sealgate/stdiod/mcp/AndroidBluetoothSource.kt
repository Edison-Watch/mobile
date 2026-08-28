package ai.sealgate.stdiod.mcp

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Production [BluetoothSource] backed by [BluetoothManager]. */
class AndroidBluetoothSource(private val context: Context) : BluetoothSource {

    private val adapter
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    override val adapterPresent: Boolean get() = adapter != null

    override val enabled: Boolean get() = adapter?.isEnabled == true

    override val hasConnectPermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    override fun bondedDevices(): List<BondedDevice> {
        // Callers check [hasConnectPermission] first, but the user can revoke
        // it at any moment - degrade to an empty list instead of crashing.
        val bonded = try {
            adapter?.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }
        return bonded.map { device ->
            BondedDevice(
                name = try {
                    device.name
                } catch (_: SecurityException) {
                    null
                },
                type = when (
                    try {
                        device.type
                    } catch (_: SecurityException) {
                        BluetoothDevice.DEVICE_TYPE_UNKNOWN
                    }
                ) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
                    BluetoothDevice.DEVICE_TYPE_LE -> "le"
                    BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                    else -> "unknown"
                },
            )
        }
    }
}
