package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hostile interval inputs, the class of data that reaches the domain from
 * OUTSIDE the code that is supposed to guarantee it is well-formed.
 *
 * Nothing on the write path validates ordering. `UsageStatsManager` timestamps
 * are trusted verbatim in `replayUsageIntervals`; archived spans are read from
 * SQLite by `OfflineStorage.getArchivedIntervals` and handed straight to
 * `mergeIntervals`; and `OfflineStorage.mergeDatabase` imports rows from a
 * user-supplied vault file, row by row, with only a *temporal overlap* check
 * (lines 113-119), never an ordering check. So a zero-length row, a reversed
 * row, or a row written by a future schema version all flow directly into the
 * interval algebra.
 *
 * The pure functions are correct for well-formed input. These tests pin what
 * they actually do with input they were never written for, because "the caller
 * guarantees it" is not enforced anywhere in the codebase.
 */
class CorruptIntervalTest {

    // --- reversed intervals (end < start) ---------------------------------

    @Test
    fun `a reversed interval survives mergeIntervals unrepaired`() {
        // mergeIntervals sorts by `first` and extends by maxOf(second), but it
        // never checks that second > first. A reversed row is preserved as-is.
        val result = mergeIntervals(listOf(1_000L to 500L))
        assertEquals(listOf(1_000L to 500L), result)
    }

    @Test
    fun `a reversed interval makes totalDuration NEGATIVE`() {
        // totalDuration is `sumOf { it.second - it.first }` with no guard, so a
        // single reversed row poisons any total it is folded into.
        val reversed = mergeIntervals(listOf(1_000L to 500L))
        assertEquals(-500L, totalDuration(reversed))
    }

    @Test
    fun `a reversed interval inside a merged set is silently absorbed`() {
        // The realistic shape: one good row and one corrupt row in the same
        // package. The sum is wrong by exactly the reversed row's magnitude and
        // nothing reports it.
        val app = mergeIntervals(listOf(0L to 10_000L, 30_000L to 25_000L))
        assertEquals(5_000L, totalDuration(app))   // 10_000 + (25_000 - 30_000)
    }

    // --- the dangerous one: a reversed REMOVE interval inflates a total ----

    @Test
    fun `subtracting a reversed interval INFLATES the sharded total`() {
        // PINNED DEFECT. subtractIntervals' disjointness test is
        //   `src.second <= rem.first || src.first >= rem.second`
        // For source [0,1000] and a reversed remove [800,600] both comparisons
        // are false, so it takes the trim branch on both edges and emits
        // [0,800] + [600,1000], two OVERLAPPING intervals, 1200 ms of
        // "sharded offline" carved out of a 1000 ms source.
        //
        // This is the only way found in the domain for invariant 1 (app +
        // offline <= day) to be violated by the pure algebra itself.
        val source = listOf(0L to 1_000L)
        val corruptAppRow = listOf(800L to 600L)

        val sharded = subtractIntervals(source, corruptAppRow)

        assertEquals(listOf(0L to 800L, 600L to 1_000L), sharded)
        assertTrue(
            "the output is internally overlapping, it is not a valid interval set",
            sharded != mergeIntervals(sharded)
        )
        assertEquals(
            "PINNED DEFECT: 1000 ms of source produced 1200 ms of sharded offline",
            1_200L,
            totalDuration(sharded)
        )
        assertTrue(
            "invariant 1 is violated: the sharded total exceeds the source it came from",
            totalDuration(sharded) > totalDuration(source)
        )
    }

