package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.SessionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session-clustering extraction.
 *
 * `getAppSessions` and `getOfflineSessions` each carried their own copy of the
 * same twenty-line loop. They were compared line by line before the change:
 * identical, including the unused `gapStart`/`gapEnd` locals, the `<=`
 * comparison, the `cDur > 0L` emit guard and the `currentRaw` bookkeeping. The
 * only difference between the two functions was the SOURCE of the interval set
 * — `getAllIntervalsCached(...)[packageName]` versus
 * `getShardedOfflineIntervalsForTitle(...)` — plus an emptiness guard that
 * differed only in what it tested (the raw list before merging, in both cases
 * equivalent to testing the merged list, since `mergeIntervals(emptyList())` is
 * `emptyList()`).
 *
 * So this is a pure de-duplication and the test that matters is parity: the
 * shipped [clusterSessions] must agree with the loop the two existing test
 * classes mirror, on every shape those classes use and on the awkward ones they
 * do not. A behaviour change here would silently alter every session list in
 * the app, so parity is asserted against a literal copy of the old code rather
 * than against a second call into the new one.
 */
class SessionClusteringParityTest {

    /** A verbatim copy of the loop as it appeared twice in HeuristicEngine. */
    private fun legacyCluster(merged: List<Interval>, thresholdMillis: Long): List<SessionItem> {
        if (merged.isEmpty()) return emptyList()
        val clustered = mutableListOf<SessionItem>()
        var cS = merged[0].first
        var cE = merged[0].second
        var cDur = cE - cS
        val currentRaw = mutableListOf(merged[0])

        for (i in 1 until merged.size) {
            val next = merged[i]
            val gapStart = cE
            val gapEnd = next.first
            val gapDur = gapEnd - gapStart

            if (gapDur <= thresholdMillis) {
                cE = next.second; cDur += (next.second - next.first)
                currentRaw.add(next)
            } else {
                if (cDur > 0L) clustered.add(SessionItem(cS, cE, cDur, currentRaw.toList()))
                cS = next.first; cE = next.second; cDur = next.second - next.first
                currentRaw.clear(); currentRaw.add(next)
            }
        }
        if (cDur > 0L) clustered.add(SessionItem(cS, cE, cDur, currentRaw.toList()))
        return clustered
    }

    private fun assertParity(merged: List<Interval>, threshold: Long) {
        assertEquals(legacyCluster(merged, threshold), clusterSessions(merged, threshold))
    }

    // --- parity across the shapes the existing suite uses ------------------

    @Test
    fun `parity on an empty set`() {
        assertParity(emptyList(), 60_000L)
        assertEquals(emptyList<SessionItem>(), clusterSessions(emptyList(), 60_000L))
    }

    @Test
    fun `parity on a single interval`() {
        assertParity(mergeIntervals(listOf(0L to 1_000L)), 60_000L)
    }

    @Test
    fun `parity on a bridged cluster with an internal gap`() {
        assertParity(mergeIntervals(listOf(0L to 1_000L, 1_500L to 2_000L)), 1_000L)
    }

    @Test
    fun `parity on a split cluster`() {
        assertParity(mergeIntervals(listOf(0L to 1_000L, 5_000L to 6_000L)), 1_000L)
    }

    @Test
    fun `parity on touching intervals`() {
        assertParity(mergeIntervals(listOf(0L to 1_000L, 1_000L to 2_000L)), 0L)
    }

    @Test
    fun `parity at the threshold boundary`() {
        assertParity(mergeIntervals(listOf(0L to 1_000L, 2_000L to 3_000L)), 1_000L)   // gap == threshold
        assertParity(mergeIntervals(listOf(0L to 1_000L, 2_001L to 3_000L)), 1_000L)   // threshold + 1
        assertParity(mergeIntervals(listOf(0L to 1_000L, 1_999L to 3_000L)), 1_000L)   // threshold - 1
    }

    @Test
    fun `parity on a zero-width interval set`() {
        assertParity(listOf(500L to 500L), 60_000L)
        assertEquals("no session is emitted for zero active time", emptyList<SessionItem>(), clusterSessions(listOf(500L to 500L), 60_000L))
    }

