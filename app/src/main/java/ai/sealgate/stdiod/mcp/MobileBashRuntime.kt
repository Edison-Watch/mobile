package ai.sealgate.stdiod.mcp

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class BashExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

interface MobileBashRuntime : AutoCloseable {
    fun execute(script: String): BashExecutionResult
}

/** One QuickJS + just-bash instance. Its in-memory filesystem lives until [close]. */
class QuickJsMobileBashRuntime(
    private val sourceProvider: () -> String,
    private val commandRouter: MobileCommandRouter,
) : MobileBashRuntime {
    private val lock = ReentrantLock()
    private var quickJs: QuickJs? = null
    private var completedResult: String? = null

    override fun execute(script: String): BashExecutionResult = lock.withLock {
        try {
            runBlocking {
                withTimeout(EXECUTION_TIMEOUT_MILLIS) {
                    val runtime = runtime()
                    completedResult = null
                    runtime.evaluate<Any?>(
                        "__mobileResult(await globalThis.__mobileBashExec(${JsonPrimitive(script)}))",
                        filename = "mobile-command.mjs",
                        asModule = true,
                    )
                }
            }
            val encoded = completedResult
                ?: return@withLock BashExecutionResult("", "bash: runtime returned no result\n", 1)
            val result = Json.parseToJsonElement(encoded).jsonObject
            BashExecutionResult(
                stdout = result["stdout"]?.jsonPrimitive?.content.orEmpty(),
                stderr = result["stderr"]?.jsonPrimitive?.content.orEmpty(),
                exitCode = result["exitCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            )
        } catch (_: TimeoutCancellationException) {
            // Cancellation interrupts QuickJS evaluation. Discard the engine so
            // a timed-out script cannot leave execution or VM state behind.
            quickJs?.close()
            quickJs = null
            completedResult = null
            BashExecutionResult("", "bash: execution exceeded 60 seconds\n", 124)
        } catch (error: QuickJsException) {
            BashExecutionResult("", "bash: ${error.message?.lineSequence()?.firstOrNull() ?: "runtime error"}\n", 1)
        } catch (error: Exception) {
            BashExecutionResult("", "bash: ${error.message ?: "runtime error"}\n", 1)
        }
    }

    private fun runtime(): QuickJs {
        quickJs?.let { return it }
        val created = QuickJs.create(Dispatchers.Default).apply {
            memoryLimit = QUICKJS_MEMORY_LIMIT_BYTES
            maxStackSize = QUICKJS_STACK_LIMIT_BYTES
            function<String, String>("__mobileCommand", commandRouter::executeJson)
            asyncFunction<Long, Unit>("__mobileSleep") { millis ->
                delay(millis.coerceIn(0, EXECUTION_TIMEOUT_MILLIS))
            }
            function<String, Unit>("__mobileResult") { completedResult = it }
        }
        try {
            runBlocking {
                created.evaluate<Any?>(POLYFILLS, filename = "mobile-polyfills.js")
                created.evaluate<Any?>(sourceProvider(), filename = "mobile-bash-runtime.js")
            }
        } catch (error: Exception) {
            created.close()
            throw error
        }
        quickJs = created
        return created
    }

    override fun close() = lock.withLock {
        quickJs?.close()
        quickJs = null
        completedResult = null
    }

    companion object {
        private const val EXECUTION_TIMEOUT_MILLIS = 60_000L
        private const val QUICKJS_MEMORY_LIMIT_BYTES = 64L * 1024L * 1024L
        private const val QUICKJS_STACK_LIMIT_BYTES = 2L * 1024L * 1024L

        /** QuickJS intentionally starts without browser or Android host globals. */
        private val POLYFILLS = """
            globalThis.process = Object.freeze({
              env: Object.freeze({}),
              versions: Object.freeze({}),
              platform: "android",
              cwd: () => "/"
            });
            globalThis.performance = Object.freeze({ now: () => Date.now() });
            globalThis.crypto = Object.freeze({
              getRandomValues: array => {
                for (let i = 0; i < array.length; i++) array[i] = Math.floor(Math.random() * 256);
                return array;
              }
            });
            globalThis.DOMException = class DOMException extends Error {
              constructor(message, name = "Error") { super(message); this.name = name; }
            };
            globalThis.TextEncoder = class TextEncoder {
              encode(value = "") {
                const encoded = unescape(encodeURIComponent(String(value)));
                return Uint8Array.from(encoded, character => character.charCodeAt(0));
              }
            };
            globalThis.TextDecoder = class TextDecoder {
              decode(value = new Uint8Array()) {
                let binary = "";
                for (let index = 0; index < value.length; index += 8192) {
                  binary += String.fromCharCode(...value.subarray(index, index + 8192));
                }
                return decodeURIComponent(escape(binary));
              }
            };
            let nextTimerId = 1;
            globalThis.setTimeout = () => nextTimerId++;
            globalThis.clearTimeout = () => {};
            globalThis.setInterval = () => nextTimerId++;
            globalThis.clearInterval = () => {};
        """.trimIndent()
    }
}
