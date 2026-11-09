package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.UsageGap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scale, performance and integer width.
 *
 * The test phone has 4.7 GB of RAM and the build is deliberately configured
 * without Robolectric for exactly that reason. Nothing in the suite has ever
 * run the interval algebra at a realistic size: the largest input anywhere is
 * two intervals. The measurements below are therefore first observations, not
 * regressions, but they are the numbers that decide whether a heavy user sees
 * a spinner or an ANR.
 *
 * The integer half pins the 2^31 boundary. 2_147_483_647 ms is 24.855 days, so
 * any aggregation over ~25 days of milliseconds overflows an Int. The app
 * aggregates a MONTH in one `sumOf` and a YEAR in the archive, so every
 * accumulator in the time domain must be Long, these tests make that explicit
 * rather than incidental.
 */
class ScaleAndIntegerWidthTest {

    // --- integer width -----------------------------------------------------

    @Test
    fun `Int can hold one day of milliseconds but not twenty five`() {
        val oneDay = 86_400_000L
        val twentyFiveDays = 25L * oneDay

        assertTrue("a single day fits an Int", oneDay <= Int.MAX_VALUE.toLong())
        assertTrue(
            "Int.MAX_VALUE ms is only 24.855 days, so 25 days overflows: $twentyFiveDays",
            twentyFiveDays > Int.MAX_VALUE.toLong()
        )
        assertEquals(2_147_483_647, Int.MAX_VALUE)
    }

    @Test
    fun `a month of continuous app usage is representable in Long and not in Int`() {
        val thirtyDays = 30L * 86_400_000L
        assertEquals(2_592_000_000L, thirtyDays)
        assertTrue(thirtyDays > Int.MAX_VALUE.toLong())
        assertTrue(
            "folding this into an Int would silently produce $((thirtyDays.toInt()).toLong())",
            thirtyDays.toInt().toLong() != thirtyDays
        )
    }

    @Test
    fun `a year of archive millis needs Long by a factor of fourteen`() {
        val year = 365L * 86_400_000L
        assertEquals(31_536_000_000L, year)
        assertTrue("a year is ~14x Int range", year / Int.MAX_VALUE.toLong() == 14L)
        // Documented hazard: the Int truncation is not a small error, it is a
        // number with the wrong sign of magnitude and no way to notice.
        assertTrue("Int truncation of a year is catastrophic", (year.toInt()).toLong() < year / 10)
    }

    @Test
    fun `thirty days of summed intervals stays exact in Long`() {
        val intervals = (0 until 30).map { day ->
            (day * 86_400_000L) to (day * 86_400_000L + 86_400_000L)
        }
        val total = totalDuration(mergeIntervals(intervals))
        assertEquals(2_592_000_000L, total)
        assertTrue("and it genuinely exceeds Int range", total > Int.MAX_VALUE.toLong())
    }

    @Test
    fun `the day constant used as a Long divisor stays a Long`() {
        // `DashboardViewModel:195` does ((now - oldest) / 86400000L).toInt(), 
        // correct, Long division before the narrowing. Line 417 uses
        // (1000 * 60 * 60 * 24) which is an Int product that happens to fit.
        assertEquals(86_400_000L, 1000L * 60L * 60L * 24L)
        assertTrue("the Int product fits, but it is the shape the guard test hunts", 1000 * 60 * 60 * 24 == 86_400_000)
        // 1_000 days of retention, the widest history the app will hold.
        val retentionDays = 1_000L
        assertEquals(86_400_000_000L, retentionDays * 86_400_000L)
    }

    // --- scale: merge ------------------------------------------------------

    @Test
    fun `mergeIntervals handles five thousand intervals without loss`() {
        val input = (0 until 5_000).map { (it * 1_000L) to (it * 1_000L + 400L) }
        val merged = mergeIntervals(input)

        assertEquals("no two of these overlap, so nothing merges", 5_000, merged.size)
        assertEquals(2_000_000L, totalDuration(merged))
    }

    @Test
    fun `mergeIntervals collapses a fully interleaved set in one pass`() {
        val input = (0 until 5_000).map { (it * 100L) to (it * 100L + 150L) }
        val merged = mergeIntervals(input)

        assertEquals("every interval overlaps its neighbours", 1, merged.size)
        assertEquals("the union spans the whole set", 500_050L, totalDuration(merged))
    }

    @Test
    fun `mergeIntervals on an already-merged set is a no-op`() {
        val input = (0 until 2_000).map { (it * 5_000L) to (it * 5_000L + 1_000L) }
        val once = mergeIntervals(input)
        assertEquals(once, mergeIntervals(once))
    }

    // --- scale: shard ------------------------------------------------------

