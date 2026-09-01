package ai.sealgate.stdiod

import android.content.Context

/**
 * Persisted connection settings, so the tunnel can be configured from the
 * screen instead of by editing code. SharedPreferences is enough for two
 * strings; the API key never leaves the device except as the tunnel's
 * bearer header.
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
        )
    }

    fun save(context: Context, config: TunnelConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GATEWAY_URL, config.gatewayUrl)
            .putString(KEY_AUTH_TOKEN, config.authToken)
            .remove(LEGACY_KEY_BASH_MODE)
            .apply()
    }

    private const val PREFS = "tunnel_settings"
    private const val KEY_GATEWAY_URL = "gateway_url"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val LEGACY_KEY_BASH_MODE = "bash_mode"
}
