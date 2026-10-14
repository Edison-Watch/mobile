package ai.sealgate.stdiod

import ai.sealgate.stdiod.mcp.BashExecutionResult
import ai.sealgate.stdiod.mcp.BashModule
import ai.sealgate.stdiod.mcp.MobileBashRuntime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionLogStoreTest {
    @Before
    fun setUp() = ExecutionLogStore.clear()

    @After
    fun tearDown() = ExecutionLogStore.clear()

    @Test
    fun recordsRunningThenCompletedExecution() {
        val id = ExecutionLogStore.begin("battery status\n", startedAtMillis = 100)

        val running = ExecutionLogStore.entries.value.single()
        assertEquals("battery status", running.headline)
        assertTrue(running.isRunning)

        ExecutionLogStore.finish(id, "84\n", "", 0, finishedAtMillis = 145)

        val completed = ExecutionLogStore.entries.value.single()
        assertFalse(completed.isRunning)
        assertEquals(45L, completed.durationMillis)
        assertEquals(0, completed.exitCode)
        assertEquals("84\n", completed.stdout)
    }

    @Test
    fun keepsOnlyTheMostRecentFiftyEntries() {
        repeat(55) { index -> ExecutionLogStore.begin("command $index") }

        val entries = ExecutionLogStore.entries.value
        assertEquals(50, entries.size)
        assertEquals("command 54", entries.first().headline)
        assertEquals("command 5", entries.last().headline)
    }

    @Test
    fun clipsLongHeadlinesAndOutput() {
        val id = ExecutionLogStore.begin("x".repeat(100))
        ExecutionLogStore.finish(id, "o".repeat(20_000), "", 0)

        val entry = ExecutionLogStore.entries.value.single()
        assertTrue(entry.headline.endsWith("…"))
        assertTrue(entry.stdout.endsWith("… output clipped in log"))
        assertTrue(entry.stdout.length < 20_000)
    }

    @Test
    fun bashToolCallsAreAddedToTheLog() {
        val module = BashModule(
            runtimeFactory = {
                object : MobileBashRuntime {
                    override fun execute(script: String) = BashExecutionResult("Pixel 6\n", "", 0)
                    override fun close() = Unit
                }
            },
        )
        val request = Json.parseToJsonElement(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"run","arguments":{"script":"device info"}}}""",
        ).jsonObject

        module.handle(request)

        val entry = ExecutionLogStore.entries.value.single()
        assertEquals("device info", entry.headline)
        assertEquals("Pixel 6\n", entry.stdout)
        assertEquals(0, entry.exitCode)
    }
}
