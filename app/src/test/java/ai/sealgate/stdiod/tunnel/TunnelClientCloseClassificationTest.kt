package ai.sealgate.stdiod.tunnel

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reconnect loop must stop only on the gateway's terminal refusals, and keep
 * retrying everything else. These assert the classification of the close codes
 * and reasons `edison-watch`'s stdio_tunnel.py emits.
 */
class TunnelClientCloseClassificationTest {

    private fun classifyClose(code: Int, reason: String) =
        TunnelClient.terminalReasonForClose(code, reason)

    @Test
    fun revokedInstallationCloseIsCredentialRejected() {
        assertEquals(
            TunnelStopReason.CREDENTIAL_REJECTED,
            classifyClose(1008, "client installation revoked"),
        )
    }

    @Test
    fun changedIdentityCloseIsCredentialRejected() {
        assertEquals(
            TunnelStopReason.CREDENTIAL_REJECTED,
            classifyClose(1008, "client identity changed"),
        )
    }

    @Test
    fun deviceIdMismatchCloseIsCredentialRejected() {
        assertEquals(
            TunnelStopReason.CREDENTIAL_REJECTED,
            classifyClose(1008, "device id does not match client credential"),
        )
        assertEquals(
            TunnelStopReason.CREDENTIAL_REJECTED,
            classifyClose(1008, "device_id mismatch between header and client_hello"),
        )
    }

    @Test
    fun protocolMismatchCloseIsProtocolUnsupported() {
        assertEquals(
            TunnelStopReason.PROTOCOL_UNSUPPORTED,
            classifyClose(1008, "protocol_version mismatch (client=1, server supports 2-3)"),
        )
    }

    @Test
    fun orgDisabledCloseIsOrgNotEnabled() {
        assertEquals(
            TunnelStopReason.ORG_NOT_ENABLED,
            classifyClose(1008, "stdio_tunnel not enabled for this org"),
        )
    }

    @Test
    fun connectionReplacedCloseIsTransient() {
        // Another connection took over; reconnecting is legitimate, not terminal.
        assertNull(classifyClose(1008, "connection replaced"))
    }

    @Test
    fun normalAndTransientCloseCodesAreNotTerminal() {
        assertNull(classifyClose(1000, "client stopping"))
        assertNull(classifyClose(1006, "abnormal closure"))
        assertNull(classifyClose(1011, "server error"))
        assertNull(classifyClose(1012, "service restart"))
        // A 1008 with an unrecognised reason stays transient rather than bricking
        // the tunnel on a reason string we did not anticipate.
        assertNull(classifyClose(1008, "policy violation"))
    }

    @Test
    fun rejectedUpgradeStatusesAreCredentialRejected() {
        assertEquals(
            TunnelStopReason.CREDENTIAL_REJECTED,
            TunnelClient.terminalReasonForFailure(responseWithCode(403)),
        )
        assertEquals(
            TunnelStopReason.CREDENTIAL_REJECTED,
            TunnelClient.terminalReasonForFailure(responseWithCode(401)),
        )
    }

    @Test
    fun networkFailureWithoutResponseIsTransient() {
        assertNull(TunnelClient.terminalReasonForFailure(null))
        // A 5xx upgrade failure is the server hiccupping, not a refused credential.
        assertNull(TunnelClient.terminalReasonForFailure(responseWithCode(503)))
    }

    private fun responseWithCode(code: Int): Response =
        Response.Builder()
            .request(Request.Builder().url("https://gateway.example/api/v1/stdio-tunnel/ws").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .build()
}
