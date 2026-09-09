package ai.sealgate.stdiod.tunnel

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayUrlsTest {

    @Test
    fun `derives https origin from wss tunnel url`() {
        assertEquals(
            "https://demo-dashboard.sealgate.ai",
            GatewayUrls.apiBaseFromWs("wss://demo-dashboard.sealgate.ai/api/v1/stdio-tunnel/ws"),
        )
    }

    @Test
    fun `derives http origin from ws url and preserves port`() {
        assertEquals(
            "http://10.0.2.2:8000",
            GatewayUrls.apiBaseFromWs("ws://10.0.2.2:8000/api/v1/stdio-tunnel/ws"),
        )
    }

    @Test
    fun `trims surrounding whitespace and ignores query and fragment`() {
        assertEquals(
            "https://host.example",
            GatewayUrls.apiBaseFromWs("  wss://host.example/ws?x=1#frag  "),
        )
    }

    @Test
    fun `rejects non websocket schemes and empty authority`() {
        assertNull(GatewayUrls.apiBaseFromWs("https://host/ws"))
        assertNull(GatewayUrls.apiBaseFromWs("host/ws"))
        assertNull(GatewayUrls.apiBaseFromWs("wss:///ws"))
        assertNull(GatewayUrls.apiBaseFromWs(""))
    }
}

class PkceTest {

    @Test
    fun `verifier is 43 char base64url and challenge matches sha256`() {
        val pair = Pkce.generate()
        assertEquals(43, pair.verifier.length)
        assertTrue(pair.verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })

        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pair.verifier.toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(expected, pair.challenge)
        // 43 chars is exactly a 32-byte base64url payload (no padding).
        assertEquals(43, pair.challenge.length)
    }

    @Test
    fun `successive verifiers differ`() {
        assertTrue(Pkce.generate().verifier != Pkce.generate().verifier)
    }
}

class DeviceAuthPollActionTest {

    private val client = DeviceAuthClient(apiBaseUrl = "https://example.test")

    @Test
    fun `authorization_pending retries at the same interval`() {
        val action = client.pollActionFor(400, """{"error":"authorization_pending"}""", 7)
        assertEquals(DeviceAuthClient.PollAction.Retry(7), action)
    }

    @Test
    fun `slow_down backs off the interval`() {
        val action = client.pollActionFor(400, """{"error":"slow_down"}""", 7)
        assertEquals(DeviceAuthClient.PollAction.Retry(12), action)
    }

    @Test
    fun `access_denied and expired_token are terminal`() {
        assertTrue(
            client.pollActionFor(400, """{"error":"access_denied"}""", 5)
                is DeviceAuthClient.PollAction.Fail,
        )
        assertTrue(
            client.pollActionFor(400, """{"error":"expired_token"}""", 5)
                is DeviceAuthClient.PollAction.Fail,
        )
    }

    @Test
    fun `unrecognised error body still fails cleanly`() {
        val action = client.pollActionFor(500, "not json", 5)
        assertTrue(action is DeviceAuthClient.PollAction.Fail)
    }
}
