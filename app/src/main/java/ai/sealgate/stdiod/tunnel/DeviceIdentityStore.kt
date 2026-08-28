package ai.sealgate.stdiod.tunnel

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * Loads (or mints on first run) this install's stable device identity.
 *
 * The device_id must survive restarts — the backend keys the device row and
 * its server enablements on it — so it is a UUID minted once and kept in
 * SharedPreferences. Reinstalling the app is a new device, which matches how
 * the desktop daemon's config-dir identity behaves.
 */
object DeviceIdentityStore {

    fun load(context: Context, clientVersion: String): DeviceIdentity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        val model = Build.MODEL ?: "Android device"
        val manufacturer = Build.MANUFACTURER ?: ""
        val label = if (manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
        return DeviceIdentity(
            deviceId = deviceId,
            hostname = model,
            label = label,
            clientVersion = clientVersion,
        )
    }

    private const val PREFS = "tunnel_identity"
    private const val KEY_DEVICE_ID = "device_id"
}
