package ai.sealgate.stdiod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigTest {

    @Test
    fun `valid when wss url and token present`() {
        val config = TunnelConfig(
            gatewayUrl = "wss://gateway.sealgate.ai/tunnel",
            authToken = "token",
        )
        assertTrue(config.isValid())
    }

    @Test
    fun `invalid when token blank`() {
        val config = TunnelConfig(
            gatewayUrl = "wss://gateway.sealgate.ai/tunnel",
            authToken = "   ",
        )
        assertFalse(config.isValid())
    }

    @Test
    fun `invalid when url is not a websocket scheme`() {
        val config = TunnelConfig(
            gatewayUrl = "https://gateway.sealgate.ai/tunnel",
            authToken = "token",
        )
        assertFalse(config.isValid())
    }

    @Test
    fun `device id defaults to null and does not affect validity`() {
        val apiKey = TunnelConfig(
            gatewayUrl = "wss://gateway.sealgate.ai/tunnel",
            authToken = "ew_key",
        )
        assertNull(apiKey.deviceId)
        assertTrue(apiKey.isValid())

        val oauth = apiKey.copy(authToken = "ewc_token", deviceId = "ewd_device")
        assertEquals("ewd_device", oauth.deviceId)
        assertTrue(oauth.isValid())
    }
}
