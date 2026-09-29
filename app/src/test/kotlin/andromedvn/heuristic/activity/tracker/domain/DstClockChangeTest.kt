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
 * The dev device is Asia/Manila, which has had no DST since 1978, so no
 * developer has ever seen a 23-hour or 25-hour day in this app. Every other
 * user does, twice a year. The suite had no timezone coverage at all — not one
 * test changed `TimeZone.getDefault()`.
 *
 * Three separate places in production assume a day is exactly 86_400_000 ms:
 *
 *  1. `UsageStatsEngine.sliceIntervals` builds hour buckets with
 *     `current - ((current + offset) % 3600000L)`.
 *  2. `HeuristicEngine.buildChartPoints` walks 24 buckets with
 *     `tempCal.add(Calendar.HOUR_OF_DAY, 1)`.
 *  3. `ActivityRepository.syncArchive` seeds `currentDay` with
 *     `lastArchived + 86400000L` (line 59) while advancing with
 *     `driftCal.add(Calendar.DAY_OF_YEAR, 1)` (line 78).
 *
 * On a DST boundary the first is a fixed 24-hour offset and the second two are
 * calendar arithmetic, so they disagree — and only one of the three is right.
 * The tests below pin the *observed* behaviour with the real numbers, so the
 * next person to touch this code knows which case they are in.
 *
 * These are characterisation tests, not assertions of intent. Several of them
 * document behaviour that is wrong; that is stated explicitly in each.
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
     * of the call. Always set the zone BEFORE calling this — the timestamp it
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

    /**
     * Mirrors the DAY branch of `HeuristicEngine.buildChartPoints`
     * (lines 310-345): walk `count` buckets, each one hour wider than the last
     * via `add(HOUR_OF_DAY, 1)`, keyed by `cal.get(HOUR_OF_DAY)`.
     */
    private fun chartDayBucketHours(dayStart: Long, count: Int = 24): List<Int> {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
        val hours = mutableListOf<Int>()
        repeat(count) {
            hours.add(cal.get(Calendar.HOUR_OF_DAY))
            val temp = cal.clone() as Calendar
            temp.add(Calendar.HOUR_OF_DAY, 1)
            cal.timeInMillis = temp.timeInMillis
        }
        return hours
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
        // believes is 86_400_000 ms long. Invariant 1 is not merely violated —
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
        // added BEFORE the modulo — the opposite order from the merge sort.
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
        // Asia/Kathmandu is UTC+05:45 — the worst case for hour alignment.
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

    // --- buildChartPoints' 24-bucket walk ---------------------------------

    @Test
    fun `the 24-bucket chart walk emits a duplicate hour and drops one on a spring-forward day`() {
        // PINNED DEFECT. `add(HOUR_OF_DAY, 1)` is calendar arithmetic, so on
        // 2024-03-10 the walk steps 00 -> 01 -> 03 (02:00 does not exist) and
        // by the 24th step it has run past local midnight into the next day.
        // Result: 24 ChartPoints but only 23 distinct syncTags, hour 2 is never
        // labelled, and hour 0 is labelled twice. A chart highlight lookup
        // (`dataPoints.indexOfFirst { it.syncTag == targetTag }` in Charts.kt)
        // resolves to whichever of the two hour-0 points comes first.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val dayStart = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 10) })

        val hours = chartDayBucketHours(dayStart)

        assertEquals("the walk still produces 24 points", 24, hours.size)
        assertEquals("but only 23 distinct labels", 23, hours.toSet().size)
        assertTrue("the non-existent 02:00 is never labelled", 2 !in hours)
        assertEquals("00 AM is labelled twice", 2, hours.count { it == 0 })
    }

    @Test
    fun `the 24-bucket chart walk loses the last hour on a fall-back day`() {
        // PINNED DEFECT. On the 25-hour 2024-11-03 the walk visits 23 distinct
        // hours and never reaches hour 23, so the final hour of the user's day
        // is simply not charted.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val dayStart = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.NOVEMBER, 3) })

        val hours = chartDayBucketHours(dayStart)

        assertEquals(24, hours.size)
        assertEquals(23, hours.toSet().size)
        assertTrue("hour 23 is never charted on a 25-hour day", 23 !in hours)
    }

    @Test
    fun `the 24-bucket chart walk is correct on a day with no transition`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Manila"))
        val dayStart = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 10) })

        val hours = chartDayBucketHours(dayStart)

        assertEquals(24, hours.toSet().size)
        assertEquals((0..23).toList(), hours)
    }

    // --- syncArchive's mixed arithmetic ------------------------------------

    @Test
    fun `syncArchive seeds the next day with a fixed 24 h offset and drifts an hour across DST`() {
        // PINNED DEFECT. ActivityRepository.kt:59 computes
        //   currentDay = lastArchived + 86400000L
        // but line 78 advances with
        //   driftCal.add(Calendar.DAY_OF_YEAR, 1)
        // The seed assumes every day is 86_400_000 ms; the loop does not. On the
        // day after a spring-forward the seed lands at 01:00 local, an hour
        // into the day, so the archiver re-reads (and re-archives) the first
        // hour of 2024-03-11. The drift is one hour, once, at the seed.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val lastArchived = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 10) })

        val seeded = lastArchived + 86400000L
        val viaCalendar = (Calendar.getInstance().apply { timeInMillis = lastArchived }).apply {
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

        assertEquals("the seed is an hour past the true next midnight", 3_600_000L, seeded - viaCalendar)
        assertNotEquals("the two forms of 'next day' disagree across a DST boundary", seeded, viaCalendar)
    }

    @Test
    fun `syncArchive's seed and loop agree when there is no transition`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Manila"))
        val lastArchived = startOfDay(Calendar.getInstance().apply { timeInMillis = local(2024, Calendar.MARCH, 10) })

        val seeded = lastArchived + 86400000L
        val viaCalendar = (Calendar.getInstance().apply { timeInMillis = lastArchived }).apply {
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

        assertEquals("Manila agrees, which is why this has never been noticed", seeded, viaCalendar)
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
