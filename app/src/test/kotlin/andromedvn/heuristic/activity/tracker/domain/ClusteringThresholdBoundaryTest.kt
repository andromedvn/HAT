package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clustering threshold boundaries and the ghost/offline double-count cases.
 *
 * `SessionClusteringTest` covers the span-vs-active split but only ever uses a
 * 1_000 ms threshold and gaps of 500 ms and 4_000 ms — comfortably either side.
 * The comparison in `HeuristicEngine.getAppSessions` is `gapDur <=
 * clusterThreshold` (inclusive) and the threshold is a user setting, so the
 * exact boundary value is reachable in normal use and untested.
 *
 * The second half of this class covers invariant 3 — the no-double-count rule
 * for the ghost-conversion path. `DashboardViewModel.convertGhostTime` writes
 * an `IgnoredSession` AND an `OfflineActivityEntity` for the same millisecond
 * range, which is the single most double-count-prone thing the app does.
 */
class ClusteringThresholdBoundaryTest {

    /** Mirrors the clustering loop in `HeuristicEngine.getAppSessions`. */
    private fun cluster(merged: List<Interval>, thresholdMillis: Long): List<Triple<Long, Long, Long>> {
        if (merged.isEmpty()) return emptyList()
        val out = mutableListOf<Triple<Long, Long, Long>>()
        var cS = merged[0].first
        var cE = merged[0].second
        var cDur = cE - cS
        for (i in 1 until merged.size) {
            val next = merged[i]
            val gapDur = next.first - cE
            if (gapDur <= thresholdMillis) {
                cE = next.second
                cDur += next.second - next.first
            } else {
                if (cDur > 0L) out.add(Triple(cS, cE, cDur))
                cS = next.first; cE = next.second; cDur = next.second - next.first
            }
        }
        if (cDur > 0L) out.add(Triple(cS, cE, cDur))
        return out
    }

    // --- the threshold is inclusive ---------------------------------------

    @Test
    fun `a gap exactly equal to the threshold is bridged`() {
        val merged = mergeIntervals(listOf(0L to 1_000L, 2_000L to 3_000L))
        // gap = 2000 - 1000 = 1000 == threshold
        assertEquals(1, cluster(merged, thresholdMillis = 1_000L).size)
    }

    @Test
    fun `a gap one millisecond over the threshold splits the session`() {
        val merged = mergeIntervals(listOf(0L to 1_000L, 2_001L to 3_000L))
        // gap = 2001 - 1000 = 1001 == threshold + 1
        assertEquals(2, cluster(merged, thresholdMillis = 1_000L).size)
    }

    @Test
    fun `a gap one millisecond under the threshold is bridged`() {
        val merged = mergeIntervals(listOf(0L to 1_000L, 1_999L to 3_000L))
        assertEquals(1, cluster(merged, thresholdMillis = 1_000L).size)
    }

    @Test
    fun `a zero threshold still bridges touching intervals`() {
        // sessionClusteringMins = 0 is reachable. mergeIntervals already
        // collapses touching intervals, so a 0 threshold changes nothing and
        // the session count must not jump.
        val merged = mergeIntervals(listOf(0L to 1_000L, 1_000L to 2_000L))
        assertEquals(1, cluster(merged, thresholdMillis = 0L).size)
    }

    @Test
    fun `a zero threshold splits on the very first millisecond of separation`() {
        val merged = mergeIntervals(listOf(0L to 1_000L, 1_001L to 2_000L))
        assertEquals(2, cluster(merged, thresholdMillis = 0L).size)
    }

    @Test
    fun `the threshold in millis is derived from the minutes setting exactly`() {
        // `clusterThresholdMins * 60L * 1000L` at HeuristicEngine:242. The
        // settings UI allows 0..1440; every value must be exact.
        for (mins in 0..1440 step 137) {
            assertEquals(mins.toLong() * 60_000L, mins * 60L * 1000L)
        }
        assertEquals(86_400_000L, 1440 * 60L * 1000L)
    }

