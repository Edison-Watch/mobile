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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        maybeRequestNotificationPermission()

        binding.startButton.setOnClickListener {
            // TODO: read a real gateway URL + token from settings/DataStore.
            val config = TunnelConfig(
                gatewayUrl = "wss://dashboard.sealgate.ai/api/v1/stdio-tunnel/ws",
                authToken = "replace-me",
            )
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
}
