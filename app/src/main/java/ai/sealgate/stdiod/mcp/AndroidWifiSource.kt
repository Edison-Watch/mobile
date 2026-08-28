package ai.sealgate.stdiod.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager

/**
 * Production [WifiSource]. Enabled/connected come from [WifiManager] and
 * [ConnectivityManager]/[NetworkCapabilities]; SSID and link speed come from
 * the current [WifiInfo].
 */
class AndroidWifiSource(private val context: Context) : WifiSource {

    override fun snapshot(): WifiSnapshot {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val capabilities =
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val connected =
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val info = if (connected) currentWifiInfo(wifiManager) else null
        val linkSpeed = info?.linkSpeed?.takeIf { it > 0 }
        // Without location permission the platform reports the literal
        // "<unknown ssid>"; map that (and the quoting) to a plain name or null.
        val ssid = info?.ssid
            ?.takeIf { it != WifiManager.UNKNOWN_SSID }
            ?.removeSurrounding("\"")
            ?.takeIf(String::isNotEmpty)

        return WifiSnapshot(
            enabled = wifiManager.isWifiEnabled,
            connected = connected,
            linkSpeedMbps = linkSpeed,
            ssid = ssid,
        )
    }

    // WifiManager.connectionInfo is deprecated since API 31 (the replacement,
    // NetworkCapabilities.transportInfo, only carries a WifiInfo from API 29
    // and needs a NetworkCallback for the SSID before 31); on minSdk 26 the
    // deprecated getter is the only synchronous way to read SSID + link speed.
    @Suppress("DEPRECATION")
    private fun currentWifiInfo(wifiManager: WifiManager): WifiInfo? = wifiManager.connectionInfo
}
