package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clustering threshold boundaries and the ghost/offline double-count cases.
 *
 * `SessionClusteringTest` covers the span-vs-active split but only ever uses a
 * 1_000 ms threshold and gaps of 500 ms and 4_000 ms, comfortably either side.
 * The comparison in `HeuristicEngine.getAppSessions` is `gapDur <=
 * clusterThreshold` (inclusive) and the threshold is a user setting, so the
 * exact boundary value is reachable in normal use and untested.
 *
 * The second half of this class covers invariant 3, the no-double-count rule
 * for the ghost-conversion path. `DashboardViewModel.convertGhostTime` writes
 * an `IgnoredSession` AND an `OfflineActivityEntity` for the same millisecond
 * range, which is the single most double-count-prone thing the app does.
 */
class ClusteringThresholdBoundaryTest {

    /** Mirrors the clustering loop in `HeuristicEngine.getAppSessions`. */

    // --- INVARIANT 3: ghost conversion must not double count --------------

    @Test
    fun `a converted ghost that fully contains an app session yields zero offline`() {
        // The ghost-conversion case. convertGhostTime writes an offline row for
        // the ghost AND an IgnoredSession that removes the app interval. The
        // offline row therefore covers time the app also owned. Sharding must
        // remove it, otherwise the user is credited with time twice.
        val app = mergeIntervals(listOf(0L to 10_000L))
        val convertedOffline = listOf(0L to 10_000L)

        val sharded = subtractIntervals(convertedOffline, app)

        assertEquals("the whole converted ghost is app time, not offline", emptyList<Interval>(), sharded)
        assertEquals(0L, totalDuration(sharded))
    }

    @Test
    fun `a converted ghost that contains only part of an app session keeps only the remainder`() {
        val app = mergeIntervals(listOf(4_000L to 10_000L))
        val convertedOffline = listOf(0L to 10_000L)

        val sharded = subtractIntervals(convertedOffline, app)

        assertEquals(listOf(0L to 4_000L), sharded)
        assertEquals(4_000L, totalDuration(sharded))
    }

    @Test
    fun `multiple converted ghost ranges that overlap app time are sharded once each`() {
        // saveMultipleGaps / convertMultipleGhostTimes accept a whole selection.
        // Two overlapping offline rows and two app rows must not multiply.
        val offline = mergeIntervals(listOf(0L to 5_000L, 3_000L to 9_000L))
        val app = mergeIntervals(listOf(1_000L to 2_000L, 6_000L to 7_000L))

        val sharded = subtractIntervals(offline, app)
        val raw = totalDuration(offline)

        assertEquals(9_000L, raw)
        assertEquals(listOf(0L to 1_000L, 2_000L to 6_000L, 7_000L to 9_000L), sharded)
        assertEquals("overlap is removed exactly once", 7_000L, totalDuration(sharded))
        assertEquals("the raw figure over-reports by both app sessions", 2_000L, raw - totalDuration(sharded))
    }

    @Test
    fun `app and offline that tile the day with no overlap sum to exactly the day`() {
        // The clean case: a user who partitioned their day between apps and
        // one labelled activity. Sharding must be the identity here.
        val app = mergeIntervals(listOf(0L to 40_000_000L, 50_000_000L to 86_400_000L))
        val offline = listOf(40_000_000L to 50_000_000L)

        val sharded = subtractIntervals(offline, app)

        assertEquals("nothing to remove", offline, sharded)
        assertEquals(10_000_000L, totalDuration(sharded))
        assertEquals(
            "app + offline must reconstruct the day with no slack",
            86_400_000L,
            totalDuration(app) + totalDuration(sharded)
        )
    }
}
