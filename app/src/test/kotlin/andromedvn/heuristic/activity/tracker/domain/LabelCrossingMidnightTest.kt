package andromedvn.heuristic.activity.tracker.domain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * A label that crosses midnight.
 *
 * Finding: the day-bucketing DOES split such a label and DOES give each day its
 * own share, the second day is not skipped. But the two shares do not sum to
 * the label's duration: they are short by exactly 1 ms per midnight crossed,
 * and the cause is a convention shared by the writer and the reader, not a
 * missing split.
 *
 * Writer, `DashboardViewModel.saveGap` (and the same loop in
 * `saveMultipleGaps`, `convertGhostTime`, `convertMultipleGhostTimes`):
 *
 *     while (curr < end) {
 *         cal.timeInMillis = curr
 *         cal.set(HOUR_OF_DAY, 23); set(MINUTE, 59); set(SECOND, 59); set(MILLISECOND, 999)
 *         val chunkEnd = minOf(end, cal.timeInMillis)
 *         save(label, chunkEnd - curr, curr)
 *         curr = chunkEnd + 1
 *     }
 *
 * `getEndOfDay` is INCLUSIVE (23:59:59.999) while each stored row is a duration
 * from `curr`, so row N covers up to and including 23:59:59.999 and `curr =
 * chunkEnd + 1` then resumes at the next 00:00:00.000. The millisecond
 * 23:59:59.999 is counted once, not lost, but the row's stored duration is
 * `chunkEnd - curr` over a half-open range, so the last millisecond of every
 * day after the first is never persisted. Already pinned by
 * DayBoundaryInvariantTest ("labelling across one midnight loses exactly one
 * millisecond").
 *
 * Reader, `HeuristicEngine` clamps each row into the queried day with
 * `sanitizeOfflineRow`, which is exact given the window it is given. The
 * 1 ms is in the window, not the function: with the inclusive `getEndOfDay`,
 * day N's slice stops at 23:59:59.999 and day N+1's starts at 00:00:00.000, so
 * the day N slice is 1 ms shorter than the writer stored. No millisecond is
 * DUPLICATED by this, day N ends 1 ms before day N+1 begins. Only lost.
 *
 * A second, larger finding, in a file outside the domain: the read path cannot
 * see the second day's share at all.
 * `OfflineStorage.getOfflineActivitiesBetween` selects
 * `timestamp >= start AND timestamp <= end`, so a row that BEGAN on the
 * previous day is never returned when the next day is queried. For a label
 * split by the writer this is harmless (each stored row lies inside one day),
 * which is why the split exists, but for a row that crossed midnight intact
 * (an older schema, or a vault import) the next day receives nothing. That
 * query is `OfflineStorage.kt:381`, not a file this change may touch, so it is
 * reported rather than fixed.
 */
class LabelCrossingMidnightTest {

    private var originalZone: TimeZone = TimeZone.getDefault()

    @Before
    fun pinZone() {
        originalZone = TimeZone.getDefault()
        // No DST, so a day is 86_399_999 ms. DST behaviour is DstClockChangeTest's
        // job; mixing the two would make these numbers ambiguous.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Manila"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    // --- production day helpers (DashboardViewModel:412-413) ---------------

    private fun startOfDay(cal: Calendar): Long {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun endOfDay(cal: Calendar): Long {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59)
        c.set(Calendar.SECOND, 59); c.set(Calendar.MILLISECOND, 999)
        return c.timeInMillis
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            clear(); set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0)
        }

    // --- the writer, mirrored ----------------------------------------------

    /** `DashboardViewModel.saveGap`'s loop, verbatim. */
    private fun chunkAsProduction(start: Long, end: Long): List<Interval> {
        val rows = mutableListOf<Interval>()
        var curr = start
        while (curr < end) {
            val cal = Calendar.getInstance().apply { timeInMillis = curr }
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
            val chunkEnd = minOf(end, cal.timeInMillis)
            rows.add(curr to chunkEnd)
            curr = chunkEnd + 1
        }
        return rows
    }

    /** The same loop with a half-open day boundary, the shape that is exact. */
    private fun chunkHalfOpen(start: Long, end: Long): List<Interval> {
        val rows = mutableListOf<Interval>()
        var curr = start
        while (curr < end) {
            val cal = Calendar.getInstance().apply { timeInMillis = curr }
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val chunkEnd = minOf(end, cal.timeInMillis)
            rows.add(curr to chunkEnd)
            curr = chunkEnd
        }
        return rows
    }

    // --- the reader, mirrored ----------------------------------------------

    /**
     * The WHERE clause of `OfflineStorage.getOfflineActivitiesBetween`:
     * `timestamp >= start AND timestamp <= end`. A row is returned only for the
     * day it BEGAN on.
     */
    private fun isVisibleToQuery(rowTimestamp: Long, start: Long, end: Long): Boolean =
        rowTimestamp >= start && rowTimestamp <= end

    // --- the label under test ----------------------------------------------

    private val day10 = at(2024, Calendar.MARCH, 10, 12, 0)
    private val day11 = at(2024, Calendar.MARCH, 11, 12, 0)
    private val day12 = at(2024, Calendar.MARCH, 12, 12, 0)

    /** 23:30 on the 10th through 00:30 on the 11th: a one-hour label. */
    private val labelStart = at(2024, Calendar.MARCH, 10, 23, 30).timeInMillis
    private val labelEnd = at(2024, Calendar.MARCH, 11, 0, 30).timeInMillis
    private val labelDuration = labelEnd - labelStart

    // --- the read side, in the production function -------------------------

    @Test
    fun `each day receives its own share of the label`() {
        val d10Start = startOfDay(day10); val d10End = endOfDay(day10)
        val d11Start = startOfDay(day11); val d11End = endOfDay(day11)
        val now = labelEnd + 86_400_000L

        val onDay10 = sanitizeOfflineRow(labelStart, labelDuration, d10Start, d10End, now)
        val onDay11 = sanitizeOfflineRow(labelStart, labelDuration, d11Start, d11End, now)

        assertEquals(labelStart to d10End, onDay10)
        assertEquals("the second day's slice starts at its own midnight", d11Start to labelEnd, onDay11)
        assertEquals(1_799_999L, totalDuration(listOf(onDay10!!)))
        assertEquals(1_800_000L, totalDuration(listOf(onDay11!!)))
        assertEquals("the two slices do not overlap", true, onDay10.second < onDay11.first)
    }

    @Test
    fun `the two slices sum to the label minus the inclusive end-of-day millisecond`() {
        val now = labelEnd + 86_400_000L
        val onDay10 = sanitizeOfflineRow(labelStart, labelDuration, startOfDay(day10), endOfDay(day10), now)!!
        val onDay11 = sanitizeOfflineRow(labelStart, labelDuration, startOfDay(day11), endOfDay(day11), now)!!
        val sum = totalDuration(listOf(onDay10, onDay11))

        assertEquals(labelDuration, sum + 1L)
        assertTrue("nothing is duplicated", sum < labelDuration)
    }

    @Test
    fun `sharding the label against app time does not inflate either day`() {
        // The invariant that matters, per day. App time and the label's share
        // are disjoint, so their sum reconstructs the day exactly.
        val d10Start = startOfDay(day10); val d10End = endOfDay(day10)
        val now = labelEnd + 86_400_000L

        val app = mergeIntervals(listOf(d10Start to at(2024, Calendar.MARCH, 10, 23, 30).timeInMillis))
        val labelShare = sanitizeOfflineRow(labelStart, labelDuration, d10Start, d10End, now)!!
        val sharded = subtractIntervals(listOf(labelShare), app)

        assertEquals("the whole share is labelled time, the app owns none of it", listOf(labelShare), sharded)
        assertTrue(
            "app + offline still fits the day window",
            totalDuration(app) + totalDuration(sharded) <= d10End - d10Start
        )
    }

    @Test
    fun `a label wholly inside one day is untouched`() {
        val start = at(2024, Calendar.MARCH, 10, 1, 0).timeInMillis
        val end = at(2024, Calendar.MARCH, 10, 3, 0).timeInMillis
        val now = end + 86_400_000L

        val row = sanitizeOfflineRow(start, end - start, startOfDay(day10), endOfDay(day10), now)
        assertEquals(start to end, row)
        assertEquals(7_200_000L, totalDuration(listOf(row!!)))
    }

    @Test
    fun `a label starting at exactly local midnight stays whole`() {
        val start = startOfDay(day10)
        val end = at(2024, Calendar.MARCH, 10, 1, 0).timeInMillis
        val row = sanitizeOfflineRow(start, end - start, startOfDay(day10), endOfDay(day10), end + 86_400_000L)
        assertEquals(start to end, row)
    }

    @Test
    fun `a three day label gives all three days a share`() {
        val start = at(2024, Calendar.MARCH, 10, 22, 0).timeInMillis
        val end = at(2024, Calendar.MARCH, 12, 2, 0).timeInMillis
        val duration = end - start
        val now = end + 86_400_000L

        val shares = listOf(day10, day11, day12).map {
            sanitizeOfflineRow(start, duration, startOfDay(it), endOfDay(it), now)
        }

        assertTrue("every day gets a share", shares.all { it != null })
        assertEquals("2 ms short over two boundaries", 2L, duration - shares.sumOf { totalDuration(listOf(it!!)) })
        assertTrue("the shares do not overlap", shares[0]!!.second < shares[1]!!.first)
        assertTrue(shares[1]!!.second < shares[2]!!.first)
    }
}
