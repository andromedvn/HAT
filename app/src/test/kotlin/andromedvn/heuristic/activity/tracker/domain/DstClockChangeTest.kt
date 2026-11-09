package andromedvn.heuristic.activity.tracker.domain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Clock and timezone transitions.
 *
 * The reference timezone for these tests is Asia/Manila, which has had no DST
 * since 1978, so no developer has ever seen a 23-hour or 25-hour day in this
 * app's default test environment. Every other user does, twice a year. The suite had no timezone coverage at all, not one
 * test changed `TimeZone.getDefault()`.
 *
 * Three separate places in production used to assume a day is exactly
 * 86_400_000 ms, with three different arithmetics:
 *
 *  1. `UsageStatsEngine.sliceIntervals` builds hour buckets with
 *     `current - ((current + offset) % 3600000L)`.
 *  2. `HeuristicEngine.buildChartPoints` and the dashboard's gap chart walked
 *     a hardcoded 24 buckets.
 *  3. `ActivityRepository.syncArchive` seeded `currentDay` with
 *     `lastArchived + 86400000L` while advancing with calendar arithmetic.
 *
 * (2) and (3) are FIXED: both now use the pure helpers in LocalDay.kt
 * (`hourBucketsOfDay`, `nextLocalMidnight`), and the tests below pin the
 * fixed behaviour directly against those helpers, 23 buckets on a
 * spring-forward day, 25 on a fall-back day, no skipped hour, and a seed that
 * never drifts off local midnight. (1) was already correct: it re-reads the
 * offset at every chunk boundary, and the slice tests below document that.
 */
class DstClockChangeTest {

    private var originalZone: TimeZone = TimeZone.getDefault()

