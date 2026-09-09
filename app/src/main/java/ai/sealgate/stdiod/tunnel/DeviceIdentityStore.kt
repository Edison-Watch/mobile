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

    /**
     * @param preferredDeviceId when non-blank (an OAuth `ewd_...` id), it is
     *   authoritative: the gateway requires the tunnel's device id to match the
     *   one bound to the `ewc_` credential. It is persisted so it stays stable
     *   across restarts. When null/blank (API-key mode), a locally minted UUID
     *   is used, matching the desktop daemon's config-dir identity behaviour.
     */
    fun load(
        context: Context,
        clientVersion: String,
        preferredDeviceId: String? = null,
    ): DeviceIdentity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val supplied = preferredDeviceId?.ifBlank { null }
        val stored = prefs.getString(KEY_DEVICE_ID, null)
        // A caller-supplied (OAuth `ewd_`) id is authoritative and already persisted
        // by TunnelSettings, so it must NOT overwrite the locally minted UUID here -
        // otherwise a later switch back to API-key mode would present the backend's
        // device id as its own. Only persist an id we mint ourselves.
        val deviceId = supplied ?: stored ?: UUID.randomUUID().toString()
        if (supplied == null && deviceId != stored) {
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        val model = Build.MODEL ?: "Android device"
        return DeviceIdentity(
            deviceId = deviceId,
            hostname = model,
            label = deviceLabel(),
            clientVersion = clientVersion,
        )
    }

    /** Human-readable device name (e.g. "Google Pixel 8"); no side effects. */
    fun deviceLabel(): String {
        val model = Build.MODEL ?: "Android device"
        val manufacturer = Build.MANUFACTURER ?: ""
        return if (manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    private const val PREFS = "tunnel_identity"
    private const val KEY_DEVICE_ID = "device_id"
}