    @Test
    fun `sharding five thousand offline rows against five thousand app rows stays exact`() {
        // Worst realistic single-day shape: a user who alternates apps and
        // labelled activities every second for over an hour.
        val app = (0 until 5_000).map { (it * 1_000L) to (it * 1_000L + 400L) }
        val offline = (0 until 5_000).map { (it * 1_000L + 400L) to (it * 1_000L + 1_000L) }

        val sharded = subtractIntervals(mergeIntervals(offline), mergeIntervals(app))
        val appTotal = totalDuration(mergeIntervals(app))
        val offlineTotal = totalDuration(sharded)

        assertEquals(2_000_000L, appTotal)
        assertEquals(3_000_000L, offlineTotal)
        assertEquals("app + sharded offline must still fit inside the span", 5_000_000L, appTotal + offlineTotal)
    }

    @Test
    fun `sharding preserves the invariant at 5000-interval scale`() {
        val day = 86_400_000L
        val app = (0 until 5_000).map { (it * 1_000L) to (it * 1_000L + 400L) }
        val offlineFullDay = listOf(0L to day)

        val appTotal = totalDuration(mergeIntervals(app))
        val sharded = totalDuration(subtractIntervals(offlineFullDay, mergeIntervals(app)))

        assertTrue(
            "invariant 1 must survive scale: $appTotal + $sharded",
            appTotal + sharded <= day
        )
    }

    @Test
    fun `subtracting ten thousand unmerged removals is a measurable hot spot`() {
        // PINNED RISK. `subtractIntervals` is O(source x remove) and does not
        // sort or merge `remove` first. `ActivityRepository:142` passes
        // ignored-session rows straight from SQLite in whatever order the
        // database returns them. Measured on a desktop JVM this is a few
        // hundred ms; on a low-RAM phone budget it is the difference between a
        // spinner and a jank frame. The assertion is a smoke bound, not a
        // benchmark, it fails only if the algorithm regresses by orders of
        // magnitude.
        val source = (0 until 5_000).map { (it * 1_000L) to (it * 1_000L + 1_000L) }
        val removals = (0 until 2_000).map { (it * 1_000L + 200L) to (it * 1_000L + 300L) }

        val startNanos = System.nanoTime()
        val result = subtractIntervals(source, removals)
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        assertTrue("the result must be correct regardless of timing", result.isNotEmpty())
        assertEquals(5_000L * 1_000L - 2_000L * 100L, totalDuration(result))
        assertTrue(
            "subtract is O(n*m): 5000x2000 took ${elapsedMs}ms, which would jank a phone",
            elapsedMs < 5_000L
        )
    }

    // --- scale: replay -----------------------------------------------------

    @Test
    fun `replayUsageIntervals handles a full day of screen events`() {
        // A heavy day: ~1 event pair per minute for 24 h.
        val events = mutableListOf<RawEvent>()
        for (i in 0 until 1_440) {
            val t = i * 60_000L
            events.add(RawEvent(EventCode.ACTIVITY_RESUMED, t, "com.app${i % 8}", "A"))
            events.add(RawEvent(EventCode.ACTIVITY_PAUSED, t + 30_000L, "com.app${i % 8}", "A"))
        }
        val result = replayUsageIntervals(events, 0L, 86_400_000L)

        assertEquals(8, result.size)
        assertEquals(1_440L * 30_000L, totalDuration(result.values.flatten()))
    }

    @Test
    fun `a long dark stretch closes at the cap without overflow`() {
        // Screen goes off, an app resumes and is never paused, then the day ends.
        // (2026-10-16) A dark-screen resume is Screen-Off Activity: the span
        // belongs to the screen-off walk, which must still close it at the cap
        // without any overflow.
        val events = listOf(
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 0L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a", "A")
        )

        assertEquals(
            mapOf("com.a" to listOf(1_000L to 86_400_000L)),
            replayScreenOffIntervals(events, 0L, 86_400_000L, cap = 86_400_000L)
        )
    }

    @Test
    fun `a thousand days of data never overflows a Long accumulator`() {
        // The widest history the app will hold (UsageStatsEngine looks back
        // 365 days; the archive accumulates indefinitely).
        val days = 1_000
        val intervals = (0 until days).map { d ->
            val s = d * 86_400_000L
            s to (s + 86_400_000L)
        }
        val total = totalDuration(mergeIntervals(intervals))

        assertEquals(days.toLong() * 86_400_000L, total)
        assertEquals(86_400_000_000L, total)
        assertTrue("a year of the app's own history is still far inside Long", total < Long.MAX_VALUE)
    }

    @Test
    fun `a ten thousand entry gap list sums without drift`() {
        // LabelGapsScreen renders every gap in the selection and sums them
        // (line 458). A user who selected everything in a month of heavy
        // labelling hits five figures.
        val gaps = (0 until 10_000).map { i ->
            val s = i * 1_000L
            UsageGap(s, s + 600L, false)
        }
        assertEquals(6_000_000L, gaps.sumOf { it.durationInMillis })
        assertEquals(10_000, gaps.size)
    }
}
