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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val modulesByServerId = HashMap<String, LocalMcpModule>()

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    val state: StateFlow<TunnelState> = _state

    private var loopJob: Job? = null
    private var webSocket: WebSocket? = null
    private val stopped = AtomicBoolean(false)
    private val modulesClosed = AtomicBoolean(false)
    private val modulesCloseFinished = CompletableDeferred<Unit>()
    private val moduleLock = Any()

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
            val sessionSawHello = runOneConnection()
            _state.value = TunnelState.Disconnected
            // A handshake that completed earns a fresh backoff; a connection
            // refused/dropped before server_hello keeps climbing toward the cap.
            backoffMillis = if (sessionSawHello) {
                INITIAL_BACKOFF_MILLIS
            } else {
                (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
            }
            val jittered = backoffMillis / 2 + Random.nextLong(backoffMillis / 2 + 1)
            Log.i(TAG, "tunnel disconnected; reconnecting in ${jittered}ms")
            delay(jittered)
        }
    }

    /** Runs one WebSocket session to completion. Returns true if `server_hello` arrived. */
    private suspend fun runOneConnection(): Boolean = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(gatewayUrl)
            .header("Authorization", "Bearer $authToken")
            .header("X-SealGate-Device-Id", identity.deviceId)
            .build()

        val listener = object : WebSocketListener() {
            // OkHttp delivers reader callbacks sequentially, so this needs no lock.
            var sawServerHello = false

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
                    is McpFrame -> routeMcpFrame(webSocket, frame)
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
                finish()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish()
            }

            fun finish() {
                this@TunnelClient.webSocket = null
                modulesByServerId.clear()
                if (cont.isActive) cont.resume(sawServerHello)
            }
        }

        val socket = httpClient.newWebSocket(request, listener)
        cont.invokeOnCancellation { socket.cancel() }
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

    private fun routeMcpFrame(webSocket: WebSocket, frame: McpFrame) {
        // Accept a module addressed by bare name too, so a backend that keys
        // built-ins by name (and tests) can skip the desired-state handshake.
        val module = modulesByServerId[frame.serverId] ?: modulesByName[frame.serverId]
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
            if (stopped.get()) return
            module.handle(frame.frame)
        } ?: return
        if (stopped.get()) return
        send(webSocket, McpFrame(serverId = frame.serverId, frame = response))
    }

    private fun send(webSocket: WebSocket, frame: TunnelFrame) {
        webSocket.send(encodeTunnelFrame(frame))
    }

    companion object {
        private const val TAG = "TunnelClient"
        private const val NORMAL_CLOSURE = 1000
        private const val INITIAL_BACKOFF_MILLIS = 1_000L
        private const val MAX_BACKOFF_MILLIS = 60_000L

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            // One WS, no request/response cycle: no read timeout, but do
            // fail dead links: OkHttp pings keep NAT mappings warm and
            // detect half-open sockets.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