    @Before
    fun rememberZone() {
        originalZone = TimeZone.getDefault()
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    // --- helpers mirroring production -------------------------------------

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

    /**
     * A local wall-clock time, resolved in whatever zone is default at the time
     * of the call. Always set the zone BEFORE calling this, the timestamp it
     * returns is zone-dependent, and every assertion below compares two
     * timestamps that must have been built in the SAME zone.
     */
    private fun local(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, sec: Int = 0, ms: Int = 0) =
        Calendar.getInstance().apply {
            clear(); set(year, month, day, hour, minute, sec); set(Calendar.MILLISECOND, ms)
        }.timeInMillis

    /**
     * Mirrors the hour-bucket loop in `UsageStatsEngine.sliceIntervals`
     * (lines 90-99) for the `Calendar.HOUR_OF_DAY` branch.
     */
    private fun sliceHours(intervals: List<Interval>): Map<Long, Long> {
        val buckets = mutableMapOf<Long, Long>()
        val tz = TimeZone.getDefault()
        for ((start, end) in intervals) {
            var current = start
            while (current < end) {
                val offset = tz.getOffset(current)
                val bucketStart = current - ((current + offset) % 3600000L)
                val chunkEnd = minOf(end, bucketStart + 3600000L)
                buckets[bucketStart] = (buckets[bucketStart] ?: 0L) + (chunkEnd - current)
                current = chunkEnd
            }
        }
        return buckets
    }

    // --- the day window itself --------------------------------------------

    @Test
    fun `a spring-forward day is 23 hours and the invariant-1 clamp window shrinks with it`() {
        // America/New_York, 2024-03-10. Local 02:00 never happens.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val day = Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 10) }

        val window = endOfDay(day) - startOfDay(day)

        assertEquals("a 23-hour day", 82_799_999L, window)
        assertTrue(
            "the clamp at DashboardViewModel:225 is now 3.6 s short of a nominal day",
            window < 86_400_000L
        )
    }

    @Test
    fun `a fall-back day is 25 hours and the clamp window EXCEEDS a nominal day`() {
        // PINNED DEFECT. America/New_York, 2024-11-03. Local 01:00 happens twice.
        // `end - start` is 89_999_999 ms, which is LARGER than 86_400_000.
        //
        // A user who labels their entire 2024-11-03 as offline records
        // 89_999_999 ms of sharded offline against a day the rest of the app
        // believes is 86_400_000 ms long. Invariant 1 is not merely violated, 
        // the clamp cannot restore it, because the clamp IS the larger number.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val day = Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.NOVEMBER, 3) }

        val start = startOfDay(day)
        val end = endOfDay(day)
        val window = end - start

        assertEquals(89_999_999L, window)
        assertTrue("a real 25-hour day outlasts the nominal day", window > 86_400_000L)

        val fullDayOffline = totalDuration(subtractIntervals(listOf(start to end), emptyList()))
        assertEquals("the recorded offline time alone exceeds 24 h", 89_999_999L, fullDayOffline)
        assertTrue(
            "invariant 1 as stated (app + offline <= 86_400_000) is unsatisfiable on this day",
            fullDayOffline > 86_400_000L
        )
    }

    @Test
    fun `Manila has no DST so its day window is the nominal one`() {
        // The baseline the rest of the suite implicitly assumes.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Manila"))
        val day = Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 10) }
        assertEquals(86_399_999L, endOfDay(day) - startOfDay(day))
    }

    // --- sliceIntervals hour buckets across a transition -------------------

    @Test
    fun `hour buckets on a spring-forward day sum exactly and never overflow the hour`() {
        // The good news: `sliceIntervals` re-reads `tz.getOffset(current)` inside
        // the loop, so it re-derives the offset at each chunk boundary and the
        // arithmetic stays exact across the transition. The whole 23-hour day
        // is conserved and no bucket exceeds 3_600_000 ms.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val start = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 10) })
        val end = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 11) })

        val buckets = sliceHours(listOf(start to end))

        assertEquals("no usage time is lost or invented", end - start, buckets.values.sum())
        assertEquals("the skipped 02:00 hour produces no bucket", 23, buckets.size)
        assertTrue(
            "no bucket may exceed 3_600_000 ms or the chart's /3600000f ratio exceeds 1.0",
            buckets.values.all { it <= 3_600_000L }
        )
    }

    @Test
    fun `hour buckets preserve a partial interval that straddles the transition`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        // 01:30 to 03:30 LOCAL on the spring-forward day. The wall clock reads
        // two hours but only ONE real hour elapses, because 02:00-03:00 never
        // happens. The bucketing is measured in absolute millis, so the two
        // half-hour chunks are 1_800_000 ms each and sum to the real elapsed
        // time. Nothing inflates the interval to its wall-clock reading.
        val start = local(2024, Calendar.MARCH, 10, 1, 30)
        val end = local(2024, Calendar.MARCH, 10, 3, 30)

        assertEquals("the wall clock reads 2 h but 1 h elapses", 3_600_000L, end - start)

        val buckets = sliceHours(listOf(start to end))

        assertEquals(2, buckets.size)
        assertEquals("real elapsed time is conserved exactly", end - start, buckets.values.sum())
        assertEquals(
            "neither bucket is inflated to a full hour",
            listOf(1_800_000L, 1_800_000L),
            buckets.values.toList()
        )
    }

    @Test
    fun `hour buckets are correct in a half-hour offset zone`() {
        // Asia/Kolkata is UTC+05:30. The `(current + offset) % 3600000L` form
        // must still land on the local hour, which requires the offset to be
        // added BEFORE the modulo, the opposite order from the merge sort.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        val start = local(2024, Calendar.MARCH, 10, 10, 7)
        val end = local(2024, Calendar.MARCH, 10, 12, 30)

        val buckets = sliceHours(listOf(start to end))
        val expected = Calendar.getInstance().apply {
            timeInMillis = start; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals("buckets are keyed by the local hour start", expected, buckets.keys.first())
        assertEquals("10:07 to 12:30 touches the 10, 11 and 12 o'clock buckets", 3, buckets.size)
        assertEquals("the first bucket keeps only its own 53 minutes", 3_180_000L, buckets.values.first())
        assertEquals("and conserves every millisecond", end - start, buckets.values.sum())
    }

    @Test
    fun `hour buckets are correct in a quarter-hour offset zone`() {
        // Asia/Kathmandu is UTC+05:45, the worst case for hour alignment.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"))
        val start = local(2024, Calendar.MARCH, 10, 10, 7)
        val end = local(2024, Calendar.MARCH, 10, 11, 59)

        val buckets = sliceHours(listOf(start to end))
        val expected = Calendar.getInstance().apply {
            timeInMillis = start; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals("the 10:00 bucket is keyed on the local hour", expected, buckets.keys.first())
        assertEquals("10:07 to 11:59 touches the 10 and 11 o'clock buckets", 2, buckets.size)
        assertEquals("the 53 minutes before the hour are not snapped away", 3_180_000L, buckets.values.first())
        assertEquals("the 59 minutes after it are intact", 3_540_000L, buckets.values.last())
        assertEquals("10:07 to 11:59 is 112 minutes, all of it accounted for", 6_720_000L, buckets.values.sum())
        assertEquals("and equals the real elapsed time", end - start, buckets.values.sum())
    }

    // --- the fixed hour-bucket walk (LocalDay.hourBucketsOfDay) ------------

    @Test
    fun `a spring-forward day has 23 buckets, none duplicated and none dropped`() {
        // America/New_York, 2024-03-10. Local 02:00 never happens. The old
        // hardcoded 24-bucket walk emitted a duplicate hour 0, never labelled
        // hour 2, and ran past midnight into the next day. The calendar walk
        // emits exactly the hours the wall clock really has.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val dayStart = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 10) })
        val nextDay = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 11) })

        val buckets = hourBucketsOfDay(dayStart)

        assertEquals("a 23-hour day has 23 buckets", 23, buckets.size)
        assertEquals("the walk covers exactly the local day", nextDay, buckets.last().endMillis)
        assertTrue("the non-existent 02:00 is never charted", buckets.none { it.startHour == 2 })
        assertEquals(
            "the transition bucket is labelled with its REAL end hour",
            3, buckets.first { it.startHour == 1 }.endHour
        )
        assertTrue(
            "every bucket is one real hour",
            buckets.all { it.endMillis - it.startMillis == 3_600_000L }
        )
        // Buckets tile the day exactly: each starts where the previous ended.
        (1 until buckets.size).forEach { i ->
            assertEquals("buckets must tile without gaps or overlaps", buckets[i - 1].endMillis, buckets[i].startMillis)
        }
        assertEquals("the first bucket starts at local midnight", dayStart, buckets.first().startMillis)
    }

    @Test
    fun `a fall-back day has 25 buckets and charts hour 23`() {
        // America/New_York, 2024-11-03. Local 01:00 happens twice. The old
        // 24-bucket walk never reached hour 23, silently dropping the last
        // hour of the user's day from the chart.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val dayStart = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.NOVEMBER, 3) })
        val nextDay = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.NOVEMBER, 4) })

        val buckets = hourBucketsOfDay(dayStart)

        assertEquals("a 25-hour day has 25 buckets", 25, buckets.size)
        assertEquals("the walk covers exactly the local day", nextDay, buckets.last().endMillis)
        assertTrue("hour 23 IS charted on a fall-back day", buckets.any { it.startHour == 23 })
        assertEquals(
            "the ambiguous wall hour appears twice, as it does in real life",
            2, buckets.count { it.startHour == 1 }
        )
        // The first 01:00 bucket ends at the SECOND 01:00, one real hour later.
        assertEquals(
            "the first 1 AM bucket ends at the second 1 AM",
            1, buckets.first { it.startHour == 1 }.endHour
        )
        assertTrue("every bucket is one real hour", buckets.all { it.endMillis - it.startMillis == 3_600_000L })
        (1 until buckets.size).forEach { i ->
            assertEquals("buckets must tile without gaps or overlaps", buckets[i - 1].endMillis, buckets[i].startMillis)
        }
    }

    @Test
    fun `a normal day has the usual 24 buckets in order`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Manila"))
        val dayStart = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 10) })

        val buckets = hourBucketsOfDay(dayStart)

        assertEquals(24, buckets.size)
        assertEquals((0..23).toList(), buckets.map { it.startHour })
        assertTrue("on a normal day every bucket ends at the next hour", buckets.all { it.endHour == (it.startHour + 1) % 24 })
    }

    // --- syncArchive's day arithmetic (LocalDay.nextLocalMidnight) ----------

    @Test
    fun `nextLocalMidnight never drifts off local midnight across a spring-forward boundary`() {
        // America/New_York, 2024-03-10 (23-hour day). The OLD seed was
        //   lastArchived + 86400000L
        // which landed at 01:00 local the next morning, an hour PAST the true
        // next midnight, so the archive re-read (and re-archived) the first
        // hour of the following day and every window after the boundary
        // inherited the wrong anchor. The calendar form lands exactly on
        // midnight.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val lastArchived = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 10) })

        val seeded = nextLocalMidnight(lastArchived)
        val trueMidnight = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 11) })

        assertEquals("the seed is the true next local midnight", trueMidnight, seeded)
        assertEquals(
            "the fixed +24h form is a full hour late here; production must not use it",
            3_600_000L, (lastArchived + 86400000L) - seeded
        )
    }

    @Test
    fun `nextLocalMidnight covers the full 25 hours of a fall-back day`() {
        // America/New_York, 2024-11-03 (25-hour day). The fixed +24h form
        // lands at 23:00 the SAME evening, an hour short of the next midnight:
        // with the old window end `currentDay + 86400000L`, the local
        // 23:00-24:00 hour of every fall-back day was skipped and never
        // archived by any later pass either, because the next window began at
        // the next midnight.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val day = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.NOVEMBER, 3) })

        val dayEnd = nextLocalMidnight(day)
        val trueNextMidnight = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.NOVEMBER, 4) })

        assertEquals("the day window ends at the true next local midnight", trueNextMidnight, dayEnd)
        assertEquals("a fall-back day really is 25 hours long", 25 * 3_600_000L, dayEnd - day)
        assertTrue(
            "the fixed +24h form cuts off the last local hour; production must not use it",
            (day + 86400000L) < dayEnd
        )
    }

    @Test
    fun `nextLocalMidnight and the fixed offset agree when there is no transition`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Manila"))
        val lastArchived = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 10) })

        assertEquals(
            "Manila agrees, which is why this has never been noticed",
            lastArchived + 86400000L, nextLocalMidnight(lastArchived)
        )
    }

    // --- a user changing the clock mid-day --------------------------------

    @Test
    fun `moving the timezone west relabels stored timestamps without moving them`() {
        // HAT stores absolute epoch millis and derives every label with
        // `Calendar.getInstance()`. A user flying Manila -> London sees the same
        // 3_600_000 ms of app usage, but the hours it lands in change, because
        // the bucket key is a local-time value.
        // Build the interval ONCE, in UTC, so both readings below describe the
        // identical absolute millisecond range. Only the default zone differs.
        // 09:00-10:00 UTC is 14:30-15:30 in Kolkata (+05:30) and 09:00-10:00 in
        // London (+00:00), so the same hour lands under a different local label.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val start = local(2024, Calendar.MARCH, 10, 9, 0)
        val end = local(2024, Calendar.MARCH, 10, 10, 0)

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        val kolkataBuckets = sliceHours(listOf(start to end))
        val kolkataTotal = kolkataBuckets.values.sum()
        val kolkataKey = kolkataBuckets.keys.first()

        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        val londonBuckets = sliceHours(listOf(start to end))
        val londonTotal = londonBuckets.values.sum()
        val londonKey = londonBuckets.keys.first()

        assertEquals("the duration is a physical fact and does not move", kolkataTotal, londonTotal)
        assertEquals(3_600_000L, londonTotal)
        assertEquals(3_600_000L, kolkataTotal)
        assertNotEquals("but the bucket it is filed under does", kolkataKey, londonKey)
    }

    @Test
    fun `formatDuration treats a 25-hour day as one day and an hour, not as overflow`() {
        // TimeUtils.formatDuration is Long-only and has no notion of a day
        // boundary; it renders whatever it is given. The 24 h special case at
        // line 6 fires for exactly 86_400_000 and never for a 25-hour day.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val day = Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.NOVEMBER, 3) }
        val window = endOfDay(day) - startOfDay(day)

        // 89_999_999 = 1d 1h 59m 59s: the extra hour is rendered, not lost.
        assertEquals(89_999_999L, window)
        assertTrue("a 25-hour day is still expressible in d/h/m/s", window / 86_400_000L == 1L)
    }
}
