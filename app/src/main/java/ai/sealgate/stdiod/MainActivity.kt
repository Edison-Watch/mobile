package ai.sealgate.stdiod

import android.Manifest
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ai.sealgate.stdiod.databinding.ActivityMainBinding
import ai.sealgate.stdiod.tunnel.TunnelState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single-screen control surface for the tunnel: a status line and Start/Stop
 * buttons that drive [TunnelService]. Intentionally minimal (no Compose) so the
 * template is a small, familiar starting point.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var tunnelState: TunnelState? = null

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private val requestBluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        maybeRequestNotificationPermission()
        maybeRequestBluetoothPermission()

        val stored = TunnelSettings.load(this)
        binding.gatewayUrlInput.setText(stored.gatewayUrl)
        binding.apiKeyInput.setText(stored.authToken)
        binding.settingsPanel.visibility = if (stored.isValid()) View.GONE else View.VISIBLE

        binding.swipeRefresh.setColorSchemeResources(R.color.core_cyan)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.baseline_black)
        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            val config = TunnelSettings.load(this)
            if (tunnelState == null || !config.isValid()) {
                binding.swipeRefresh.isRefreshing = false
                return@setOnRefreshListener
            }

            // A refresh is a controlled reconnect, not a configuration reset:
            // preserve the active endpoint and credentials.
            TunnelService.start(this, config)
            lifecycleScope.launch {
                // The status view owns ongoing progress; the refresh indicator
                // only acknowledges that the reconnect request was accepted.
                delay(REFRESH_INDICATOR_MILLIS)
                binding.swipeRefresh.isRefreshing = false
            }
        }

        binding.settingsButton.setOnClickListener {
            val showing = binding.settingsPanel.visibility == View.VISIBLE
            binding.settingsPanel.visibility = if (showing) View.GONE else View.VISIBLE
            binding.settingsButton.contentDescription = getString(
                if (showing) R.string.action_show_settings else R.string.action_hide_settings,
            )
        }

        binding.connectionInfoButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.connection_info_title)
                .setMessage(
                    getString(R.string.connection_info_message) + "\n\n" +
                        getString(
                            R.string.connection_info_version,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                        ),
                )
                .setPositiveButton(R.string.action_close, null)
                .show()
        }

        binding.tunnelButton.setOnClickListener {
            binding.tunnelButton.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (tunnelState != null) {
                TunnelService.stop(this)
                return@setOnClickListener
            }
            val config = TunnelConfig(
                gatewayUrl = binding.gatewayUrlInput.text?.toString()?.trim().orEmpty(),
                authToken = binding.apiKeyInput.text?.toString()?.trim().orEmpty(),
            )
            binding.gatewayUrlLayout.error = null
            binding.apiKeyLayout.error = null
            if (!config.isValid()) {
                if (!config.gatewayUrl.startsWith("wss://") && !config.gatewayUrl.startsWith("ws://")) {
                    binding.gatewayUrlLayout.error = getString(R.string.error_gateway_url)
                }
                if (config.authToken.isBlank()) {
                    binding.apiKeyLayout.error = getString(R.string.error_api_key)
                }
                binding.settingsPanel.visibility = View.VISIBLE
                return@setOnClickListener
            }
            TunnelSettings.save(this, config)
            binding.settingsPanel.visibility = View.GONE
            TunnelService.start(this, config)
        }

        // The service owns the tunnel, so the status line mirrors its published
        // state rather than guessing from button presses; this also survives
        // the activity being recreated while the tunnel keeps running.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TunnelServiceState.state.collect { state ->
                    tunnelState = state
                    val text = when (state) {
                        TunnelState.Connected -> getString(R.string.tunnel_state_connected)
                        TunnelState.Connecting -> getString(R.string.tunnel_state_connecting)
                        TunnelState.Disconnected -> getString(R.string.tunnel_state_disconnected)
                        null -> getString(R.string.status_stopped)
                    }
                    renderState(state, text)
                }
            }
        }
    }

    private fun renderState(state: TunnelState?, text: String) {
        val stateColor = ContextCompat.getColor(
            this,
            when (state) {
                TunnelState.Connected -> R.color.circuit_green
                TunnelState.Connecting -> R.color.signal_amber
                TunnelState.Disconnected, null -> R.color.infra_red
            },
        )
        binding.statusText.text = text
        binding.statusText.setTextColor(stateColor)
        binding.statusIndicator.backgroundTintList = ColorStateList.valueOf(stateColor)
        binding.tunnelVisual.setState(state)
        binding.swipeRefresh.isEnabled = state != null
        binding.tunnelButton.text = getString(
            if (state == null) R.string.action_connect else R.string.action_stop,
        )
        binding.tunnelButton.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (state == null) R.color.core_cyan else R.color.infra_red,
            ),
        )
        binding.tunnelVisual.contentDescription = getString(
            when (state) {
                TunnelState.Connected -> R.string.tunnel_visual_connected
                TunnelState.Connecting -> R.string.tunnel_visual_connecting
                TunnelState.Disconnected -> R.string.tunnel_visual_reconnecting
                null -> R.string.tunnel_visual_stopped
            },
        )
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Best effort, like notifications: the bluetooth module also reports a
    // missing "Nearby devices" permission in-band if the user declines here.
    // From Android 12 the control tools need both BLUETOOTH_CONNECT (connect,
    // pair, GATT, SPP) and BLUETOOTH_SCAN (bt_scan); request whichever is not
    // yet granted.
    private fun maybeRequestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val wanted = listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) requestBluetoothPermissions.launch(wanted.toTypedArray())
    }

    companion object {
        private const val REFRESH_INDICATOR_MILLIS = 650L
    }
}
