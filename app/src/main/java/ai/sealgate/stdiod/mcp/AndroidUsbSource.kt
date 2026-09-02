package ai.sealgate.stdiod.mcp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Production [UsbSource] backed by [UsbManager] (the Android USB Host API).
 *
 * USB host access needs no manifest `<uses-permission>`; instead the app must
 * hold per-device runtime permission granted through a system dialog
 * ([UsbManager.requestPermission]). Every I/O method checks
 * [UsbManager.hasPermission] first and reports a missing grant in-band rather
 * than throwing. Open connections (and the claimed interface) are held by
 * [UsbDevice.getDeviceName] in a thread-safe map so `usb_open` -> transfers ->
 * `usb_close` reference one session; `TunnelService` holds one long-lived
 * instance for the app session so this state persists across tool calls.
 */
class AndroidUsbSource(private val context: Context) : UsbSource {

    private val usbManager: UsbManager?
        get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val sessions = ConcurrentHashMap<String, Session>()
    private val lifecycleLock = Any()
    @Volatile private var closed = false

    override val hostSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST) &&
            usbManager != null

    override fun listDevices(): List<UsbDeviceInfo> {
        val manager = usbManager ?: return emptyList()
        val devices = try {
            manager.deviceList?.values?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return devices.map { device -> describe(manager, device) }
    }

    private fun describe(manager: UsbManager, device: UsbDevice): UsbDeviceInfo {
        val permitted = try {
            manager.hasPermission(device)
        } catch (_: Exception) {
            false
        }
        // String descriptors need an open connection with permission; degrade to
        // null (never crash) when we can't read them.
        var manufacturer: String? = null
        var product: String? = null
        var serial: String? = null
        if (permitted) {
            try {
                manufacturer = device.manufacturerName
                product = device.productName
                serial = readSerial(device)
            } catch (_: Exception) {
                // Leave whatever was read as null; enumeration must not fail.
            }
        }
        return UsbDeviceInfo(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturer = manufacturer,
            product = product,
            serial = serial,
            deviceClass = device.deviceClass,
            interfaceCount = device.interfaceCount,
            hasPermission = permitted,
        )
    }

    /** Reading the serial number requires per-device permission and throws
     *  SecurityException without it on Android 10+; keep that handled in the same
     *  function as the call so lint sees it guarded, and degrade to null. */
    @Suppress("DEPRECATION")
    private fun readSerial(device: UsbDevice): String? = try {
        device.serialNumber
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }

    override fun requestPermission(deviceName: String): UsbPermissionResult {
        val manager = usbManager ?: return UsbPermissionResult(error = "USB host service is unavailable on this device")
        val device = findDevice(manager, deviceName)
            ?: return UsbPermissionResult(error = "unknown USB device: $deviceName (call usb_list_devices)")
        return try {
            if (manager.hasPermission(device)) {
                return UsbPermissionResult(granted = true)
            }
            // Register a receiver for the grant result. The grant is async and
            // user-driven, so we only fire the dialog and return; callers re-check
            // has_permission (or just retry the I/O).
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    try {
                        ctx.unregisterReceiver(this)
                    } catch (_: Exception) {
                        // Already unregistered - ignore.
                    }
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags,
            )
            manager.requestPermission(device, pending)
            UsbPermissionResult(requested = true)
        } catch (e: SecurityException) {
            UsbPermissionResult(error = "USB permission request denied: ${e.message}")
        } catch (e: Exception) {
            UsbPermissionResult(error = "could not request USB permission for $deviceName: ${e.message}")
        }
    }

    override fun open(deviceName: String, interfaceIndex: Int): UsbOpenResult {
        if (closed) return UsbOpenResult(error = "USB source is closed")
        val manager = usbManager ?: return UsbOpenResult(error = "USB host service is unavailable on this device")
        val device = findDevice(manager, deviceName)
            ?: return UsbOpenResult(error = "unknown USB device: $deviceName (call usb_list_devices)")
        return try {
            if (!manager.hasPermission(device)) {
                return UsbOpenResult(error = permissionMessage(deviceName))
            }
            if (interfaceIndex < 0 || interfaceIndex >= device.interfaceCount) {
                return UsbOpenResult(
                    error = "interface_index $interfaceIndex out of range (device has ${device.interfaceCount} interface(s))",
                )
            }
            // Drop any stale session for the same device first.
            synchronized(lifecycleLock) {
                if (closed) return UsbOpenResult(error = "USB source is closed")
                sessions.remove(deviceName)
            }?.close()
            val iface = device.getInterface(interfaceIndex)
            val connection = manager.openDevice(device)
                ?: return UsbOpenResult(error = "could not open USB device $deviceName")
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                return UsbOpenResult(error = "could not claim interface $interfaceIndex on $deviceName")
            }
            val session = Session(connection, iface)
            var replaced: Session? = null
            val accepted = synchronized(lifecycleLock) {
                if (closed) false else {
                    replaced = sessions.put(deviceName, session)
                    true
                }
            }
            replaced?.close()
            if (!accepted) {
                session.close()
                return UsbOpenResult(error = "USB source was closed while opening $deviceName")
            }
            UsbOpenResult(endpoints = endpointsOf(iface))
        } catch (e: SecurityException) {
            UsbOpenResult(error = "USB permission denied: ${e.message}")
        } catch (e: Exception) {
            UsbOpenResult(error = "failed to open $deviceName: ${e.message}")
        }
    }

    override fun bulkTransfer(
        deviceName: String,
        endpointAddress: Int,
        payload: ByteArray?,
        length: Int,
        timeoutMs: Int,
    ): UsbTransferResult {
        val session = sessions[deviceName] ?: return UsbTransferResult(error = notOpen(deviceName))
        val endpoint = findEndpoint(session.iface, endpointAddress)
            ?: return UsbTransferResult(
                error = "unknown endpoint 0x${Integer.toHexString(endpointAddress)} on the claimed interface of $deviceName",
            )
        return try {
            val isIn = endpoint.direction == UsbConstants.USB_DIR_IN
            if (isIn) {
                val buffer = ByteArray(length)
                val n = session.connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
                if (n < 0) {
                    UsbTransferResult(error = "bulk IN transfer failed or timed out on $deviceName")
                } else {
                    UsbTransferResult(bytesTransferred = n, value = buffer.copyOf(n))
                }
            } else {
                val buffer = payload ?: ByteArray(0)
                val n = session.connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
                if (n < 0) {
                    UsbTransferResult(error = "bulk OUT transfer failed or timed out on $deviceName")
                } else {
                    UsbTransferResult(bytesTransferred = n)
                }
            }
        } catch (e: Exception) {
            UsbTransferResult(error = "bulk transfer error on $deviceName: ${e.message}")
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
        val session = sessions[deviceName] ?: return UsbTransferResult(error = notOpen(deviceName))
        val isIn = requestType and UsbConstants.USB_DIR_IN != 0
        return try {
            val buffer = if (isIn) ByteArray(length) else (payload ?: ByteArray(0))
            val n = session.connection.controlTransfer(
                requestType,
                request,
                value,
                index,
                buffer,
                buffer.size,
                timeoutMs,
            )
            if (n < 0) {
                UsbTransferResult(error = "control transfer failed or timed out on $deviceName")
            } else if (isIn) {
                UsbTransferResult(bytesTransferred = n, value = buffer.copyOf(n))
            } else {
                UsbTransferResult(bytesTransferred = n)
            }
        } catch (e: Exception) {
            UsbTransferResult(error = "control transfer error on $deviceName: ${e.message}")
        }
    }

    override fun close(deviceName: String): UsbOpResult {
        // Idempotent: closing a device that is not open still succeeds.
        sessions.remove(deviceName)?.close()
        return UsbOpResult()
    }

    override fun close() {
        val toClose = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            sessions.values.toList().also { sessions.clear() }
        }
        toClose.forEach(Session::close)
    }

    // -- Helpers ------------------------------------------------------------

    private fun findDevice(manager: UsbManager, deviceName: String): UsbDevice? = try {
        manager.deviceList?.get(deviceName) ?: manager.deviceList?.values?.firstOrNull { it.deviceName == deviceName }
    } catch (_: Exception) {
        null
    }

    private fun findEndpoint(iface: UsbInterface, address: Int): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.address == address) return ep
        }
        return null
    }

    private fun endpointsOf(iface: UsbInterface): List<UsbEndpointInfo> {
        val out = ArrayList<UsbEndpointInfo>(iface.endpointCount)
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            out += UsbEndpointInfo(
                address = ep.address,
                direction = if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out",
                type = endpointType(ep.type),
                maxPacketSize = ep.maxPacketSize,
            )
        }
        return out
    }

    private fun endpointType(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "iso"
        else -> "unknown"
    }

    private fun permissionMessage(deviceName: String): String =
        "permission not granted for device $deviceName: call usb_request_permission and approve the on-device dialog"

    private fun notOpen(deviceName: String): String =
        "not open: call usb_open first for device $deviceName"

    /** One open device connection plus its claimed interface. */
    private class Session(val connection: UsbDeviceConnection, val iface: UsbInterface) {
        fun close() {
            try {
                connection.releaseInterface(iface)
            } catch (_: Exception) {
                // Best effort: closing regardless.
            }
            try {
                connection.close()
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "ai.sealgate.stdiod.USB_PERMISSION"
    }
}
