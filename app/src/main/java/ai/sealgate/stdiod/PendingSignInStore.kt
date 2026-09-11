package ai.sealgate.stdiod

import ai.sealgate.stdiod.tunnel.PendingGrant
import android.content.Context

/**
 * Persists an in-flight OAuth device grant so a sign-in that is interrupted by
 * process death (the OS reclaiming the app while the user is approving in the
 * browser) can resume on next launch instead of silently orphaning the grant.
 *
 * Rotation and other configuration changes are already handled by
 * `android:configChanges` on MainActivity, so this store only earns its keep
 * for true process death. The grant is short-lived (minutes) and the stored
 * fields carry the same trust level as the credential they redeem, so they
 * live in the app's private SharedPreferences and are cleared the moment the
 * poll reaches any terminal state.
 */
object PendingSignInStore {

    /** A resumable grant, with [grant] re-expressed against the time remaining. */
    data class Saved(val gatewayUrl: String, val grant: PendingGrant)

    fun save(context: Context, gatewayUrl: String, grant: PendingGrant, expiresAtMillis: Long) {
        context.prefs()
            .edit()
            .putString(KEY_GATEWAY_URL, gatewayUrl)
            .putString(KEY_DEVICE_CODE, grant.deviceCode)
            .putString(KEY_USER_CODE, grant.userCode)
            .putString(KEY_VERIFY_URI, grant.verificationUri)
            .putString(KEY_VERIFY_URI_COMPLETE, grant.verificationUriComplete)
            .putInt(KEY_INTERVAL, grant.intervalSeconds)
            .putString(KEY_CODE_VERIFIER, grant.codeVerifier)
            .putLong(KEY_EXPIRES_AT, expiresAtMillis)
            .apply()
    }

    /**
     * Return the persisted grant with its lifetime rebased on the time left, or
     * null when there is none or it has (all but) expired. Clears an expired or
     * malformed record so a stale grant is never resumed.
     */
    fun load(context: Context, nowMillis: Long = System.currentTimeMillis()): Saved? {
        val prefs = context.prefs()
        val gatewayUrl = prefs.getString(KEY_GATEWAY_URL, null)
        val deviceCode = prefs.getString(KEY_DEVICE_CODE, null)
        val userCode = prefs.getString(KEY_USER_CODE, null)
        val verifyUri = prefs.getString(KEY_VERIFY_URI, null)
        val verifyUriComplete = prefs.getString(KEY_VERIFY_URI_COMPLETE, null)
        val codeVerifier = prefs.getString(KEY_CODE_VERIFIER, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val interval = prefs.getInt(KEY_INTERVAL, 0)
        if (
            gatewayUrl == null || deviceCode == null || userCode == null ||
            verifyUri == null || verifyUriComplete == null || codeVerifier == null
        ) {
            return null
        }
        val remainingSeconds = ((expiresAt - nowMillis) / 1000L).toInt()
        if (remainingSeconds < MIN_RESUME_SECONDS) {
            clear(context)
            return null
        }
        return Saved(
            gatewayUrl = gatewayUrl,
            grant = PendingGrant(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = verifyUri,
                verificationUriComplete = verifyUriComplete,
                expiresInSeconds = remainingSeconds,
                intervalSeconds = interval.coerceAtLeast(1),
                codeVerifier = codeVerifier,
            ),
        )
    }

    fun clear(context: Context) {
        context.prefs().edit().clear().apply()
    }

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Too little time left is not worth resuming: the poll interval alone could
    // outlast it, so the user would just watch it expire.
    private const val MIN_RESUME_SECONDS = 15
    private const val PREFS = "pending_sign_in"
    private const val KEY_GATEWAY_URL = "gateway_url"
    private const val KEY_DEVICE_CODE = "device_code"
    private const val KEY_USER_CODE = "user_code"
    private const val KEY_VERIFY_URI = "verification_uri"
    private const val KEY_VERIFY_URI_COMPLETE = "verification_uri_complete"
    private const val KEY_INTERVAL = "interval"
    private const val KEY_CODE_VERIFIER = "code_verifier"
    private const val KEY_EXPIRES_AT = "expires_at"
}
