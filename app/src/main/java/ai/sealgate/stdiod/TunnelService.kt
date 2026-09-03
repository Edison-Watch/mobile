package ai.sealgate.stdiod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ai.sealgate.stdiod.mcp.AndroidBatterySource
import ai.sealgate.stdiod.mcp.AndroidBluetoothSource
import ai.sealgate.stdiod.mcp.AndroidCameraSource
import ai.sealgate.stdiod.mcp.AndroidDeviceInfo
import ai.sealgate.stdiod.mcp.AndroidUsbSource
import ai.sealgate.stdiod.mcp.AndroidWifiSource
import ai.sealgate.stdiod.mcp.BatteryModule
import ai.sealgate.stdiod.mcp.BashModule
import ai.sealgate.stdiod.mcp.BluetoothModule
import ai.sealgate.stdiod.mcp.CameraModule
import ai.sealgate.stdiod.mcp.AndroidComputerSource
import ai.sealgate.stdiod.mcp.ComputerAccessibilityService
import ai.sealgate.stdiod.mcp.ComputerModule
import ai.sealgate.stdiod.mcp.DeviceInfoModule
import ai.sealgate.stdiod.mcp.MobileCommandRouter
import ai.sealgate.stdiod.mcp.QuickJsMobileBashRuntime
import ai.sealgate.stdiod.mcp.UsbModule
import ai.sealgate.stdiod.mcp.WifiModule
import ai.sealgate.stdiod.tunnel.DeviceIdentityStore
import ai.sealgate.stdiod.tunnel.TunnelClient
import ai.sealgate.stdiod.tunnel.TunnelState
import ai.sealgate.stdiod.ui.NotificationTunnelArtwork
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the stdio tunnel's lifecycle.
 *
 * On a device the tunnel is a long-lived, single outbound WebSocket connection
 * to the gateway. Android will kill background work aggressively, so the tunnel
 * runs as a foreground service with an ongoing notification.
 *
 * [connect] exposes one restricted Mobile Bash MCP server backed by in-process
 * Android capability modules. Android never spawns a local stdio process.
 */
class TunnelService : LifecycleService() {

