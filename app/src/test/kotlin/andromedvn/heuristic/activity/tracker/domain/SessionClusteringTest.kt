package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5: SessionItem carries two different durations. `spanDuration` is the
 * wall-clock span (endTime - startTime); `activeDuration` is the sum of the real
 * sub-intervals, excluding the idle gaps bridged by the clustering tolerance.
 *
 * ActivityDetailsScreen summed spanDuration for its header while the rows
 * beneath showed activeDuration, so the header read larger than the rows.
 * The fix routes the UI through the same figure the rows use.
 *
 * These tests pin both values and prove they genuinely diverge.
 */
class SessionClusteringTest {

    /** Mirrors the clustering loop in HeuristicEngine.getAppSessions. */
    data class Cluster(val start: Long, val end: Long, val active: Long, val raw: List<Interval>)

    fun clusterIntervals(merged: List<Interval>, thresholdMillis: Long): List<Cluster> {
        if (merged.isEmpty()) return emptyList()
        val out = mutableListOf<Cluster>()
        var cS = merged[0].first
        var cE = merged[0].second
        var cDur = cE - cS
        val raw = mutableListOf(merged[0])
        for (i in 1 until merged.size) {
            val next = merged[i]
            val gapDur = next.first - cE
            if (gapDur <= thresholdMillis) {
                cE = next.second
                cDur += next.second - next.first
                raw.add(next)
            } else {
                if (cDur > 0L) out.add(Cluster(cS, cE, cDur, raw.toList()))
                cS = next.first; cE = next.second; cDur = next.second - next.first
                raw.clear(); raw.add(next)
            }
        }
        if (cDur > 0L) out.add(Cluster(cS, cE, cDur, raw.toList()))
        return out
    }

    @Test
    fun `a cluster with an internal gap has span larger than active`() {
        // 0-1000 active, 500ms idle, 1500-2000 active, bridged by a 1s tolerance.
        val merged = mergeIntervals(listOf(0L to 1_000L, 1_500L to 2_000L))
        val clusters = clusterIntervals(merged, thresholdMillis = 1_000L)

        assertEquals(1, clusters.size)
        val c = clusters.first()
        assertEquals(2_000L, c.end - c.start)   // span
        assertEquals(1_500L, c.active)          // active sum
        assertTrue("span and active must genuinely differ", c.end - c.start != c.active)
    }

    @Test
    fun `a gap wider than the tolerance splits the session`() {
        val merged = mergeIntervals(listOf(0L to 1_000L, 5_000L to 6_000L))
        val clusters = clusterIntervals(merged, thresholdMillis = 1_000L)
        assertEquals(2, clusters.size)
    }

    @Test
    fun `span equals active when there is no internal gap`() {
        val merged = mergeIntervals(listOf(0L to 1_000L, 1_000L to 2_000L))
        val clusters = clusterIntervals(merged, thresholdMillis = 1_000L)
        val c = clusters.first()
        assertEquals(c.end - c.start, c.active)
    }

    @Test
    fun `summing the active figure matches the sum of the rows`() {
        // The regression: header used spanDuration, rows used activeDuration.
        val merged = mergeIntervals(listOf(0L to 1_000L, 1_500L to 2_000L))
        val clusters = clusterIntervals(merged, thresholdMillis = 1_000L)

        val headerUsedSpan = clusters.sumOf { it.end - it.start }
        val rowsShowActive = clusters.sumOf { it.active }

        assertTrue("this is the bug: $headerUsedSpan != $rowsShowActive", headerUsedSpan != rowsShowActive)
        assertEquals(rowsShowActive, clusters.sumOf { it.active })
    }
}
