package ai.sealgate.stdiod.tunnel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Client for SealGate's OAuth 2.0 Device Authorization Grant (RFC 8628, with
 * PKCE), the same flow the desktop daemon uses. The phone requests a short
 * user code, the human approves it in the dashboard, and the phone polls until
 * it receives a scoped `ewc_` tunnel credential bound to a backend-minted
 * device id. See `src/api/v1/routes/device_auth.py` in edison-watch and
 * `dev-docs/architecture/mobile-hardware-gateway-design.md`.
 *
 * This class deliberately avoids Android framework types so its logic is
 * exercised by plain JVM unit tests (there is no Android SDK in CI's unit
 * test task).
 */
class DeviceAuthClient(
    private val apiBaseUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = defaultJson,
) {
    /** The `ewc_` credential and identity the app persists after a successful pairing. */
    data class PairingResult(
        val accessToken: String,
        val deviceId: String,
        val clientInstallationId: String,
    )

    /** A user-facing failure with a message safe to show verbatim. */
    class DeviceAuthException(message: String) : Exception(message)

    /**
     * Ask the backend for a device code. Returns the pending grant plus the
     * PKCE verifier the caller must keep to redeem it in [pollForToken].
     */
    suspend fun requestDeviceCode(
        deviceLabel: String,
        clientVersion: String,
        clientInstallationId: String?,
    ): PendingGrant {
        val pkce = Pkce.generate()
        val body = buildJsonObject {
            put("client_id", CLIENT_ID)
            putJsonArray("scope") { add(SCOPE_TUNNEL_CONNECT) }
            put("code_challenge", pkce.challenge)
            put("code_challenge_method", "S256")
            put("device_label", deviceLabel)
            put("platform", PLATFORM_ANDROID)
            put("client_version", clientVersion)
            if (clientInstallationId != null) put("client_installation_id", clientInstallationId)
        }.toString()
        val response = post("$apiBaseUrl$PATH_CODE", body)
        if (!response.isSuccessful) {
            throw DeviceAuthException(describeCodeError(response.code, response.body))
        }
        val code = try {
            json.decodeFromString<DeviceCodeResponse>(response.body)
        } catch (e: Exception) {
            throw DeviceAuthException("The gateway returned an unexpected sign-in response.")
        }
        return PendingGrant(
            deviceCode = code.deviceCode,
            userCode = code.userCode,
            verificationUri = code.verificationUri,
            verificationUriComplete = code.verificationUriComplete,
            expiresInSeconds = code.expiresIn,
            intervalSeconds = code.interval,
            codeVerifier = pkce.verifier,
        )
    }

    /**
     * Poll the token endpoint until the grant is approved, denied, or expires.
     * Honours the server's `interval` and `slow_down` back-pressure.
     */
    suspend fun pollForToken(grant: PendingGrant): PairingResult {
        var intervalSeconds = grant.intervalSeconds.coerceAtLeast(1)
        val deadlineMillis = System.currentTimeMillis() + grant.expiresInSeconds.toLong() * 1000L
        val body = buildJsonObject {
            put("client_id", CLIENT_ID)
            put("device_code", grant.deviceCode)
            put("code_verifier", grant.codeVerifier)
        }.toString()
        while (true) {
            delay(intervalSeconds.toLong() * 1000L)
            if (System.currentTimeMillis() >= deadlineMillis) {
                throw DeviceAuthException(EXPIRED_MESSAGE)
            }
            val response = post("$apiBaseUrl$PATH_TOKEN", body)
            if (response.isSuccessful) {
                val token = try {
                    json.decodeFromString<DeviceTokenResponse>(response.body)
                } catch (e: Exception) {
                    throw DeviceAuthException("The gateway returned an unexpected token response.")
                }
                return PairingResult(
                    accessToken = token.accessToken,
                    deviceId = token.deviceId,
                    clientInstallationId = token.clientInstallationId,
                )
            }
            when (val action = pollActionFor(response.code, response.body, intervalSeconds)) {
                is PollAction.Retry -> intervalSeconds = action.intervalSeconds
                is PollAction.Fail -> throw DeviceAuthException(action.message)
            }
        }
    }

    private suspend fun post(url: String, jsonBody: String): HttpResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            httpClient.newCall(request).execute().use { raw ->
                HttpResult(raw.code, raw.body?.string().orEmpty())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DeviceAuthException(
                "Could not reach the SealGate gateway. Check the URL and your connection.",
            )
        }
    }

    /** Decode a token-endpoint error and decide what to do next. Pure, so it is unit-tested. */
    internal fun pollActionFor(statusCode: Int, body: String, intervalSeconds: Int): PollAction =
        when (val error = parseErrorCode(body)) {
            "authorization_pending" -> PollAction.Retry(intervalSeconds)
            // The server widens its own interval on slow_down; mirror that locally.
            "slow_down" -> PollAction.Retry(intervalSeconds + SLOW_DOWN_BACKOFF_SECONDS)
            "access_denied" -> PollAction.Fail("Sign-in was denied in the dashboard.")
            "expired_token" -> PollAction.Fail(EXPIRED_MESSAGE)
            "invalid_scope" -> PollAction.Fail("This app requested a scope the gateway does not allow.")
            "invalid_client" -> PollAction.Fail("The gateway does not recognise this app as a client.")
            else -> PollAction.Fail(
                "Sign-in failed (${error ?: "HTTP $statusCode"}). Please try again.",
            )
        }

    private fun describeCodeError(statusCode: Int, body: String): String =
        when (parseErrorCode(body)) {
            "invalid_client" ->
                "This gateway does not support mobile sign-in yet. " +
                    "Update the SealGate backend or use an API key."
            "invalid_scope" -> "This app requested a scope the gateway does not allow."
            else -> "Could not start sign-in (HTTP $statusCode). Check the gateway URL."
        }

    private fun parseErrorCode(body: String): String? =
        try {
            json.decodeFromString<OAuthError>(body).error
        } catch (e: Exception) {
            null
        }

    sealed interface PollAction {
        data class Retry(val intervalSeconds: Int) : PollAction
        data class Fail(val message: String) : PollAction
    }

    private data class HttpResult(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    companion object {
        const val CLIENT_ID = "mobile"
        const val SCOPE_TUNNEL_CONNECT = "tunnel:connect"
        const val PLATFORM_ANDROID = "android"
        const val PATH_CODE = "/api/v1/auth/device/code"
        const val PATH_TOKEN = "/api/v1/auth/device/token"
        private const val SLOW_DOWN_BACKOFF_SECONDS = 5
        private const val EXPIRED_MESSAGE = "The sign-in code expired. Please try again."
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val defaultJson = Json { ignoreUnknownKeys = true }
    }
}

