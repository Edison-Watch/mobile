package ai.sealgate.stdiod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the stdio tunnel's lifecycle.
 *
 * On a device the tunnel is a long-lived, single outbound WebSocket connection
 * to the gateway. Android will kill background work aggressively, so the tunnel
 * runs as a foreground service with an ongoing notification.
 *
 * This is a template stub: [connect] is where the real work goes - open the
 * WebSocket to [TunnelConfig.gatewayUrl], spawn/attach the local stdio MCP
 * process, and pump bytes between the socket and the process's stdin/stdout,
 * translating stdio framing to the gateway's HTTP/SSE transport. Reconnect with
 * backoff on drop.
 */
class TunnelService : LifecycleService() {

    private var tunnelJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForeground(NOTIFICATION_ID, buildNotification())

        val config = intent?.let {
            TunnelConfig(
                gatewayUrl = it.getStringExtra(TunnelConfig.EXTRA_GATEWAY_URL).orEmpty(),
                authToken = it.getStringExtra(TunnelConfig.EXTRA_AUTH_TOKEN).orEmpty(),
            )
        }

        if (config == null || !config.isValid()) {
            Log.w(TAG, "Missing or invalid TunnelConfig; stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Restart the tunnel loop if we were re-delivered a start command.
        tunnelJob?.cancel()
        tunnelJob = lifecycleScope.launch { connect(config) }

        // START_STICKY: keep the tunnel alive; the OS restarts us if killed.
        return START_STICKY
    }

    /**
     * TODO: implement the tunnel.
     *
     *  1. Open a WebSocket to [config.gatewayUrl] with a bearer auth header.
     *  2. Launch the local stdio MCP server process.
     *  3. Bridge: gateway frames -> process stdin, process stdout -> gateway.
     *  4. Reconnect with exponential backoff on disconnect.
     */
    private suspend fun connect(config: TunnelConfig) {
        Log.i(TAG, "Tunnel starting -> ${config.gatewayUrl}")
        // Placeholder: real WebSocket + stdio bridging goes here.
    }

    override fun onDestroy() {
        tunnelJob?.cancel()
        tunnelJob = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tunnel_notification_title))
            .setContentText(getString(R.string.tunnel_notification_text))
            .setSmallIcon(R.drawable.ic_tunnel)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tunnel_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.tunnel_channel_description) }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TunnelService"
        private const val CHANNEL_ID = "stdio_tunnel"
        private const val NOTIFICATION_ID = 1

        /** Start the tunnel with the given config. */
        fun start(context: Context, config: TunnelConfig) {
            val intent = Intent(context, TunnelService::class.java).apply {
                putExtra(TunnelConfig.EXTRA_GATEWAY_URL, config.gatewayUrl)
                putExtra(TunnelConfig.EXTRA_AUTH_TOKEN, config.authToken)
            }
            context.startForegroundService(intent)
        }

        /** Stop the tunnel. */
        fun stop(context: Context) {
            context.stopService(Intent(context, TunnelService::class.java))
        }
    }
}
