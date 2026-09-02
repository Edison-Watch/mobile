package ai.sealgate.stdiod.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSettlerTest {
    @Test
    fun waitsForFirstPostActionEventAndThenForQuietUi() {
        var now = 1_000L
        val settler = UiSettler(
            uptimeMillis = { now },
            sleepMillis = { now += it },
            quietMillis = 300L,
            pollMillis = 50L,
            timeoutMillis = 2_000L,
        )
        val baseline = marker(sequence = 7, eventAt = 500, packageName = "before", windowId = 1)

        val result = settler.awaitPostAction(baseline) {
            when {
                now < 1_100L -> baseline
                now < 1_200L -> marker(8, 1_100L, "after", 2)
                else -> marker(9, 1_200L, "after", 2)
            }
        }

        assertTrue(result.settled)
        assertTrue(result.postActionEventObserved)
        assertTrue(result.activeWindowChanged)
        assertTrue("capture started before the event stream was quiet", now >= 1_500L)
    }

    @Test
    fun doesNotTreatPreActionQuietAsPostActionSettlement() {
        var now = 1_000L
        val baseline = marker(sequence = 7, eventAt = 100, packageName = "same", windowId = 1)
        val settler = UiSettler(
            uptimeMillis = { now },
            sleepMillis = { now += it },
            quietMillis = 300L,
            pollMillis = 50L,
            timeoutMillis = 500L,
        )

        val result = settler.awaitPostAction(baseline) { baseline }

        assertFalse(result.settled)
        assertFalse(result.postActionEventObserved)
        assertFalse(result.activeWindowChanged)
        assertTrue(now >= 1_500L)
    }

    @Test
    fun sameWindowContentEventCanSettleWithoutWindowIdentityChange() {
        var now = 1_000L
        val baseline = marker(sequence = 3, eventAt = 500, packageName = "settings", windowId = 4)
        val settler = UiSettler(
            uptimeMillis = { now },
            sleepMillis = { now += it },
            quietMillis = 300L,
            pollMillis = 50L,
            timeoutMillis = 1_000L,
        )

        val result = settler.awaitPostAction(baseline) {
            if (now < 1_100L) baseline else marker(4, 1_100L, "settings", 4)
        }

        assertTrue(result.settled)
        assertTrue(result.postActionEventObserved)
        assertFalse(result.activeWindowChanged)
    }

    @Test
    fun minimumIntervalGateDelaysAnImmediateSecondScreenshot() {
        var now = 5_000L
        val gate = MinimumIntervalGate(
            uptimeMillis = { now },
            sleepMillis = { now += it },
            minimumIntervalMillis = 500L,
        )

        gate.awaitAndMark()
        now += 100L
        gate.awaitAndMark()

        assertTrue(now >= 5_500L)
    }

    @Test
    fun recentSnapshotStoreKeepsRecentIdsAndEvictsOnlyTheOldest() {
        val store = RecentSnapshotStore<String>(capacity = 2)
        store.put("obs_1", "one")
        store.put("obs_2", "two")

        assertTrue(store.get("obs_1") == "one")
        store.put("obs_3", "three")

        assertTrue(store.get("obs_1") == null)
        assertTrue(store.get("obs_2") == "two")
        assertTrue(store.latestId() == "obs_3")
    }

    private fun marker(
        sequence: Long,
        eventAt: Long,
        packageName: String,
        windowId: Int,
    ) = UiStateMarker(sequence, eventAt, packageName, windowId)
}
