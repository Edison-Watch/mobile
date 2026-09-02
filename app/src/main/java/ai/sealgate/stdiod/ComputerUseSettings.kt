package ai.sealgate.stdiod

import android.content.Context

/** The local kill switch for computer use. Availability itself is build-time gated. */
object ComputerUseSettings {
    fun isEnabled(context: Context): Boolean =
        BuildConfig.COMPUTER_USE_AVAILABLE && preferences(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "computer_use_settings"
    private const val KEY_ENABLED = "enabled"
}
