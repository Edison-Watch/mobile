package ai.sealgate.stdiod

import android.content.Context

/** The local kill switch for camera capture. Defaults off; Mobile Bash asks in-band when disabled. */
object CameraSettings {
    fun isEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    internal fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "camera_settings"
    internal const val KEY_ENABLED = "enabled"
}
