package ai.sealgate.stdiod.tunnel

import ai.sealgate.stdiod.mcp.LocalMcpModule
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.concurrent.thread
import kotlin.random.Random

/** Who this device says it is in `client_hello`. */
data class DeviceIdentity(
    val deviceId: String,
    val hostname: String,
    val label: String,
    val clientVersion: String,
)

sealed interface TunnelState {
    data object Disconnected : TunnelState
    data object Connecting : TunnelState

    /** `server_hello` received; the tunnel is live. */
    data object Connected : TunnelState

    /**
     * Terminal: the gateway refused this credential for good, so the reconnect
     * loop has stopped. Reconnecting with the same credential would only loop,
     * so the UI turns this into a call to action (see [reason]).
     */
    data class Unauthorized(val reason: TunnelStopReason) : TunnelState
}

/** Why the gateway refused the tunnel for good, and thus what the user must do. */
enum class TunnelStopReason {
    /** Credential invalid, revoked, or bound to a different device: sign in again. */
    CREDENTIAL_REJECTED,

    /** The client's protocol version is outside the gateway's window: update the app. */
    PROTOCOL_UNSUPPORTED,

    /** stdio tunnel is not enabled for this org: contact an admin. */
    ORG_NOT_ENABLED,
}

/**
 * The device side of the stdiod tunnel: one outbound WebSocket to the
 * backend, a `client_hello`/`server_hello` handshake, then steady-state
 * frame routing. Reconnects forever with jittered exponential backoff until
 * [stop] is called; the owning [ai.sealgate.stdiod.TunnelService] scopes its
 * lifetime.
 *
 * Unlike the desktop daemon there is no process supervision: `mcp_frame`s
 * route to in-process [LocalMcpModule]s bound from the desired state (by
 * prefix, or by the `mobile-builtin` command), and entries that match no
 * built-in module are refused with a spawn error (a phone cannot run `npx`).
 */