    private var tunnelJob: Job? = null
    private var connectJob: Job? = null
    private var connectGeneration = 0
    private var notificationAnimationJob: Job? = null
    private var tunnelClient: TunnelClient? = null
    private val sealGateLogo by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.sealgate_logo)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_DISABLE_COMPUTER || intent?.action == ACTION_REFRESH_COMPUTER) {
            if (intent.action == ACTION_DISABLE_COMPUTER) {
                ComputerUseSettings.setEnabled(this, false)
                ComputerAccessibilityService.disable()
            }
            val state = TunnelServiceState.state.value ?: TunnelState.Connecting
            startForeground(NOTIFICATION_ID, buildNotification(state))
            return START_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(TunnelState.Connecting))

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

        // Restart the tunnel if we were re-delivered a start command.
        connect(config)

        // START_STICKY: keep the tunnel alive; the OS restarts us if killed.
        return START_STICKY
    }

    private fun connect(config: TunnelConfig) {
        // A replacement waits for the old module to drain; do not leave the UI
        // claiming the previous client is still connected during that window.
        TunnelServiceState.publish(TunnelState.Connecting)
        val generation = ++connectGeneration
        val previousClient = tunnelClient
        tunnelClient = null
        previousClient?.stop()
        tunnelJob?.cancel()
        notificationAnimationJob?.cancel()
        val previousConnectJob = connectJob
        connectJob = lifecycleScope.launch {
            previousConnectJob?.join()
            previousClient?.stopAndAwait()
            if (generation != connectGeneration || !isActive) return@launch
            startClient(config)
        }
    }

    private fun startClient(config: TunnelConfig) {
        Log.i(TAG, "Tunnel starting -> ${config.gatewayUrl}")
        val identity = DeviceIdentityStore.load(this, BuildConfig.VERSION_NAME)
        val capabilityModules = buildList {
            add(DeviceInfoModule(AndroidDeviceInfo))
            add(BatteryModule(AndroidBatterySource(this@TunnelService)))
            add(WifiModule(AndroidWifiSource(this@TunnelService)))
            add(BluetoothModule(AndroidBluetoothSource(this@TunnelService)))
            add(UsbModule(AndroidUsbSource(this@TunnelService)))
            add(CameraModule(AndroidCameraSource(this@TunnelService)))
            if (BuildConfig.COMPUTER_USE_AVAILABLE) {
                add(ComputerModule(AndroidComputerSource(this@TunnelService)))
            }
        }
        val router = MobileCommandRouter(capabilityModules)
        val exposedModules = listOf(
            BashModule(
                runtimeFactory = {
                    QuickJsMobileBashRuntime(
                        sourceProvider = {
                            assets.open(BASH_RUNTIME_ASSET).bufferedReader().use { it.readText() }
                        },
                        commandRouter = router,
                    )
                },
                closeCapabilities = {
                    capabilityModules.filterIsInstance<AutoCloseable>().forEach(AutoCloseable::close)
                },
            ),
        )
        val client = TunnelClient(
            gatewayUrl = config.gatewayUrl,
            authToken = config.authToken,
            identity = identity,
            modules = exposedModules,
            scope = lifecycleScope,
        )
        tunnelClient = client
        client.start()

        // Keep the ongoing notification (and the in-app status line, via
        // TunnelServiceState) honest about the tunnel's state.
        tunnelJob = lifecycleScope.launch {
            client.state.collect { state ->
                TunnelServiceState.publish(state)
                val manager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationAnimationJob?.cancel()
                manager.notify(NOTIFICATION_ID, buildNotification(state, frame = 0))
                if (state != TunnelState.Disconnected && systemAnimationsEnabled()) {
                    notificationAnimationJob = animateNotification(state, manager)
                }
            }
        }
    }

    private fun animateNotification(
        state: TunnelState,
        manager: NotificationManager,
    ): Job = lifecycleScope.launch {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        var frame = 1
        while (isActive) {
            delay(
                if (powerManager.isInteractive) {
                    NOTIFICATION_FRAME_INTERVAL_MILLIS
                } else {
                    NOTIFICATION_SCREEN_OFF_INTERVAL_MILLIS
                },
            )
            if (!powerManager.isInteractive) continue
            manager.notify(NOTIFICATION_ID, buildNotification(state, frame))
            frame = (frame + 1) % NotificationTunnelArtwork.FRAME_COUNT
        }
    }

    override fun onDestroy() {
        connectGeneration++
        connectJob?.cancel()
        connectJob = null
        tunnelClient?.stop()
        tunnelClient = null
        tunnelJob?.cancel()
        tunnelJob = null
        notificationAnimationJob?.cancel()
        notificationAnimationJob = null
        // No service means no tunnel: null (not Disconnected, which implies a
        // pending reconnect) so observers show "stopped".
        TunnelServiceState.publish(null)
        super.onDestroy()
    }

    private fun buildNotification(state: TunnelState, frame: Int = 0): Notification {
        ensureChannel()
        val presentation = notificationPresentation(state)
        val status = getString(presentation.status)
        val statusColor = ContextCompat.getColor(this, presentation.color)
        val route = getString(
            if (BuildConfig.COMPUTER_USE_AVAILABLE && ComputerUseSettings.isEnabled(this)) {
                R.string.notification_route_computer
            } else {
                R.string.notification_route
            },
        )
        val diagram = NotificationTunnelArtwork.render(this, state, frame)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val pictureStyle = NotificationCompat.BigPictureStyle()
            .bigPicture(diagram)
            .bigLargeIcon(null as android.graphics.Bitmap?)
            .setBigContentTitle(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pictureStyle.setContentDescription(
                getString(R.string.notification_diagram_description),
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            // A native template uses the full width Android makes available to
            // notifications and stays far denser than a decorated RemoteViews panel.
            .setContentTitle(status)
            .setContentText(route)
            .setSmallIcon(R.drawable.ic_stat_sealgate)
            .setLargeIcon(sealGateLogo)
            .setStyle(pictureStyle)
            .setColor(statusColor)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
        if (BuildConfig.COMPUTER_USE_AVAILABLE && ComputerUseSettings.isEnabled(this)) {
            val disableComputer = PendingIntent.getService(
                this,
                1,
                Intent(this, TunnelService::class.java).setAction(ACTION_DISABLE_COMPUTER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, getString(R.string.action_disable_computer), disableComputer)
        }
        return builder.build()
    }

    private fun notificationPresentation(state: TunnelState): NotificationPresentation =
        when (state) {
            TunnelState.Connected -> NotificationPresentation(
                status = R.string.tunnel_state_connected,
                color = R.color.circuit_green,
            )
            TunnelState.Connecting -> NotificationPresentation(
                status = R.string.tunnel_state_connecting,
                color = R.color.signal_amber,
            )
            TunnelState.Disconnected -> NotificationPresentation(
                status = R.string.tunnel_state_disconnected,
                color = R.color.infra_red,
            )
        }

    private fun systemAnimationsEnabled(): Boolean =
        Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tunnel_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.tunnel_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private data class NotificationPresentation(
        val status: Int,
        val color: Int,
    )

    companion object {
        private const val TAG = "TunnelService"
        private const val CHANNEL_ID = "mobile_tunnel_live"
        private const val LEGACY_CHANNEL_ID = "studio_d_live_tunnel"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_FRAME_INTERVAL_MILLIS = 500L
        private const val NOTIFICATION_SCREEN_OFF_INTERVAL_MILLIS = 10_000L
        private const val BASH_RUNTIME_ASSET = "mobile-bash-runtime.js"
        private const val ACTION_DISABLE_COMPUTER = "ai.sealgate.stdiod.action.DISABLE_COMPUTER"
        private const val ACTION_REFRESH_COMPUTER = "ai.sealgate.stdiod.action.REFRESH_COMPUTER"

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

        fun refreshComputerControl(context: Context) {
            context.startForegroundService(
                Intent(context, TunnelService::class.java).setAction(ACTION_REFRESH_COMPUTER),
            )
        }
    }
}