    @Test
    fun `parity at realistic scale`() {
        // A day of alternating app use: ~600 sessions' worth of intervals.
        val day = mergeIntervals((0 until 600).map { (it * 120_000L) to (it * 120_000L + 45_000L) })
        for (threshold in listOf(0L, 1_000L, 60_000L, 300_000L, 3_600_000L, 86_400_000L)) {
            assertParity(day, threshold)
        }
    }

    @Test
    fun `parity on a mixed set of bridges and splits`() {
        val merged = mergeIntervals(
            listOf(
                0L to 1_000L,
                1_200L to 2_000L,        // bridged
                2_500L to 3_000L,        // bridged
                90_000L to 91_000L,      // split
                91_300L to 92_000L,      // bridged
                500_000L to 501_000L     // split
            )
        )
        for (threshold in listOf(0L, 200L, 500L, 1_000L, 10_000L)) {
            assertParity(merged, threshold)
        }
    }

    // --- the properties the callers rely on -------------------------------

    @Test
    fun `sub intervals are carried and never shared between sessions`() {
        val merged = mergeIntervals(
            listOf(0L to 1_000L, 1_500L to 2_000L, 50_000L to 51_000L)
        )
        val sessions = clusterSessions(merged, 1_000L)

        assertEquals(2, sessions.size)
        assertEquals(merged, sessions.flatMap { it.subIntervals })
        assertEquals("every raw interval appears exactly once", merged.size, sessions.flatMap { it.subIntervals }.size)
    }

    @Test
    fun `active duration excludes the bridged gaps and never exceeds the span`() {
        val merged = mergeIntervals(listOf(0L to 1_000L, 1_500L to 2_000L))
        val session = clusterSessions(merged, 1_000L).single()

        assertEquals("span is the wall clock", 2_000L, session.spanDuration)
        assertEquals("active excludes the 500 ms bridge", 1_500L, session.activeDuration)
        assertEquals(1_500L, session.displayedDuration)
        assertTrue(session.activeDuration <= session.spanDuration)
    }

    @Test
    fun `a session with no internal gap has span equal to active`() {
        val merged = mergeIntervals(listOf(0L to 1_000L, 1_000L to 2_000L))
        val session = clusterSessions(merged, 0L).single()
        assertEquals(session.spanDuration, session.activeDuration)
    }

    @Test
    fun `active durations sum to the total of the input`() {
        // The figure the UI aggregates (ClusteringThresholdBoundaryTest's
        // invariant 3 context): summing sessions must not invent or lose time.
        val merged = mergeIntervals((0 until 200).map { (it * 200_000L) to (it * 200_000L + 90_000L) })
        for (threshold in listOf(0L, 60_000L, 250_000L)) {
            assertEquals(
                "threshold $threshold",
                totalDuration(merged),
                clusterSessions(merged, threshold).sumOf { it.activeDuration }
            )
        }
    }

    @Test
    fun `sessions come back in ascending-start order and the caller reverses them`() {
        val merged = mergeIntervals(listOf(0L to 1_000L, 10_000L to 11_000L, 20_000L to 21_000L))
        val sessions = clusterSessions(merged, 0L)
        assertEquals(listOf(0L, 10_000L, 20_000L), sessions.map { it.startTime })
        assertEquals(
            "both callers sort descending by start",
            listOf(20_000L, 10_000L, 0L),
            sessions.sortedByDescending { it.startTime }.map { it.startTime }
        )
    }

    @Test
    fun `the threshold is derived from the minutes setting exactly`() {
        for (mins in 0..1440) {
            assertEquals(mins.toLong() * 60_000L, minutesToMillisExact(mins))
        }
        assertEquals(86_400_000L, minutesToMillisExact(1440))
        assertEquals(0L, minutesToMillisExact(0))
    }

    @Test
    fun `a very large clustering setting still cannot overflow the threshold`() {
        // The reason the conversion is `Int.toLong() * 60_000L` and not
        // `Int * Int`: the settings UI allows 0..1440, but a restored vault
        // could carry anything.
        val threshold = minutesToMillisExact(Int.MAX_VALUE)
        assertEquals(128_849_018_820_000L, threshold)
        assertTrue(threshold > 0L)
        // And such a threshold joins a whole day into one session.
        val merged = mergeIntervals(listOf(0L to 1_000L, 86_400_000L to 86_401_000L))
        assertEquals(1, clusterSessions(merged, threshold).size)
    }
}