class TunnelClient(
    private val gatewayUrl: String,
    private val authToken: String,
    private val identity: DeviceIdentity,
    modules: List<LocalMcpModule>,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val modulesByName: Map<String, LocalMcpModule> = modules.associateBy { it.name }

    /** server_id (backend's key for `mcp_frame`s) → built-in module. */
    private val modulesByServerId = ConcurrentHashMap<String, LocalMcpModule>()

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    val state: StateFlow<TunnelState> = _state

    private var loopJob: Job? = null
    private var webSocket: WebSocket? = null
    private val stopped = AtomicBoolean(false)
    private val modulesClosed = AtomicBoolean(false)
    private val modulesCloseFinished = CompletableDeferred<Unit>()
    private val moduleLock = Any()
    private val activeDispatcher = AtomicReference<McpRequestDispatcher?>()

    fun start() {
        if (stopped.get()) return
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { connectLoop() }
    }

    fun stop() {
        stopped.set(true)
        loopJob?.cancel()
        loopJob = null
        webSocket?.close(NORMAL_CLOSURE, "client stopping")
        webSocket = null
        activeDispatcher.getAndSet(null)?.close()
        if (modulesClosed.compareAndSet(false, true)) {
            // A QuickJS evaluation may hold its runtime lock until the 60-second
            // execution limit. Never make the service/main thread wait for it.
            thread(start = true, isDaemon = true, name = "mobile-mcp-close") {
                try {
                    synchronized(moduleLock) {
                        modulesByName.values.filterIsInstance<AutoCloseable>().forEach { module ->
                            runCatching(module::close).onFailure {
                                Log.w(TAG, "failed to close module ${module.javaClass.simpleName}", it)
                            }
                        }
                    }
                } finally {
                    modulesCloseFinished.complete(Unit)
                }
            }
        }
        _state.value = TunnelState.Disconnected
    }

    /** Stop and wait off the main thread until in-flight module work has drained. */
    suspend fun stopAndAwait() {
        stop()
        modulesCloseFinished.await()
    }

    private suspend fun connectLoop() {
        var backoffMillis = INITIAL_BACKOFF_MILLIS
        while (true) {
            _state.value = TunnelState.Connecting
            val outcome = runOneConnection()
            if (outcome.terminalReason != null) {
                // The gateway rejected the credential for good. Stop reconnecting
                // (retrying the same credential would just loop every backoff) and
                // publish a terminal state the UI turns into a call to action.
                Log.w(TAG, "tunnel stopped, credential no longer usable: ${outcome.terminalReason}")
                _state.value = TunnelState.Unauthorized(outcome.terminalReason)
                return
            }
            _state.value = TunnelState.Disconnected
            // A handshake that completed earns a fresh backoff; a connection
            // refused/dropped before server_hello keeps climbing toward the cap.
            backoffMillis = if (outcome.sawServerHello) {
                INITIAL_BACKOFF_MILLIS
            } else {
                (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
            }
            val jittered = backoffMillis / 2 + Random.nextLong(backoffMillis / 2 + 1)
            Log.i(TAG, "tunnel disconnected; reconnecting in ${jittered}ms")
            delay(jittered)
        }
    }

    /** The result of one WebSocket session: whether it handshook, and any terminal refusal. */
    private data class ConnectionOutcome(
        val sawServerHello: Boolean,
        val terminalReason: TunnelStopReason?,
    )

    /** Runs one WebSocket session to completion. */
    private suspend fun runOneConnection(): ConnectionOutcome = suspendCancellableCoroutine { cont ->
        val sessionActive = AtomicBoolean(true)
        val dispatcher = McpRequestDispatcher()
        activeDispatcher.getAndSet(dispatcher)?.close()
        val request = Request.Builder()
            .url(gatewayUrl)
            .header("Authorization", "Bearer $authToken")
            .header("X-SealGate-Device-Id", identity.deviceId)
            .build()

        val listener = object : WebSocketListener() {
            // OkHttp delivers reader callbacks sequentially, so these need no lock.
            var sawServerHello = false
            var terminalReason: TunnelStopReason? = null

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopped.get()) {
                    webSocket.close(NORMAL_CLOSURE, "client already stopped")
                    return
                }
                this@TunnelClient.webSocket = webSocket
                send(
                    webSocket,
                    ClientHello(
                        protocolVersion = PROTOCOL_VERSION,
                        deviceId = identity.deviceId,
                        hostname = identity.hostname,
                        label = identity.label,
                        os = "android",
                        clientVersion = identity.clientVersion,
                        currentlyRunning = modulesByName.keys.sorted(),
                    ),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped.get()) return
                val frame = try {
                    parseTunnelFrame(text)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "dropping unparseable tunnel frame", e)
                    return
                }
                when (frame) {
                    is ServerHello -> {
                        sawServerHello = true
                        _state.value = TunnelState.Connected
                        bindServers(webSocket, frame.servers)
                    }
                    is DesiredStateUpdate -> {
                        bindServers(webSocket, frame.added + frame.updated)
                        frame.removed.forEach(modulesByServerId::remove)
                    }
                    is McpFrame -> {
                        // Local modules may perform gestures, screenshots, or a
                        // full Bash script. Never run them on OkHttp's reader
                        // callback: doing so prevents WebSocket control frames
                        // and unrelated tunnel messages from being processed.
                        // Resolve the binding at receipt time. A later desired-state
                        // update must not retroactively change an earlier request.
                        val module = modulesByServerId[frame.serverId] ?: modulesByName[frame.serverId]
                        if (!dispatcher.submit {
                                routeMcpFrame(webSocket, frame, module, sessionActive)
                            }
                        ) {
                            // Backpressure is explicit: retaining an unbounded number
                            // of long-running requests would eventually exhaust memory.
                            webSocket.close(TRY_AGAIN_LATER, "MCP request queue full")
                            finish()
                        }
                    }
                    is Ping -> send(webSocket, Pong)
                    is Pong -> Unit
                    // Built-in modules have no spawn-time env/spec to store.
                    is ServerEnvUpdate, is ServerSpecUpdate -> Unit
                    is TunnelError ->
                        Log.w(TAG, "tunnel_error from backend: ${frame.code}: ${frame.message}")
                    is ClientHello, is ServerSpawnResult ->
                        Log.w(TAG, "unexpected client->server frame from backend: $frame")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "tunnel socket failure (http=${response?.code})", t)
                // A rejected upgrade (the gateway closes before `accept`, e.g. an
                // invalid or revoked credential) reaches us as an HTTP status, not
                // a WS close frame; treat 401/403 as terminal.
                if (terminalReason == null) terminalReason = terminalReasonForFailure(response)
                finish()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // onClosing carries the peer's close code/reason; capture it here
                // before echoing our own close (onClosed reports the same peer code).
                terminalReason = terminalReasonForClose(code, reason)
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (terminalReason == null) terminalReason = terminalReasonForClose(code, reason)
                finish()
            }

            fun finish() {
                if (!sessionActive.compareAndSet(true, false)) return
                dispatcher.close()
                activeDispatcher.compareAndSet(dispatcher, null)
                this@TunnelClient.webSocket = null
                modulesByServerId.clear()
                if (cont.isActive) cont.resume(ConnectionOutcome(sawServerHello, terminalReason))
            }
        }

        val socket = httpClient.newWebSocket(request, listener)
        cont.invokeOnCancellation {
            sessionActive.set(false)
            dispatcher.close()
            activeDispatcher.compareAndSet(dispatcher, null)
            socket.cancel()
        }
    }

    /**
     * Bind desired servers to built-in modules and ack each spawn the way
     * the desktop daemon's supervisor does — except "spawning" here is a
     * lookup (see [resolveBuiltinModule]: by prefix, else by the
     * `mobile-builtin` command). Unmatched servers are refused loudly so
     * the dashboard's create-server flow gets a real error instead of a
     * timeout.
     */
    private fun bindServers(webSocket: WebSocket, servers: List<DesiredServer>) {
        if (stopped.get()) return
        for (server in servers) {
            if (!server.enabled) {
                modulesByServerId.remove(server.serverId)
                continue
            }
            val module = resolveBuiltinModule(server, modulesByName)
            if (module != null) {
                modulesByServerId[server.serverId] = module
                send(webSocket, ServerSpawnResult(serverId = server.serverId, ok = true))
            } else {
                modulesByServerId.remove(server.serverId)
                send(
                    webSocket,
                    ServerSpawnResult(
                        serverId = server.serverId,
                        ok = false,
                        error = describeUnboundServer(server, modulesByName.keys),
                    ),
                )
            }
        }
    }

    private fun routeMcpFrame(
        webSocket: WebSocket,
        frame: McpFrame,
        module: LocalMcpModule?,
        sessionActive: AtomicBoolean,
    ) {
        if (!sessionActive.get() || stopped.get()) return
        if (module == null) {
            send(
                webSocket,
                TunnelError(
                    serverId = frame.serverId,
                    code = "server_offline",
                    message = "no running module for server `${frame.serverId}`",
                ),
            )
            return
        }
        val response = synchronized(moduleLock) {
            if (!sessionActive.get() || stopped.get()) return
            module.handle(frame.frame)
        } ?: return
        if (!sessionActive.get() || stopped.get()) return
        send(webSocket, McpFrame(serverId = frame.serverId, frame = response))
    }

    private fun send(webSocket: WebSocket, frame: TunnelFrame) {
        webSocket.send(encodeTunnelFrame(frame))
    }

    companion object {
        private const val TAG = "TunnelClient"
        private const val NORMAL_CLOSURE = 1000
        private const val POLICY_VIOLATION = 1008
        private const val TRY_AGAIN_LATER = 1013
        private const val INITIAL_BACKOFF_MILLIS = 1_000L
        private const val MAX_BACKOFF_MILLIS = 60_000L

        /**
         * Classify a WS close frame. Only the gateway's own 1008 policy closes are
         * terminal, and the reason string (a documented, stable contract - see
         * `_authenticate_ws` and the protocol handshake in edison-watch's
         * stdio_tunnel.py) says which. An unrecognised close (network 1006, server
         * restart 1012, "connection replaced", ...) is transient: keep reconnecting.
         */
        internal fun terminalReasonForClose(code: Int, reason: String): TunnelStopReason? {
            if (code != POLICY_VIOLATION) return null
            val r = reason.lowercase()
            return when {
                "protocol_version" in r -> TunnelStopReason.PROTOCOL_UNSUPPORTED
                "not enabled" in r -> TunnelStopReason.ORG_NOT_ENABLED
                "revoked" in r || "identity" in r || "credential" in r ||
                    "device id" in r || "device_id" in r ->
                    TunnelStopReason.CREDENTIAL_REJECTED
                else -> null
            }
        }

        /**
         * Classify a failed WS upgrade. The gateway rejects a bad/revoked
         * credential before `accept`, which OkHttp surfaces as an HTTP status
         * rather than a close frame; 401/403 mean the credential was refused.
         */
        internal fun terminalReasonForFailure(response: Response?): TunnelStopReason? =
            when (response?.code) {
                401, 403 -> TunnelStopReason.CREDENTIAL_REJECTED
                else -> null
            }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            // One WS, no request/response cycle: no read timeout, but do
            // fail dead links: OkHttp pings keep NAT mappings warm and
            // detect half-open sockets.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}

internal const val MCP_REQUEST_QUEUE_CAPACITY = 16

/** A bounded, session-scoped serial dispatcher for potentially slow module calls. */
internal class McpRequestDispatcher {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MCP_REQUEST_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "mobile-mcp-requests").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun submit(task: () -> Unit): Boolean = try {
        executor.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    fun close() {
        executor.shutdownNow()
    }
}
