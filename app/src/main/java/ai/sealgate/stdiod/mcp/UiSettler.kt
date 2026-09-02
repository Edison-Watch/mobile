package ai.sealgate.stdiod.mcp

/** Lightweight UI state sampled around an accessibility action. */
internal data class UiStateMarker(
    val eventSequence: Long,
    val lastEventUptimeMillis: Long,
    val packageName: String?,
    val windowId: Int?,
) {
    val hasActiveWindow: Boolean
        get() = packageName != null && windowId != null
}

/** Describes whether Android exposed and settled the result of an action before capture. */
internal data class UiSettleResult(
    val settled: Boolean,
    val postActionEventObserved: Boolean,
    val activeWindowChanged: Boolean,
)

/**
 * Waits for evidence that an action reached the accessibility layer, then for
 * the resulting UI to become quiet. Requiring the first transition is
 * important: checking only for quiet can return immediately before Android has
 * delivered the action's first event.
 */
internal class UiSettler(
    private val uptimeMillis: () -> Long,
    private val sleepMillis: (Long) -> Unit,
    private val quietMillis: Long,
    private val pollMillis: Long,
    private val timeoutMillis: Long,
) {
    fun awaitPostAction(
        baseline: UiStateMarker,
        sample: () -> UiStateMarker,
    ): UiSettleResult {
        val startedAt = uptimeMillis()
        val deadline = startedAt + timeoutMillis
        var eventObserved = false
        var windowChanged = false
        var previous = baseline
        var stableSince = startedAt

        while (true) {
            val now = uptimeMillis()
            val current = sample()
            eventObserved = eventObserved || current.eventSequence > baseline.eventSequence
            windowChanged = windowChanged || (
                current.hasActiveWindow &&
                    (!baseline.hasActiveWindow ||
                        current.packageName != baseline.packageName ||
                        current.windowId != baseline.windowId)
                )

            val sameActiveWindow = current.hasActiveWindow &&
                current.packageName == previous.packageName &&
                current.windowId == previous.windowId
            if (!sameActiveWindow) stableSince = now

            val transitionObserved = eventObserved || windowChanged
            val eventStreamIsQuiet = now - current.lastEventUptimeMillis >= quietMillis
            val activeWindowIsStable = current.hasActiveWindow && now - stableSince >= quietMillis
            if (transitionObserved && eventStreamIsQuiet && activeWindowIsStable) {
                return UiSettleResult(
                    settled = true,
                    postActionEventObserved = eventObserved,
                    activeWindowChanged = windowChanged,
                )
            }
            if (now >= deadline) {
                return UiSettleResult(
                    settled = false,
                    postActionEventObserved = eventObserved,
                    activeWindowChanged = windowChanged,
                )
            }

            previous = current
            sleepMillis(minOf(pollMillis, deadline - now))
        }
    }
}

/** Serializes operations that Android rate-limits even when the caller does not. */
internal class MinimumIntervalGate(
    private val uptimeMillis: () -> Long,
    private val sleepMillis: (Long) -> Unit,
    private val minimumIntervalMillis: Long,
) {
    private var lastStartedAt: Long? = null

    @Synchronized
    fun awaitAndMark() {
        val previous = lastStartedAt
        if (previous != null) {
            val remaining = minimumIntervalMillis - (uptimeMillis() - previous)
            if (remaining > 0) sleepMillis(remaining)
        }
        lastStartedAt = uptimeMillis()
    }
}

/** Keeps a few snapshot-qualified locator sets so one follow-up observe does not invalidate every prior node ID. */
internal class RecentSnapshotStore<T>(private val capacity: Int) {
    private val values = LinkedHashMap<String, T>()

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun put(id: String, value: T) {
        values.remove(id)
        values[id] = value
        while (values.size > capacity) values.remove(values.keys.first())
    }

    @Synchronized
    fun get(id: String): T? = values[id]

    @Synchronized
    fun latestId(): String? = values.keys.lastOrNull()
}
