package ai.sealgate.stdiod

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** A bounded, in-memory history of the Bash requests agents run on this phone. */
data class ExecutionLogEntry(
    val id: Long,
    val script: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
) {
    val headline: String
        get() {
            val firstCommand = script.lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
                .orEmpty()
                .replace(Regex("\\s+"), " ")
            return when {
                firstCommand.isEmpty() -> "Empty command"
                firstCommand.length <= HEADLINE_CHARACTERS -> firstCommand
                else -> firstCommand.take(HEADLINE_CHARACTERS - 1).trimEnd() + "…"
            }
        }

    val isRunning: Boolean get() = finishedAtMillis == null
    val durationMillis: Long? get() = finishedAtMillis?.minus(startedAtMillis)?.coerceAtLeast(0)

    companion object {
        private const val HEADLINE_CHARACTERS = 72
    }
}

object ExecutionLogStore {
    private const val MAX_ENTRIES = 50
    private const val MAX_OUTPUT_CHARACTERS = 16 * 1024
    private const val OUTPUT_TRUNCATED_NOTICE = "\n… output clipped in log"

    private val lock = Any()
    private val nextId = AtomicLong()
    private val _entries = MutableStateFlow<List<ExecutionLogEntry>>(emptyList())
    val entries: StateFlow<List<ExecutionLogEntry>> = _entries.asStateFlow()

    fun begin(script: String, startedAtMillis: Long = System.currentTimeMillis()): Long {
        val id = nextId.incrementAndGet()
        synchronized(lock) {
            _entries.value = listOf(
                ExecutionLogEntry(
                    id = id,
                    script = script,
                    startedAtMillis = startedAtMillis,
                ),
            ) + _entries.value.take(MAX_ENTRIES - 1)
        }
        return id
    }

    fun finish(
        id: Long,
        stdout: String,
        stderr: String,
        exitCode: Int,
        finishedAtMillis: Long = System.currentTimeMillis(),
    ) = update(id) { entry ->
        entry.copy(
            finishedAtMillis = finishedAtMillis,
            stdout = stdout.clipped(),
            stderr = stderr.clipped(),
            exitCode = exitCode,
        )
    }

    fun fail(
        id: Long,
        error: Throwable,
        finishedAtMillis: Long = System.currentTimeMillis(),
    ) = update(id) { entry ->
        entry.copy(
            finishedAtMillis = finishedAtMillis,
            stderr = (error.message ?: error.javaClass.simpleName).clipped(),
            exitCode = 1,
        )
    }

    private fun update(id: Long, transform: (ExecutionLogEntry) -> ExecutionLogEntry) {
        synchronized(lock) {
            _entries.value = _entries.value.map { entry ->
                if (entry.id == id) transform(entry) else entry
            }
        }
    }

    private fun String.clipped(): String =
        if (length <= MAX_OUTPUT_CHARACTERS) this
        else take(MAX_OUTPUT_CHARACTERS) + OUTPUT_TRUNCATED_NOTICE

    internal fun clear() = synchronized(lock) {
        _entries.value = emptyList()
    }
}
