package ai.sealgate.stdiod

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ai.sealgate.stdiod.databinding.ActivityMainBinding

/**
 * Single-screen control surface for the tunnel: a status line and Start/Stop
 * buttons that drive [TunnelService]. Intentionally minimal (no Compose) so the
 * template is a small, familiar starting point.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private val requestBluetoothConnect =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        maybeRequestNotificationPermission()
        maybeRequestBluetoothPermission()

        val stored = TunnelSettings.load(this)
        binding.gatewayUrlInput.setText(stored.gatewayUrl)
        binding.apiKeyInput.setText(stored.authToken)

        binding.startButton.setOnClickListener {
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
                return@setOnClickListener
            }
            TunnelSettings.save(this, config)
            TunnelService.start(this, config)
            setStatus(getString(R.string.status_running))
        }

        binding.stopButton.setOnClickListener {
            TunnelService.stop(this)
            setStatus(getString(R.string.status_stopped))
        }

        setStatus(getString(R.string.status_stopped))
    }

    private fun setStatus(text: String) {
        binding.statusText.text = getString(R.string.status_prefix, text)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Best effort, like notifications: the bluetooth module also reports the
    // missing "Nearby devices" permission in-band if the user declines here.
    private fun maybeRequestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestBluetoothConnect.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
}
