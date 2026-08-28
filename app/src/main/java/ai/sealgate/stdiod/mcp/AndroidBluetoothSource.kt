package ai.sealgate.stdiod.mcp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult as LeScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Production [BluetoothControlSource] backed by [BluetoothManager].
 *
 * The module's `callTool` is synchronous, but GATT (`BluetoothGatt` +
 * `BluetoothGattCallback`) and RFCOMM (`BluetoothSocket` blocking IO) are
 * asynchronous/blocking. Every method here bridges that gap: it kicks off the
 * async work, blocks on a `CountDownLatch`/reader thread up to `timeoutMs`, and
 * returns a plain domain result. Live connections are held by device address in
 * thread-safe maps so `bt_gatt_read`/`bt_spp_send` etc. can reference a socket
 * opened by an earlier tool call. `TunnelService` holds one long-lived instance
 * for the app session, so this connection state persists across calls.
 */
class AndroidBluetoothSource(private val context: Context) : BluetoothControlSource {

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val gattConnections = ConcurrentHashMap<String, GattConnection>()
    private val sppConnections = ConcurrentHashMap<String, SppConnection>()

    override val adapterPresent: Boolean get() = adapter != null

    override val enabled: Boolean get() = adapter?.isEnabled == true

    override val hasConnectPermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    override val hasScanPermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN,
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
                type = deviceType(device),
            )
        }
    }

    // -- Scan ---------------------------------------------------------------

    override fun scan(timeoutMs: Long): ScanResult {
        val adapter = adapter ?: return ScanResult(error = "this device has no bluetooth adapter")
        val scanner = adapter.bluetoothLeScanner
            ?: return ScanResult(error = "BLE scanning is unavailable on this device")
        val found = ConcurrentHashMap<String, ScannedDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: LeScanResult) {
                val device = result.device ?: return
                val addr = device.address ?: return
                found[addr] = ScannedDevice(
                    address = addr,
                    name = try {
                        result.scanRecord?.deviceName ?: device.name
                    } catch (_: SecurityException) {
                        null
                    },
                    type = deviceType(device),
                    rssi = result.rssi,
                )
            }

            override fun onScanFailed(errorCode: Int) {
                // Leave whatever was already found; the tool reports what we have.
            }
        }
        return try {
            scanner.startScan(callback)
            sleepQuietly(timeoutMs)
            ScanResult(devices = found.values.sortedByDescending { it.rssi })
        } catch (e: SecurityException) {
            ScanResult(error = "bluetooth scan permission denied: ${e.message}")
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
                // Best effort: the scan is ending regardless.
            }
        }
    }

    // -- Pair / unpair ------------------------------------------------------

    override fun pair(address: String, timeoutMs: Long): BtOpResult {
        val device = remoteDevice(address) ?: return BtOpResult("unknown device address: $address")
        return try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) return BtOpResult()
            val latch = CountDownLatch(1)
            val finalState = AtomicReference(BluetoothDevice.BOND_NONE)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    val dev = intentDevice(intent) ?: return
                    if (dev.address != address) return
                    val state = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.BOND_NONE,
                    )
                    // BONDING is the transient middle state; only settle on a
                    // terminal BONDED / NONE.
                    if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE) {
                        finalState.set(state)
                        latch.countDown()
                    }
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            try {
                if (!device.createBond()) return BtOpResult("failed to start pairing with $address")
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    return BtOpResult("timed out pairing with $address")
                }
                if (finalState.get() == BluetoothDevice.BOND_BONDED) {
                    BtOpResult()
                } else {
                    BtOpResult("pairing with $address was rejected or removed")
                }
            } finally {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Exception) {
                    // Already unregistered / never registered - ignore.
                }
            }
        } catch (e: SecurityException) {
            BtOpResult("bluetooth connect permission denied: ${e.message}")
        }
    }

    override fun unpair(address: String): BtOpResult {
        val device = remoteDevice(address) ?: return BtOpResult("unknown device address: $address")
        return try {
            if (device.bondState == BluetoothDevice.BOND_NONE) return BtOpResult()
            // There is no public unpair API; call the hidden removeBond() reflectively.
            val method = device.javaClass.getMethod("removeBond")
            val ok = method.invoke(device) as? Boolean ?: false
            if (ok) BtOpResult() else BtOpResult("removeBond() returned false for $address")
        } catch (e: SecurityException) {
            BtOpResult("bluetooth connect permission denied: ${e.message}")
        } catch (e: Exception) {
            BtOpResult("could not unpair $address reflectively: ${e.message}")
        }
    }

    // -- GATT ---------------------------------------------------------------

    override fun gattConnect(address: String, timeoutMs: Long): GattServicesResult {
        val device = remoteDevice(address)
            ?: return GattServicesResult(error = "unknown device address: $address")
        // Drop any stale connection to the same device first.
        gattConnections.remove(address)?.close()
        val conn = GattConnection()
        return try {
            val latch = CountDownLatch(1)
            conn.beginConnect(latch)
            val gatt = device.connectGatt(context, false, conn.callback, BluetoothDevice.TRANSPORT_LE)
                ?: return GattServicesResult(error = "could not open a GATT connection to $address")
            conn.gatt = gatt
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                conn.close()
                return GattServicesResult(error = "timed out connecting to $address")
            }
            if (!conn.discovered) {
                conn.close()
                return GattServicesResult(error = "failed to connect to / discover services on $address")
            }
            gattConnections[address] = conn
            GattServicesResult(services = readServices(gatt))
        } catch (e: SecurityException) {
            conn.close()
            GattServicesResult(error = "bluetooth connect permission denied: ${e.message}")
        }
    }

    override fun gattServices(address: String): GattServicesResult {
        val conn = gattConnections[address]
            ?: return GattServicesResult(error = NOT_CONNECTED_GATT)
        val gatt = conn.gatt ?: return GattServicesResult(error = NOT_CONNECTED_GATT)
        return try {
            GattServicesResult(services = readServices(gatt))
        } catch (e: SecurityException) {
            GattServicesResult(error = "bluetooth connect permission denied: ${e.message}")
        }
    }

    override fun gattRead(
        address: String,
        service: String,
        characteristic: String,
        timeoutMs: Long,
    ): GattReadResult {
        val conn = gattConnections[address] ?: return GattReadResult(error = NOT_CONNECTED_GATT)
        val gatt = conn.gatt ?: return GattReadResult(error = NOT_CONNECTED_GATT)
        val ch = findCharacteristic(gatt, service, characteristic)
            ?: return GattReadResult(error = "characteristic $characteristic not found in service $service on $address")
        return try {
            val latch = CountDownLatch(1)
            conn.beginRead(latch)
            if (!gatt.readCharacteristic(ch)) {
                return GattReadResult(error = "failed to start read of $characteristic")
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return GattReadResult(error = "timed out reading $characteristic")
            }
            if (!conn.readOk) return GattReadResult(error = "read of $characteristic failed")
            GattReadResult(value = conn.readValue ?: ByteArray(0))
        } catch (e: SecurityException) {
            GattReadResult(error = "bluetooth connect permission denied: ${e.message}")
        }
    }

    override fun gattWrite(
        address: String,
        service: String,
        characteristic: String,
        value: ByteArray,
        withResponse: Boolean,
        timeoutMs: Long,
    ): BtOpResult {
        val conn = gattConnections[address] ?: return BtOpResult(NOT_CONNECTED_GATT)
        val gatt = conn.gatt ?: return BtOpResult(NOT_CONNECTED_GATT)
        val ch = findCharacteristic(gatt, service, characteristic)
            ?: return BtOpResult("characteristic $characteristic not found in service $service on $address")
        val writeType = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return try {
            val latch = CountDownLatch(1)
            conn.beginWrite(latch)
            // The write-characteristic API changed at API 33: the old
            // setValue()+writeCharacteristic(char) path was deprecated for a new
            // writeCharacteristic(char, value, writeType) overload.
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = writeType
                    ch.value = value
                    gatt.writeCharacteristic(ch)
                }
            }
            if (!started) return BtOpResult("failed to start write of $characteristic")
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return BtOpResult("timed out writing $characteristic")
            }
            if (!conn.writeOk) return BtOpResult("write of $characteristic failed")
            BtOpResult()
        } catch (e: SecurityException) {
            BtOpResult("bluetooth connect permission denied: ${e.message}")
        }
    }

    override fun gattDisconnect(address: String): BtOpResult {
        val conn = gattConnections.remove(address)
            ?: return BtOpResult("not connected: no GATT connection to $address")
        conn.close()
        return BtOpResult()
    }

    // -- Classic SPP --------------------------------------------------------

    override fun sppConnect(address: String, uuid: String, timeoutMs: Long): BtOpResult {
        val device = remoteDevice(address) ?: return BtOpResult("unknown device address: $address")
        val serviceUuid = try {
            UUID.fromString(uuid)
        } catch (_: IllegalArgumentException) {
            return BtOpResult("invalid SPP service UUID: $uuid")
        }
        sppConnections.remove(address)?.close()
        return try {
            val socket = device.createRfcommSocketToServiceRecord(serviceUuid)
            // Discovery makes connect() slow/unreliable; cancel it first (best effort).
            try {
                adapter?.cancelDiscovery()
            } catch (_: SecurityException) {
                // Missing scan permission - connect() still works.
            }
            val connectError = connectSocketWithTimeout(socket, timeoutMs)
            if (connectError != null) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                return BtOpResult(connectError)
            }
            val conn = SppConnection(socket)
            conn.startReader()
            sppConnections[address] = conn
            BtOpResult()
        } catch (e: SecurityException) {
            BtOpResult("bluetooth connect permission denied: ${e.message}")
        } catch (e: IOException) {
            BtOpResult("could not open an SPP socket to $address: ${e.message}")
        }
    }

    override fun sppSend(address: String, value: ByteArray, timeoutMs: Long): BtOpResult {
        val conn = sppConnections[address] ?: return BtOpResult(NOT_CONNECTED_SPP)
        return conn.send(value)
    }

    override fun sppRecv(address: String, timeoutMs: Long, maxBytes: Int): SppRecvResult {
        val conn = sppConnections[address] ?: return SppRecvResult(error = NOT_CONNECTED_SPP)
        return SppRecvResult(value = conn.drain(timeoutMs, maxBytes))
    }

    override fun sppDisconnect(address: String): BtOpResult {
        val conn = sppConnections.remove(address)
            ?: return BtOpResult("not connected: no SPP connection to $address")
        conn.close()
        return BtOpResult()
    }

    // -- Helpers ------------------------------------------------------------

    private fun remoteDevice(address: String): BluetoothDevice? = try {
        adapter?.getRemoteDevice(address)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun deviceType(device: BluetoothDevice): String = when (
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
    }

    private fun readServices(gatt: BluetoothGatt): List<GattService> =
        gatt.services.orEmpty().map { service ->
            GattService(
                uuid = service.uuid.toString(),
                characteristics = service.characteristics.orEmpty().map { ch ->
                    GattCharacteristic(ch.uuid.toString(), propertyNames(ch.properties))
                },
            )
        }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        service: String,
        characteristic: String,
    ): BluetoothGattCharacteristic? = try {
        gatt.getService(UUID.fromString(service))?.getCharacteristic(UUID.fromString(characteristic))
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun propertyNames(properties: Int): List<String> = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write_no_response")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }

    @Suppress("DEPRECATION")
    private fun intentDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun connectSocketWithTimeout(socket: BluetoothSocket, timeoutMs: Long): String? {
        val error = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        Thread {
            try {
                socket.connect()
            } catch (e: IOException) {
                error.set("SPP connect failed: ${e.message}")
            } catch (e: SecurityException) {
                error.set("bluetooth connect permission denied: ${e.message}")
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true; start() }
        return if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            // connect() has no timeout param; closing the socket unblocks it.
            try {
                socket.close()
            } catch (_: IOException) {
            }
            "timed out connecting SPP socket"
        } else {
            error.get()
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * One live GATT connection plus the callback that bridges its async events
     * back to the blocking source methods via per-operation latches.
     */
    private inner class GattConnection {
        @Volatile var gatt: BluetoothGatt? = null

        @Volatile private var connectLatch: CountDownLatch? = null
        @Volatile var discovered: Boolean = false

        @Volatile private var readLatch: CountDownLatch? = null
        @Volatile var readOk: Boolean = false
        @Volatile var readValue: ByteArray? = null

        @Volatile private var writeLatch: CountDownLatch? = null
        @Volatile var writeOk: Boolean = false

        fun beginConnect(latch: CountDownLatch) {
            discovered = false
            connectLatch = latch
        }

        fun beginRead(latch: CountDownLatch) {
            readOk = false
            readValue = null
            readLatch = latch
        }

        fun beginWrite(latch: CountDownLatch) {
            writeOk = false
            writeLatch = latch
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    try {
                        g.discoverServices()
                    } catch (_: SecurityException) {
                        discovered = false
                        connectLatch?.countDown()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    discovered = false
                    connectLatch?.countDown()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                discovered = status == BluetoothGatt.GATT_SUCCESS
                connectLatch?.countDown()
            }

            // API 33+ delivers the value alongside the callback.
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                readOk = status == BluetoothGatt.GATT_SUCCESS
                readValue = if (readOk) value else null
                readLatch?.countDown()
            }

            // Pre-33 path: value lives on the characteristic.
            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                readOk = status == BluetoothGatt.GATT_SUCCESS
                readValue = if (readOk) characteristic.value else null
                readLatch?.countDown()
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                writeOk = status == BluetoothGatt.GATT_SUCCESS
                writeLatch?.countDown()
            }
        }

        fun close() {
            val g = gatt
            gatt = null
            try {
                g?.disconnect()
                g?.close()
            } catch (_: SecurityException) {
            }
        }
    }

    /**
     * One live RFCOMM/SPP socket. A daemon reader thread drains the input
     * stream into a buffer so `bt_spp_recv` can pull whatever has arrived.
     */
    private inner class SppConnection(private val socket: BluetoothSocket) {
        private val buffer = ByteArrayOutputStream()
        private val lock = Any()
        @Volatile private var running = true
        private var reader: Thread? = null

        fun startReader() {
            reader = Thread {
                val input = try {
                    socket.inputStream
                } catch (_: IOException) {
                    return@Thread
                }
                val chunk = ByteArray(1024)
                while (running) {
                    val n = try {
                        input.read(chunk)
                    } catch (_: IOException) {
                        break
                    }
                    if (n < 0) break
                    if (n > 0) synchronized(lock) { buffer.write(chunk, 0, n) }
                }
            }.apply { isDaemon = true; start() }
        }

        fun send(bytes: ByteArray): BtOpResult = try {
            val out = socket.outputStream
            out.write(bytes)
            out.flush()
            BtOpResult()
        } catch (e: IOException) {
            BtOpResult("SPP write failed: ${e.message}")
        }

        fun drain(timeoutMs: Long, maxBytes: Int): ByteArray {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                synchronized(lock) {
                    if (buffer.size() > 0) return takeLocked(maxBytes)
                }
                sleepQuietly(20)
            }
            synchronized(lock) {
                return takeLocked(maxBytes)
            }
        }

        // Caller holds [lock].
        private fun takeLocked(maxBytes: Int): ByteArray {
            val all = buffer.toByteArray()
            buffer.reset()
            return if (all.size <= maxBytes) {
                all
            } else {
                // Keep the overflow buffered for the next recv.
                buffer.write(all, maxBytes, all.size - maxBytes)
                all.copyOfRange(0, maxBytes)
            }
        }

        fun close() {
            running = false
            try {
                socket.close()
            } catch (_: IOException) {
            }
            reader?.interrupt()
        }
    }

    private companion object {
        const val NOT_CONNECTED_GATT =
            "not connected: call bt_gatt_connect first for this device"
        const val NOT_CONNECTED_SPP =
            "not connected: call bt_spp_connect first for this device"
    }
}