/** A device grant awaiting human approval; carries the PKCE verifier to redeem it. */
data class PendingGrant(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
    val codeVerifier: String,
)

/** PKCE (RFC 7636) verifier/challenge pair generation. */
object Pkce {
    data class Pair(val verifier: String, val challenge: String)

    fun generate(random: SecureRandom = SecureRandom()): Pair {
        // 32 random bytes -> 43-char base64url verifier, within the 43..128 the
        // backend accepts (device_auth.py DeviceTokenRequest.code_verifier).
        val verifierBytes = ByteArray(32)
        random.nextBytes(verifierBytes)
        val verifier = base64Url(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pair(verifier, base64Url(digest))
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Turns a tunnel WebSocket URL into the HTTP origin its REST API is served from. */
object GatewayUrls {
    /**
     * `wss://host[:port]/api/v1/stdio-tunnel/ws` -> `https://host[:port]`.
     * Returns null when [wsUrl] is not a ws/wss URL with a host.
     */
    fun apiBaseFromWs(wsUrl: String): String? {
        val trimmed = wsUrl.trim()
        val scheme = when {
            trimmed.startsWith("wss://", ignoreCase = true) -> "https"
            trimmed.startsWith("ws://", ignoreCase = true) -> "http"
            else -> return null
        }
        val afterScheme = trimmed.substringAfter("://", "")
        // Authority ends at the first '/', '?' or '#'.
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isBlank() || authority.startsWith(":")) return null
        return "$scheme://$authority"
    }
}

@Serializable
private data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int,
)

// Only the fields the app consumes; the token endpoint also returns token_type,
// scope, user_id, org_id and api_key (always null for mobile), ignored here.
@Serializable
private data class DeviceTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("client_installation_id") val clientInstallationId: String,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
private data class OAuthError(val error: String? = null)
