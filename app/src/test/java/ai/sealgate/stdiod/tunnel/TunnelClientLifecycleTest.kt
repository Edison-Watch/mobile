package ai.sealgate.stdiod.tunnel

import ai.sealgate.stdiod.mcp.LocalMcpModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelClientLifecycleTest {
    @Test
    fun stopReturnsImmediatelyAndStopAndAwaitObservesModuleTeardown() {
        val closed = CountDownLatch(1)
        val module = object : LocalMcpModule, AutoCloseable {
            override val name = "blocking"
            override fun handle(message: JsonObject): JsonObject? = null

            override fun close() {
                Thread.sleep(200)
                closed.countDown()
            }
        }
        val client = TunnelClient(
            gatewayUrl = "ws://127.0.0.1:1",
            authToken = "test",
            identity = DeviceIdentity("test", "test", "test", "test"),
            modules = listOf(module),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        val startedAt = System.nanoTime()
        client.stop()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("stop blocked for ${elapsedMillis}ms", elapsedMillis < 100)
        runBlocking { client.stopAndAwait() }
        assertTrue(closed.await(1, TimeUnit.SECONDS))
    }
}
