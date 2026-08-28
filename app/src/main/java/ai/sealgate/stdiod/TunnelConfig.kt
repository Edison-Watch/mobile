package ai.sealgate.stdiod

/**
 * Connection settings for the stdio tunnel.
 *
 * The daemon holds a single outbound WebSocket to the gateway; there is no
 * inbound port to configure. Keep this type small and serializable so it can be
 * persisted (e.g. DataStore) and passed to [TunnelService] via an Intent extra.
 */
data class TunnelConfig(
    /** Gateway WebSocket endpoint, e.g. `wss://gateway.sealgate.ai/tunnel`. */
    val gatewayUrl: String,
    /** Bearer token used to authenticate the tunnel with the gateway. */
    val authToken: String,
) {
    /** True when the config is complete enough to attempt a connection. */
    fun isValid(): Boolean =
        authToken.isNotBlank() &&
            (gatewayUrl.startsWith("wss://") || gatewayUrl.startsWith("ws://"))

    companion object {
        const val EXTRA_GATEWAY_URL = "ai.sealgate.stdiod.extra.GATEWAY_URL"
        const val EXTRA_AUTH_TOKEN = "ai.sealgate.stdiod.extra.AUTH_TOKEN"
    }
}
