package andromedvn.heuristic.activity.tracker.domain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Day-boundary behaviour of the time-accounting domain.
 *
 * Every "day" the app shows is a *Calendar* window, not a fixed 86_400_000 ms
 * span. `DashboardViewModel.getStartOfDay` / `getEndOfDay` (lines 412-413) build
 * `[00:00:00.000, 23:59:59.999]` in the device's local zone, and that window is
 * what both the invariant-1 clamp (`minOf(totalCombinedRaw, end - start)`,
 * line 225) and the gap-chunking loop (lines 337-343) are measured against.
 *
 * Nothing in the suite pinned those two numbers down. The existing
 * `OfflineShardingTest.sharding never lets app and offline exceed the day`
 * hardcodes `window = 86_400_000L`, which is NOT the window production uses —
 * the real one is 1 ms shorter. That 1 ms matters twice over: it is the exact
 * clamp value, and it is the reason the 24-hour gap-labelling loop silently
 * loses a millisecond at every midnight it crosses.
 */
class DayBoundaryInvariantTest {

    private var originalZone: TimeZone = TimeZone.getDefault()

    @Before
    fun pinZone() {
        originalZone = TimeZone.getDefault()
        // Asia/Manila has no DST. The DST behaviour belongs in DstClockChangeTest;
        // this class pins the *simple* day arithmetic so it is not polluted by it.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Manila"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    // --- mirrors of the production day helpers ----------------------------

    /** Verbatim logic of `DashboardViewModel.getStartOfDay`. */
    private fun getStartOfDay(cal: Calendar): Long {
        val clone = cal.clone() as Calendar
        clone.set(Calendar.HOUR_OF_DAY, 0)
        clone.set(Calendar.MINUTE, 0)
        clone.set(Calendar.SECOND, 0)
        clone.set(Calendar.MILLISECOND, 0)
        return clone.timeInMillis
    }

    /** Verbatim logic of `DashboardViewModel.getEndOfDay`. */
    private fun getEndOfDay(cal: Calendar): Long {
        val clone = cal.clone() as Calendar
        clone.set(Calendar.HOUR_OF_DAY, 23)
        clone.set(Calendar.MINUTE, 59)
        clone.set(Calendar.SECOND, 59)
        clone.set(Calendar.MILLISECOND, 999)
        return clone.timeInMillis
    }

    private fun calAt(year: Int, month: Int, day: Int, hour: Int, minute: Int, sec: Int, ms: Int): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, sec)
            set(Calendar.MILLISECOND, ms)
        }

    /**
     * The day-chunking loop, mirrored from `DashboardViewModel.saveGap`
     * (lines 336-343) and repeated verbatim in `saveMultipleGaps`,
     * `convertGhostTime` and `convertMultipleGhostTimes`. Every labelled gap
     * and every converted ghost is persisted through this loop, so its output
     * is the ground truth of every offline total in the app.
     *
     * Returns the list of (timestamp, durationInMillis) rows that would be
     * handed to `repository.saveOfflineActivity`.
     */
    private fun chunkByLocalDay(start: Long, end: Long): List<Interval> {
        val rows = mutableListOf<Interval>()
        var curr = start
        while (curr < end) {
            val cal = Calendar.getInstance().apply { timeInMillis = curr }
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val chunkEnd = minOf(end, cal.timeInMillis)
            rows.add(curr to chunkEnd)
            curr = chunkEnd + 1
        }
        return rows
    }

    /** The sum of the recorded per-chunk durations, as persisted. */
    private fun recordedMillis(rows: List<Interval>): Long =
        rows.sumOf { it.second - it.first }

    // --- the window the clamp actually uses -------------------------------

    @Test
    fun `the dashboard day window is 86399999 ms, one millisecond short of a full day`() {
        // A full day is 86_400_000 ms. Production does NOT use that as the
        // window: getEndOfDay is 23:59:59.999, so `end - start` is 1 ms short.
        val cal = calAt(2024, Calendar.MARCH, 10, 12, 0, 0, 0)
        val start = getStartOfDay(cal)
        val end = getEndOfDay(cal)

        assertEquals(86_399_999L, end - start)
        assertTrue(
            "production's window is 1 ms short of a nominal day",
            end - start != 86_400_000L
        )
    }

    @Test
    fun `the clamp window matches the end-of-day timestamp to the millisecond`() {
        val cal = calAt(2024, Calendar.MARCH, 10, 12, 0, 0, 0)
        val start = getStartOfDay(cal)
        val end = getEndOfDay(cal)

        // The clamp at line 225 is `minOf(totalCombinedRaw, end - start)`. It is
        // only correct if `end` really is the last millisecond of the day.
        val expectedEnd = calAt(2024, Calendar.MARCH, 10, 23, 59, 59, 999).timeInMillis
        assertEquals(expectedEnd, end)
        assertEquals(getStartOfDay(calAt(2024, Calendar.MARCH, 10, 0, 0, 0, 0)), start)
    }

    // --- INVARIANT 1: the exact case where raw offline + interactive would exceed the day

    @Test
    fun `sharding is what holds the day together when the raw figures would overflow it`() {
        // The exact case the task asks for. A user who labelled their entire day
        // "Sleep", and who also used two apps for an hour in total.
        //
        //   offline raw   = 86_400_000   (the whole day)
        //   app total     =  4_600_000   (1h + 1h)
        //   raw sum       = 91_000_000   -> 105% of the day: WRONG
        //   sharded       = 81_800_000
        //   app + sharded = 86_400_000   -> exactly the day: the invariant
        val app = mergeIntervals(listOf(0L to 3_600_000L, 8_000_000L to 9_000_000L))
        val offlineRaw = listOf(0L to 86_400_000L)

        val appTotal = totalDuration(app)
        val rawOfflineTotal = totalDuration(offlineRaw)
        val shardedOfflineTotal = totalDuration(subtractIntervals(offlineRaw, app))

        assertEquals(4_600_000L, appTotal)
        assertEquals(86_400_000L, rawOfflineTotal)
        assertEquals(81_800_000L, shardedOfflineTotal)

        assertEquals(
            "without sharding the day is over-reported by 4_600_000 ms",
            4_600_000L,
            (rawOfflineTotal + appTotal) - 86_400_000L
        )
        assertEquals(
            "with sharding the day lands exactly on 86_400_000",
            86_400_000L,
            appTotal + shardedOfflineTotal
        )
        assertTrue(
            "invariant 1: app + sharded offline must fit the day",
            appTotal + shardedOfflineTotal <= 86_400_000L
        )
    }

    @Test
    fun `app usage alone covering the whole day still leaves zero offline`() {
        // The mirror case: sharding must be symmetric, not offline-biased.
        val app = mergeIntervals(listOf(0L to 86_400_000L))
        val offlineRaw = listOf(0L to 86_400_000L)
        val sharded = subtractIntervals(offlineRaw, app)

        assertEquals(86_400_000L, totalDuration(app))
        assertEquals(emptyList<Interval>(), sharded)
        assertEquals(0L, totalDuration(sharded))
    }

    // --- a session spanning midnight --------------------------------------

    @Test
    fun `a session spanning midnight is double counted by exactly one millisecond across the two days`() {
        // PINNED DEFECT. 23:30 on 2024-03-10 through 00:30 on 2024-03-11, one
        // continuous stored interval. Slicing it per day gives:
        //   day 10 -> [23:30:00.000, 23:59:59.999]  = 1_800_000 ms
        //   day 11 -> [23:59:59.999, 00:30:00.000]  = 1_800_001 ms
        // The millisecond AT 23:59:59.999 is the last instant of day 10 and the
        // first instant of day 11, so both days claim it. The two figures sum
        // to 3_600_001 for a 3_600_000 ms session.
        //
        // This is the same off-by-one as the chunking drift below, seen from
        // the read side: `getEndOfDay` is inclusive, so day N's slice and day
        // N+1's slice share an endpoint.
        val day10 = getStartOfDay(calAt(2024, Calendar.MARCH, 10, 12, 0, 0, 0)) to
            getEndOfDay(calAt(2024, Calendar.MARCH, 10, 12, 0, 0, 0))
        val day11 = getStartOfDay(calAt(2024, Calendar.MARCH, 11, 0, 0, 0, 0)) to
            getEndOfDay(calAt(2024, Calendar.MARCH, 11, 0, 0, 0, 0))

        val sessionStart = calAt(2024, Calendar.MARCH, 10, 23, 30, 0, 0).timeInMillis
        val sessionEnd = calAt(2024, Calendar.MARCH, 11, 0, 30, 0, 0).timeInMillis
        val stored = listOf(sessionStart to sessionEnd)

        val onDay10 = subtractIntervals(stored, listOf(day11))
        val onDay11 = subtractIntervals(stored, listOf(day10))

        assertEquals(3_600_000L, totalDuration(stored))
        assertEquals(1_800_000L, totalDuration(onDay10))
        assertEquals(1_800_001L, totalDuration(onDay11))
        assertEquals(
            "the shared boundary millisecond is counted by both days",
            1L,
            totalDuration(onDay10) + totalDuration(onDay11) - totalDuration(stored)
        )
    }

    @Test
    fun `a session spanning midnight replays into the same interval it was stored as`() {
        // replayUsageIntervals clamps to [start, end]. An event stream that spans
        // midnight, replayed for a single day, must return the day's own slice
        // and nothing of the neighbouring day.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, calAt(2024, Calendar.MARCH, 10, 23, 30, 0, 0).timeInMillis, "com.a", "A"),
            RawEvent(EventCode.ACTIVITY_PAUSED, calAt(2024, Calendar.MARCH, 11, 0, 30, 0, 0).timeInMillis, "com.a", "A")
        )
        val dayStart = getStartOfDay(calAt(2024, Calendar.MARCH, 10, 0, 0, 0, 0))
        val dayEnd = getEndOfDay(calAt(2024, Calendar.MARCH, 10, 0, 0, 0, 0))

        val result = replayUsageIntervals(events, dayStart, dayEnd)

        assertEquals(1, result.size)
        val slice = result.getValue("com.a").single()
        assertEquals(dayStart + 23L * 3_600_000L + 30L * 60_000L, slice.first)
        assertEquals("the interval must stop at the day boundary, not run into 3/11", dayEnd, slice.second)
    }

    // --- exactly-on-midnight and degenerate timestamps --------------------

    @Test
    fun `an event starting exactly at local midnight is kept whole`() {
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, calAt(2024, Calendar.MARCH, 10, 0, 0, 0, 0).timeInMillis, "com.a", "A"),
            RawEvent(EventCode.ACTIVITY_PAUSED, calAt(2024, Calendar.MARCH, 10, 0, 5, 0, 0).timeInMillis, "com.a", "A")
        )
        val dayStart = getStartOfDay(calAt(2024, Calendar.MARCH, 10, 0, 0, 0, 0))
        val dayEnd = getEndOfDay(calAt(2024, Calendar.MARCH, 10, 0, 0, 0, 0))

        val slice = replayUsageIntervals(events, dayStart, dayEnd).getValue("com.a").single()
        assertEquals(dayStart, slice.first)
        assertEquals(300_000L, totalDuration(listOf(slice)))
    }

    @Test
    fun `an event whose start equals its end produces no interval`() {
        // Identical start/end millis. The replay guards with
        // `if (state.second < event.timestamp)`, so this must be dropped rather
        // than stored as a zero-width row.
        val ts = calAt(2024, Calendar.MARCH, 10, 9, 0, 0, 0).timeInMillis
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, ts, "com.a", "A"),
            RawEvent(EventCode.ACTIVITY_PAUSED, ts, "com.a", "A")
        )
        val dayStart = getStartOfDay(calAt(2024, Calendar.MARCH, 10, 0, 0, 0, 0))
        val dayEnd = getEndOfDay(calAt(2024, Calendar.MARCH, 10, 0, 0, 0, 0))

        assertEquals(emptyMap<String, List<Interval>>(), replayUsageIntervals(events, dayStart, dayEnd))
    }

    @Test
    fun `the midnight boundary millisecond is owned by exactly one day`() {
        // The .999 ms of a day belongs to that day. The next day starts at .000.
        // If both were ever claimed, a fully-labelled day would sum to 86_400_001.
        val dayEnd = getEndOfDay(calAt(2024, Calendar.MARCH, 10, 0, 0, 0, 0))
        val nextStart = getStartOfDay(calAt(2024, Calendar.MARCH, 11, 0, 0, 0, 0))
        assertEquals(dayEnd + 1, nextStart)
    }

    // --- the chunking loop's millisecond drift ----------------------------

    @Test
    fun `labelling a single day records the window exactly`() {
        val cal = calAt(2024, Calendar.MARCH, 10, 0, 0, 0, 0)
        val start = getStartOfDay(cal)
        val end = getEndOfDay(cal)

        val rows = chunkByLocalDay(start, end)

        assertEquals(1, rows.size)
        assertEquals(end - start, recordedMillis(rows))
    }

    @Test
    fun `labelling across one midnight loses exactly one millisecond`() {
        // The regression. `curr = chunkEnd + 1` skips past 23:59:59.999 to the
        // next 00:00:00.000, and each chunk is half-open, so the final
        // millisecond of every day after the first is never persisted.
        val start = getStartOfDay(calAt(2024, Calendar.MARCH, 10, 0, 0, 0, 0))
        val end = getEndOfDay(calAt(2024, Calendar.MARCH, 11, 0, 0, 0, 0))

        val rows = chunkByLocalDay(start, end)
        val span = end - start
        val recorded = recordedMillis(rows)

        assertEquals(2, rows.size)
        assertEquals("one chunk per calendar day", 86_399_999L, recorded / 2)
        assertEquals(
            "this is the drift: 1 ms is dropped at the shared boundary",
            1L,
            span - recorded
        )
    }

    @Test
    fun `the chunking drift is one millisecond per day boundary, not a fixed offset`() {
        // Guards against a "fix" that special-cases two days. The loss must
        // scale with the number of midnights crossed.
        val start = getStartOfDay(calAt(2024, Calendar.MARCH, 10, 0, 0, 0, 0))
        for (days in 1..5) {
            val end = getEndOfDay(calAt(2024, Calendar.MARCH, 10 + days - 1, 0, 0, 0, 0))
            val rows = chunkByLocalDay(start, end)
            assertEquals("$days days should chunk into $days rows", days, rows.size)
            assertEquals(
                "a $days-day label must lose exactly ${days - 1} ms",
                (days - 1).toLong(),
                (end - start) - recordedMillis(rows)
            )
        }
    }

    @Test
    fun `the chunking loop terminates when the gap starts and ends at the same instant`() {
        val start = getStartOfDay(calAt(2024, Calendar.MARCH, 10, 0, 0, 0, 0))
        assertEquals("a zero-length gap writes nothing", emptyList<Interval>(), chunkByLocalDay(start, start))
    }
}
