package ai.sealgate.stdiod

import android.content.Context

/**
 * Persisted connection settings, so the tunnel can be configured from the
 * screen instead of by editing code. SharedPreferences is enough for a few
 * strings; the credential never leaves the device except as the tunnel's
 * bearer header.
 *
 * Two auth modes share this store: a pasted API key, or an OAuth `ewc_` client
 * credential from device sign-in. OAuth additionally persists the
 * backend-issued device id (sent as the tunnel's device id) and the client
 * installation id (passed back on re-authentication so the same device row is
 * rotated instead of a new one being created).
 */
object TunnelSettings {

    /**
     * Default gateway: the demo environment. The release backend does not
     * accept `os=android` until the current main ships in a release cut, so
     * pointing the experiment at demo by default saves everyone the first
     * confused half hour. Users can change it in the UI.
     */
    const val DEFAULT_GATEWAY_URL = "wss://demo-dashboard.sealgate.ai/api/v1/stdio-tunnel/ws"

    fun load(context: Context): TunnelConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TunnelConfig(
            gatewayUrl = prefs.getString(KEY_GATEWAY_URL, null) ?: DEFAULT_GATEWAY_URL,
            authToken = prefs.getString(KEY_AUTH_TOKEN, null).orEmpty(),
            deviceId = prefs.getString(KEY_DEVICE_ID, null)?.ifBlank { null },
        )
    }

    /** The client installation id from the last OAuth sign-in, if any. */
    fun clientInstallationId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CLIENT_INSTALLATION_ID, null)
            ?.ifBlank { null }

    fun save(context: Context, config: TunnelConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GATEWAY_URL, config.gatewayUrl)
            .putString(KEY_AUTH_TOKEN, config.authToken)
            .apply {
                if (config.deviceId.isNullOrBlank()) {
                    remove(KEY_DEVICE_ID)
                } else {
                    putString(KEY_DEVICE_ID, config.deviceId)
                }
            }
            .remove(LEGACY_KEY_BASH_MODE)
            .apply()
    }

    /**
     * Persist the outcome of an OAuth sign-in: the gateway used, the `ewc_`
     * credential, the backend device id, and the client installation id for
     * later re-authentication.
     */
    fun saveOAuthResult(
        context: Context,
        gatewayUrl: String,
        accessToken: String,
        deviceId: String,
        clientInstallationId: String,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GATEWAY_URL, gatewayUrl)
            .putString(KEY_AUTH_TOKEN, accessToken)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_CLIENT_INSTALLATION_ID, clientInstallationId)
            .remove(LEGACY_KEY_BASH_MODE)
            .apply()
    }

    private const val PREFS = "tunnel_settings"
    private const val KEY_GATEWAY_URL = "gateway_url"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_CLIENT_INSTALLATION_ID = "client_installation_id"
    private const val LEGACY_KEY_BASH_MODE = "bash_mode"
}