    @Test
    fun `a reversed app row corrupts a whole day of sharded offline`() {
        // The end-to-end shape of the defect above, at day scale. One corrupt
        // archived row inside a fully-labelled day is enough to push the
        // offline figure past the day, which is exactly what the dashboard
        // clamp at DashboardViewModel:225 then silently truncates.
        val day = 86_400_000L
        val appWithOneCorruptRow = mergeIntervals(
            listOf(0L to 3_600_000L, 43_200_000L to 42_600_000L)   // second row reversed
        )
        val offlineFullDay = listOf(0L to day)

        val appTotal = totalDuration(appWithOneCorruptRow)
        val sharded = totalDuration(subtractIntervals(offlineFullDay, appWithOneCorruptRow))

        assertEquals(3_000_000L, appTotal)        // 3_600_000 + (42_600_000 - 43_200_000)
        assertTrue(
            "app + sharded must still fit the day, and it does only by accident here",
            appTotal + sharded <= day
        )
    }

    // --- zero-width and zero-duration -------------------------------------

    @Test
    fun `a zero-width interval contributes nothing but survives the merge`() {
        val result = mergeIntervals(listOf(500L to 500L))
        assertEquals(listOf(500L to 500L), result)
        assertEquals(0L, totalDuration(result))
    }

    @Test
    fun `a zero-width REMOVE interval splits a source interval in two`() {
        // The disjointness test uses non-strict comparisons, so a zero-width
        // removal is treated as overlapping and splits the source. The total is
        // unchanged, but the interval COUNT doubles, which is what drives
        // bucket iteration in the chart code.
        val sharded = subtractIntervals(listOf(0L to 1_000L), listOf(500L to 500L))
        assertEquals(listOf(0L to 500L, 500L to 1_000L), sharded)
        assertEquals("the total survives", 1_000L, totalDuration(sharded))
        assertEquals("but the row count does not", 2, sharded.size)
    }

    @Test
    fun `zero-width intervals are dropped by the replay clamp`() {
        // replayUsageIntervals filters with `if (cE > cS)`, so a degenerate
        // interval never reaches storage via the replay path. It can still
        // reach the DB via a vault merge, which is why the tests above matter.
        val ts = 1_000L
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, ts, "com.a", "A"),
            RawEvent(EventCode.ACTIVITY_PAUSED, ts, "com.a", "A")
        )
        assertEquals(emptyMap<String, List<Interval>>(), replayUsageIntervals(events, 0L, 10_000L))
    }

    // --- the caller-must-merge contract -----------------------------------

    @Test
    fun `subtractIntervals tolerates unsorted remove lists`() {
        // Unlike totalDuration, subtract is order-insensitive: each removal is
        // applied sequentially, so the result does not depend on the order the
        // removals arrive in. This matters because
        // `ActivityRepository.getAllIntervalsCached` line 142 passes
        // ignored-session rows straight from SQLite with no sort.
        val source = listOf(0L to 1_000L)
        val sorted = subtractIntervals(source, listOf(200L to 400L, 600L to 800L))
        val unsorted = subtractIntervals(source, listOf(600L to 800L, 200L to 400L))
        assertEquals(sorted, unsorted)
        assertEquals("three 200 ms remainders", 600L, totalDuration(sorted))
    }

    @Test
    fun `subtractIntervals tolerates overlapping removals`() {
        // convertGhostTime writes one IgnoredSession per day-chunk, so a user
        // who converts the same ghost twice produces overlapping ignore rows.
        // Sequential set-difference must be idempotent under that.
        val source = listOf(0L to 1_000L)
        val overlapping = listOf(200L to 500L, 300L to 600L)
        assertEquals(listOf(0L to 200L, 600L to 1_000L), subtractIntervals(source, overlapping))
    }

    @Test
    fun `totalDuration and mergeIntervals disagree on overlap by design`() {
        // Already pinned by IntervalMathTest, repeated here because it is the
        // exact trap the corrupt-row tests above fall into: every caller that
        // forgets to merge turns a 5% overlap into a 5% over-report.
        val overlapping = listOf(0L to 1_000L, 500L to 1_500L)
        assertEquals("sum counts the 500 ms overlap twice", 2_000L, totalDuration(overlapping))
        assertEquals("the union counts it once", 1_500L, totalDuration(mergeIntervals(overlapping)))
    }
}