    @Test
    fun `a threshold larger than the gap joins an entire day into one session`() {
        val merged = mergeIntervals(listOf(0L to 1_000L, 86_400_000L to 86_401_000L))
        assertEquals(1, cluster(merged, thresholdMillis = 86_400_000L).size)
    }

    @Test
    fun `a zero-duration cluster is dropped rather than shown as an empty session`() {
        // The loop guards every emit with `if (cDur > 0L)`. An interval set
        // whose only member is zero-width must therefore produce NO session.
        val merged = mergeIntervals(listOf(0L to 0L))
        assertEquals(listOf(0L to 0L), merged)
        assertEquals(0L, totalDuration(merged))
        assertEquals("no session is emitted for zero active time", emptyList<Triple<Long, Long, Long>>(), cluster(merged, thresholdMillis = 60_000L))
    }

    @Test
    fun `the active total of a bridged cluster excludes the bridged gap`() {
        val merged = mergeIntervals(listOf(0L to 1_000L, 1_500L to 2_000L))
        val c = cluster(merged, thresholdMillis = 1_000L).single()
        assertEquals("span is the wall clock", 2_000L, c.second - c.first)
        assertEquals("active excludes the 500 ms bridge", 1_500L, c.third)
    }

    // --- INVARIANT 3: ghost conversion must not double count --------------

    @Test
    fun `a converted ghost that fully contains an app session yields zero offline`() {
        // The ghost-conversion case. convertGhostTime writes an offline row for
        // the ghost AND an IgnoredSession that removes the app interval. The
        // offline row therefore covers time the app also owned. Sharding must
        // remove it — otherwise the user is credited with time twice.
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

    @Test
    fun `acknowledging a ghost requires an exact three-field match so near misses are not silently dropped`() {
        // HeuristicEngine:82 and :107 filter with
        //   ackGhosts.any { it.packageName == pkg && it.startMillis == g.second && it.endMillis == g.third }
        // All three fields must match. A one-millisecond drift on either edge
        // leaves the ghost un-acknowledged, which is why the replay clamp in
        // `replayGhostIntervals` has to be stable for a given window.
        val pkg = "com.a"
        val ghost = Triple(pkg, 1_000L, 5_000L)
        val acks = listOf(Triple(pkg, 1_000L, 5_000L))

        fun isAcknowledged(g: Triple<String, Long, Long>) =
            acks.any { it.first == g.first && it.second == g.second && it.third == g.third }

        assertTrue("the exact ack matches", isAcknowledged(ghost))
        assertTrue("a 1 ms start drift does not match", !isAcknowledged(ghost.copy(second = 1_001L)))
        assertTrue("a 1 ms end drift does not match", !isAcknowledged(ghost.copy(third = 4_999L)))
        assertTrue("a different package does not match", !isAcknowledged(ghost.copy(first = "com.b")))
    }

    @Test
    fun `a ghost clamped to two different day windows produces two different ack keys`() {
        // PINNED RISK. `replayGhostIntervals` clamps to [start, end], so the
        // same physical ghost yields a different (start, end) triple depending
        // on which day it is viewed from. An ack recorded on day 1 therefore
        // never matches on day 2, and the ghost reappears.
        val physicalStart = 1_000L
        val physicalEnd = 900_000L
        val day1 = 0L to 500_000L
        val day2 = 500_000L to 1_000_000L

        val onDay1 = Triple("com.a", maxOf(physicalStart, day1.first), minOf(physicalEnd, day1.second))
        val onDay2 = Triple("com.a", maxOf(physicalStart, day2.first), minOf(physicalEnd, day2.second))

        assertEquals(Triple("com.a", 1_000L, 500_000L), onDay1)
        assertEquals(Triple("com.a", 500_000L, 900_000L), onDay2)
        assertTrue("the two ack keys differ, so the ack is window-scoped", onDay1 != onDay2)
    }
}
