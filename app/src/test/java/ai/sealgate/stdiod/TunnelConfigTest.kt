package ai.sealgate.stdiod

import org.junit.Assert.assertFalse
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
}
