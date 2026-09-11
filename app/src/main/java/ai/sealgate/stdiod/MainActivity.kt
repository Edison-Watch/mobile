package ai.sealgate.stdiod

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ai.sealgate.stdiod.databinding.ActivityMainBinding
import ai.sealgate.stdiod.tunnel.DeviceAuthClient
import ai.sealgate.stdiod.tunnel.DeviceIdentityStore
import ai.sealgate.stdiod.tunnel.GatewayUrls
import ai.sealgate.stdiod.tunnel.PendingGrant
import ai.sealgate.stdiod.tunnel.TunnelState
import ai.sealgate.stdiod.mcp.ComputerAccessibilityService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private var signInJob: Job? = null
    private var syncingComputerControlSwitch = false
    private var syncingCameraSwitch = false
    private val computerUsePreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key == ComputerUseSettings.KEY_ENABLED &&
                BuildConfig.COMPUTER_USE_AVAILABLE &&
                ::binding.isInitialized
            ) {
                renderComputerControl()
            }
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderCameraControl() }

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

        binding.computerControlPanel.visibility =
            if (BuildConfig.COMPUTER_USE_AVAILABLE) View.VISIBLE else View.GONE
        if (BuildConfig.COMPUTER_USE_AVAILABLE) {
            binding.computerControlSwitch.isChecked = ComputerUseSettings.isEnabled(this)
            binding.computerControlSwitch.setOnCheckedChangeListener { _, enabled ->
                if (syncingComputerControlSwitch) return@setOnCheckedChangeListener
                ComputerUseSettings.setEnabled(this, enabled)
                if (!enabled) ComputerAccessibilityService.disable()
                if (TunnelServiceState.state.value != null) TunnelService.refreshComputerControl(this)
                renderComputerControl()
                if (enabled && !ComputerAccessibilityService.isConnected()) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            renderComputerControl()
        }

        binding.cameraControlSwitch.isChecked = CameraSettings.isEnabled(this)
        binding.cameraControlSwitch.setOnCheckedChangeListener { _, enabled ->
            if (syncingCameraSwitch) return@setOnCheckedChangeListener
            CameraSettings.setEnabled(this, enabled)
            renderCameraControl()
            if (enabled && !hasCameraPermission()) {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
        renderCameraControl()

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

        binding.signInButton.setOnClickListener {
            binding.signInButton.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            startDeviceSignIn()
        }

        binding.tunnelButton.setOnClickListener {
            binding.tunnelButton.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (tunnelState != null) {
                TunnelService.stop(this)
                return@setOnClickListener
            }
            val config = configFromInputs()
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

    override fun onResume() {
        super.onResume()
        if (BuildConfig.COMPUTER_USE_AVAILABLE && ::binding.isInitialized) renderComputerControl()
        if (::binding.isInitialized) renderCameraControl()
    }

    override fun onStart() {
        super.onStart()
        if (BuildConfig.COMPUTER_USE_AVAILABLE) {
            ComputerUseSettings.preferences(this)
                .registerOnSharedPreferenceChangeListener(computerUsePreferenceListener)
            renderComputerControl()
        }
        // Resume a sign-in that a process kill interrupted mid-approval. Guarded
        // (no-op when a poll is already running or no grant is stored), so the
        // common foreground-return case does nothing.
        if (::binding.isInitialized) maybeResumeSignIn()
    }

    override fun onStop() {
        if (BuildConfig.COMPUTER_USE_AVAILABLE) {
            ComputerUseSettings.preferences(this)
                .unregisterOnSharedPreferenceChangeListener(computerUsePreferenceListener)
        }
        super.onStop()
    }

    private fun renderComputerControl() {
        val enabled = ComputerUseSettings.isEnabled(this)
        if (binding.computerControlSwitch.isChecked != enabled) {
            syncingComputerControlSwitch = true
            try {
                binding.computerControlSwitch.isChecked = enabled
            } finally {
                syncingComputerControlSwitch = false
            }
        }
        binding.computerControlStatus.setText(
            when {
                !enabled -> R.string.computer_control_off
                ComputerAccessibilityService.isConnected() -> R.string.computer_control_ready
                else -> R.string.computer_control_needs_accessibility
            },
        )
    }

    private fun renderCameraControl() {
        val enabled = CameraSettings.isEnabled(this)
        if (binding.cameraControlSwitch.isChecked != enabled) {
            syncingCameraSwitch = true
            try {
                binding.cameraControlSwitch.isChecked = enabled
            } finally {
                syncingCameraSwitch = false
            }
        }
        binding.cameraControlStatus.setText(
            when {
                !enabled -> R.string.camera_control_off
                hasCameraPermission() -> R.string.camera_control_ready
                else -> R.string.camera_control_needs_permission
            },
        )
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

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

    /**
     * Build a [TunnelConfig] from the visible inputs, carrying the OAuth device
     * id forward when the credential and gateway are unchanged from what was
     * saved. Editing either field is treated as a fresh (API-key) credential
     * with no bound device id.
     */
    private fun configFromInputs(): TunnelConfig {
        val gatewayUrl = binding.gatewayUrlInput.text?.toString()?.trim().orEmpty()
        val authToken = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
        val stored = TunnelSettings.load(this)
        // The OAuth device id is bound to the `ewc_` credential, not the endpoint,
        // so keep it as long as the saved credential is still the one in the field.
        // Editing the gateway URL must not drop it (that would leave the credential
        // unusable); a manually pasted API key simply has no bound device id.
        val deviceId = if (authToken == stored.authToken && authToken.startsWith("ewc_")) {
            stored.deviceId
        } else {
            null
        }
        return TunnelConfig(gatewayUrl = gatewayUrl, authToken = authToken, deviceId = deviceId)
    }

    /**
     * Start the OAuth device-authorization flow: request a code, persist the
     * grant (so a process death mid-approval can resume), then poll.
     */
    private fun startDeviceSignIn() {
        if (signInJob?.isActive == true) return
        val gatewayUrl = binding.gatewayUrlInput.text?.toString()?.trim().orEmpty()
        binding.gatewayUrlLayout.error = null
        val apiBase = GatewayUrls.apiBaseFromWs(gatewayUrl)
        if (apiBase == null) {
            binding.gatewayUrlLayout.error = getString(R.string.error_gateway_url)
            binding.settingsPanel.visibility = View.VISIBLE
            return
        }

        val client = DeviceAuthClient(apiBase)
        val label = DeviceIdentityStore.deviceLabel()
        val existingInstallation = TunnelSettings.clientInstallationId(this)
        binding.signInButton.isEnabled = false
        Toast.makeText(this, R.string.sign_in_starting, Toast.LENGTH_SHORT).show()

        signInJob = lifecycleScope.launch {
            val grant = try {
                client.requestDeviceCode(label, BuildConfig.VERSION_NAME, existingInstallation)
            } catch (e: DeviceAuthClient.DeviceAuthException) {
                binding.signInButton.isEnabled = true
                showSignInError(e.message)
                return@launch
            }
            PendingSignInStore.save(
                this@MainActivity,
                gatewayUrl,
                grant,
                System.currentTimeMillis() + grant.expiresInSeconds.toLong() * 1000L,
            )
            pollGrantToTunnel(client, gatewayUrl, grant, openBrowser = true)
        }
    }

    /**
     * Resume a sign-in whose poll was killed by process death while the user was
     * approving in the browser. No-op when there is no live grant. The browser is
     * not reopened - the user was already there.
     */
    private fun maybeResumeSignIn() {
        if (signInJob?.isActive == true) return
        val saved = PendingSignInStore.load(this) ?: return
        val apiBase = GatewayUrls.apiBaseFromWs(saved.gatewayUrl)
        if (apiBase == null) {
            PendingSignInStore.clear(this)
            return
        }
        binding.signInButton.isEnabled = false
        signInJob = lifecycleScope.launch {
            pollGrantToTunnel(DeviceAuthClient(apiBase), saved.gatewayUrl, saved.grant, openBrowser = false)
        }
    }

    /**
     * Show the approval dialog, poll to completion, and on success persist the
     * credential and start the tunnel. The persisted grant is cleared on success
     * and on terminal failure here, and on explicit user cancel in the dialog
     * handlers; it is deliberately kept on lifecycle cancellation and process
     * kill so [maybeResumeSignIn] can pick it up on the next launch.
     */
    private suspend fun pollGrantToTunnel(
        client: DeviceAuthClient,
        gatewayUrl: String,
        grant: PendingGrant,
        openBrowser: Boolean,
    ) {
        try {
            val dialog = showSignInDialog(grant)
            if (openBrowser) openUri(grant.verificationUriComplete)
            val result = try {
                client.pollForToken(grant)
            } finally {
                dialog.dismiss()
            }
            TunnelSettings.saveOAuthResult(
                context = this,
                gatewayUrl = gatewayUrl,
                accessToken = result.accessToken,
                deviceId = result.deviceId,
                clientInstallationId = result.clientInstallationId,
            )
            binding.gatewayUrlInput.setText(gatewayUrl)
            binding.apiKeyInput.setText(result.accessToken)
            binding.apiKeyLayout.error = null
            binding.settingsPanel.visibility = View.GONE
            Toast.makeText(this, R.string.sign_in_success, Toast.LENGTH_SHORT).show()
            TunnelService.start(this, TunnelConfig(gatewayUrl, result.accessToken, result.deviceId))
            PendingSignInStore.clear(this)
        } catch (e: CancellationException) {
            // Lifecycle cancellation (e.g. the activity is destroyed): leave the
            // grant persisted so it can resume. Explicit user cancel clears it in
            // the dialog handlers before cancelling the job.
            throw e
        } catch (e: DeviceAuthClient.DeviceAuthException) {
            PendingSignInStore.clear(this)
            showSignInError(e.message)
        } finally {
            binding.signInButton.isEnabled = true
        }
    }

    private fun showSignInDialog(grant: PendingGrant): AlertDialog {
        val view = layoutInflater.inflate(R.layout.dialog_device_sign_in, null)
        view.findViewById<TextView>(R.id.signInUri).text = grant.verificationUri
        view.findViewById<TextView>(R.id.signInCode).text = grant.userCode
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sign_in_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.action_open_dashboard, null)
            .setNegativeButton(R.string.action_cancel) { _, _ -> cancelSignIn() }
            .setOnCancelListener { cancelSignIn() }
            .create()
        // Keep the dialog open when "Open dashboard" is tapped so the user can
        // return and watch it flip to connected.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                openUri(grant.verificationUriComplete)
            }
        }
        dialog.show()
        return dialog
    }

    /** User aborted sign-in: drop the persisted grant so it is not resumed, then stop the poll. */
    private fun cancelSignIn() {
        PendingSignInStore.clear(this)
        signInJob?.cancel()
    }

    private fun showSignInError(message: String?) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sign_in_failed_title)
            .setMessage(message ?: getString(R.string.sign_in_failed_title))
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun openUri(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                getString(R.string.sign_in_no_browser, uri),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    companion object {
        private const val REFRESH_INDICATOR_MILLIS = 650L
    }
}
