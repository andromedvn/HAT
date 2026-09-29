package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rows that arrive from an imported vault.
 *
 * `OfflineStorage.mergeDatabase` reads rows out of a user-supplied SQLite file
 * and inserts them one at a time. Its only per-row check is a temporal-overlap
 * query: no ordering check, no plausibility check, no comparison against the
 * present. So a row written by a buggy version, hand-edited, or produced by a
 * future schema can carry
 *
 *  - a NEGATIVE duration;
 *  - a duration so large that `timestamp + duration` overflows Long and wraps
 *    to a negative end;
 *  - a timestamp/duration pair running far past now, claiming time that has not
 *    happened yet.
 *
 * `CorruptIntervalTest` covers what the pure algebra does with a reversed
 * interval. This class covers the read side, which is where such a row first
 * meets a total.
 *
 * The old read form was
 *
 *     entities.map { maxOf(start, it.timestamp) to minOf(end, it.timestamp + it.durationInMillis) }
 *         .filter { it.second > it.first }
 *
 * Two cases were already handled, and are stated here as findings rather than
 * fixes: a negative duration and a row starting after the window were both
 * dropped by the clamp (the first as a side effect, not deliberately). What was
 * NOT handled is that the end was bounded only by the day window, never by
 * `now` — so on the current day a corrupt row claimed offline time up to
 * 23:59:59.999 while app time stopped at the present — and that the
 * `timestamp + duration` add could wrap.
 */
class MalformedVaultRowTest {

    /** A finished day, so `now` never interferes with the window clamp. */
    private val dayStart = 1_700_000_000_000L
    private val dayEnd = dayStart + 86_399_999L
    private val now = dayEnd + 86_400_000L

    /**
     * TODAY, viewed at noon. Here the window end (23:59:59.999) is still in the
     * future, so `now` — not the window — is the binding ceiling. This is the
     * only situation in which a corrupt row can inflate a figure the user sees:
     * for a finished day the window end is already the tighter bound, so the old
     * clamp and the new one agree.
     */
    private val todayStart = 1_700_000_000_000L
    private val todayEnd = todayStart + 86_399_999L
    private val noon = todayStart + 43_200_000L

    private val day = 86_400_000L

    // --- negative and zero durations: already dropped, now explicit --------

    @Test
    fun `a negative duration is rejected`() {
        assertNull(sanitizeOfflineRow(dayStart + 3_600_000L, -1_000L, dayStart, dayEnd, now))
        assertNull(sanitizeOfflineRow(dayStart + 3_600_000L, -86_400_000L, dayStart, dayEnd, now))
    }

    @Test
    fun `a zero duration is rejected`() {
        assertNull(sanitizeOfflineRow(dayStart + 3_600_000L, 0L, dayStart, dayEnd, now))
    }

    @Test
    fun `a non-positive duration is rejected on the walk paths too`() {
        assertNull(sanitizeTrackedRow(dayStart + 3_600_000L, -1L, now))
        assertNull(sanitizeTrackedRow(dayStart + 3_600_000L, 0L, now))
    }

    @Test
    fun `the old clamp also dropped a negative duration, as a side effect`() {
        // Honest baseline: this case was never broken. The trailing
        // `it.second > it.first` filter caught it.
        val start = dayStart + 3_600_000L
        val naive = start to minOf(dayEnd, start + (-7_200_000L))
        assertTrue("the old clamp did drop it", naive.second <= naive.first)
        assertNull(sanitizeOfflineRow(start, -7_200_000L, start, dayEnd, now))
    }

    @Test
    fun `a negative duration cannot reduce a total`() {
        val good = listOfNotNull(
            sanitizeOfflineRow(dayStart + 3_600_000L, 3_600_000L, dayStart, dayEnd, now),
            sanitizeOfflineRow(dayStart + 9_000_000L, -7_200_000L, dayStart, dayEnd, now)
        )
        assertEquals(1, good.size)
        assertEquals(3_600_000L, totalDuration(good))
    }

    // --- an end time in the future: the real defect ------------------------

    @Test
    fun `a row running past the present is truncated at the present`() {
        val start = todayStart + 3_600_000L
        val row = sanitizeOfflineRow(start, day * 365L, todayStart, todayEnd, noon)
        assertEquals("clamped to now, not to a year from now", start to noon, row)
        assertEquals(noon - start, totalDuration(listOf(row!!)))
    }

    @Test
    fun `on a finished day the window end is already the tighter bound`() {
        // The honest counterpart: here the old clamp and the new one agree, and
        // the new one still caps at `now` — it is simply not the binding limit.
        val start = dayStart + 3_600_000L
        val row = sanitizeOfflineRow(start, day * 365L, dayStart, dayEnd, now)
        assertEquals("clamped to the end of the day being viewed", start to dayEnd, row)
    }

    @Test
    fun `the old clamp let a corrupt row claim the rest of the day`() {
        // Today at noon, a row imported with a one-year duration. The old clamp
        // bounded it by the day window only, so the dashboard showed offline
        // time running to 23:59:59.999 — twelve hours that had not happened.
        val startOfToday = 1_700_000_000_000L
        val endOfToday = startOfToday + 86_399_999L
        val noon = startOfToday + 43_200_000L
        val rowStart = startOfToday + 28_800_000L   // 08:00

        val oldForm = minOf(endOfToday, rowStart + (day * 365L))
        val newForm = sanitizeOfflineRow(rowStart, day * 365L, startOfToday, endOfToday, noon)!!.second

        assertEquals("the old form claimed until midnight", endOfToday, oldForm)
        assertEquals("the new form claims only until now", noon, newForm)
        assertEquals("12 h of not-yet-happened time removed", 43_199_999L, oldForm - newForm)
    }

    @Test
    fun `a row running past the present cannot inflate the day`() {
        val start = dayStart + 3_600_000L
        val app = mergeIntervals(listOf(dayStart to start))
        val corrupt = sanitizeOfflineRow(start, day * 365L, dayStart, dayEnd, now)!!
        val sharded = subtractIntervals(listOf(corrupt), app)

        assertTrue(
            "invariant 1: app + offline must fit the day, got ${totalDuration(app) + totalDuration(sharded)}",
            totalDuration(app) + totalDuration(sharded) <= day
        )
    }

    @Test
    fun `a row starting in the future is rejected outright`() {
        // The old clamp also dropped this one, by inverting the interval.
        assertNull(sanitizeOfflineRow(now + 1L, 3_600_000L, dayStart, dayEnd, now))
        assertNull(sanitizeOfflineRow(now, 3_600_000L, dayStart, dayEnd, now))
        assertNull(sanitizeTrackedRow(now, 3_600_000L, now))
    }

    @Test
    fun `a row ending exactly at the present is kept whole`() {
        val start = todayStart
        assertEquals(start to noon, sanitizeOfflineRow(start, noon - start, todayStart, todayEnd, noon))
    }

    // --- overflow ----------------------------------------------------------

    @Test
    fun `a duration near Long MAX does not wrap the end negative`() {
        val start = todayStart + 3_600_000L
        val row = sanitizeOfflineRow(start, Long.MAX_VALUE, todayStart, todayEnd, noon)

        assertNotEquals("the row survives instead of wrapping negative", null, row)
        assertEquals(start to noon, row)
        assertTrue("and the end is strictly after the start", row!!.second > row.first)
    }

    @Test
    fun `the guarded add is saturating on the walk paths as well`() {
        val start = dayStart + 3_600_000L
        assertEquals("capped at the present", start to now, sanitizeTrackedRow(start, Long.MAX_VALUE, now))
        assertEquals(
            "uncapped, the saturating add holds the end at Long.MAX_VALUE rather than wrapping",
            start to Long.MAX_VALUE,
            sanitizeTrackedRow(start, Long.MAX_VALUE, now, maxEnd = Long.MAX_VALUE)
        )
    }

    @Test
    fun `the naive add really does wrap, which is why the add is guarded`() {
        val start = dayStart + 3_600_000L
        assertTrue("timestamp + Long.MAX_VALUE overflows to negative", start + Long.MAX_VALUE < 0L)
    }

    // --- the gap walk: an honest null result --------------------------------

    @Test
    fun `capping the end does not change the gap list, because the walk already filtered it`() {
        // Characterisation, not a claim of a fix. A corrupt end used to send
        // `current` past the end of the day so no trailing gap was emitted —
        // but every such gap starts at or after `now`, and the final
        // `it.startMillis < now` filter dropped it regardless. The cap makes
        // the walk sound; it does not change what the user sees here.
        val noon = 43_200_000L
        val tracked = listOf(
            0L to 3_600_000L,
            3_600_000L to 7_200_000L,
            7_300_000L to (7_300_000L + Long.MAX_VALUE / 2L)
        )

        val uncapped = computeGaps(tracked, 0L, 86_399_999L, noon)
        val capped = computeGaps(
            tracked.mapNotNull { sanitizeTrackedRow(it.first, it.second - it.first, noon) },
            0L, 86_399_999L, noon
        )

        assertEquals(uncapped, capped)
        assertEquals("only the real 100 s gap is reported", listOf(7_200_000L to 7_300_000L), uncapped)
    }

    @Test
    fun `a row starting in the future cannot change the gap list either`() {
        // The second honest null. A row that starts after `now` emits a gap
        // from `current` to its start, but that gap is then trimmed to `now` by
        // `minOf(it.endMillis, now)` — so the visible result is already the same
        // as with no row at all. The guard is kept because it makes the
        // invariant explicit rather than relying on two filters in different
        // places cancelling out.
        val noon = 43_200_000L
        val withCorruptRow = computeGaps(
            listOf((noon + 86_400_000L) to (noon + 86_400_000L + day)),
            0L, 86_399_999L, noon
        )
        val withoutAnyRow = computeGaps(emptyList(), 0L, 86_399_999L, noon)
        val sanitised = computeGaps(
            listOfNotNull(sanitizeTrackedRow(noon + 86_400_000L, day, noon)),
            0L, 86_399_999L, noon
        )

        assertEquals(43_200_000L, totalDuration(withCorruptRow))
        assertEquals("the row changed nothing the user could see", withoutAnyRow, withCorruptRow)
        assertEquals(withoutAnyRow, sanitised)
    }

    @Test
    fun `well formed rows pass through the walk path untouched`() {
        val row = sanitizeTrackedRow(dayStart + 3_600_000L, 7_200_000L, now)
        assertEquals((dayStart + 3_600_000L) to (dayStart + 10_800_000L), row)
    }

    // --- the label cascade: where a corrupt row really did damage ----------

    @Test
    fun `a corrupt row cannot run the label cascade into the future`() {
        // `expandActivityCascade` grew `maxE` from `timestamp + duration` with
        // no bound, and the caller offers the result to the user as a block to
        // label — which then writes an offline row spanning every day it
        // touches. 3_000_000_000_000 ms is a 34-day window, not a real one.
        val rows = listOf(1_000L to 2_000L, 9_000L to 3_000_000_000_000L)

        val naive = grow(rows, start = 4_000L, end = 10_000L)
        val safe = cascadeBounds(rows, start = 4_000L, end = 10_000L, now = 1_000_000L)

        assertEquals(3_000_000_000_000L, naive.second)
        assertEquals("the cascade is bounded by the present", 1_000_000L, safe.second)
    }

    @Test
    fun `a genuinely long label is still honoured in full`() {
        // Capping at `now` only must not truncate a real fortnight of
        // "Sick leave", which is a legitimate single label.
        val cascade = cascadeBounds(
            rows = listOf(1_000L to 2_000L, 3_000L to 900_000L),
            start = 4_000L,
            end = 10_000L,
            now = 1_000_000L
        )
        assertEquals(1_000L, cascade.first)
        assertEquals(900_000L, cascade.second)
    }

    @Test
    fun `a corrupt row cannot masquerade as the activity preceding a void`() {
        // `expandVoid` selects the preceding activity on
        // `interval.second <= start + 1000`. A row truncated to `now` would
        // match that test when the user is looking at the present moment, so
        // that path deliberately leaves the end uncapped and rejects only rows
        // that cannot be real.
        val present = 1_000_000L
        val capped = sanitizeTrackedRow(500_000L, Long.MAX_VALUE / 2L, now = present)
        val uncapped = sanitizeTrackedRow(500_000L, Long.MAX_VALUE / 2L, now = present, maxEnd = Long.MAX_VALUE)

        assertEquals("truncated, so it would look like it ends at the present", present, capped!!.second)
        assertTrue("uncapped, so it cannot pass the preceding-activity test", uncapped!!.second > present + 1_000L)
    }

    // --- the window itself --------------------------------------------------

    @Test
    fun `an empty or inverted window yields nothing`() {
        assertNull(sanitizeOfflineRow(dayStart, 1_000L, dayStart, dayStart, now))
        assertNull(sanitizeOfflineRow(dayStart, 1_000L, dayEnd, dayStart, now))
    }

    @Test
    fun `a window entirely in the future yields nothing`() {
        assertNull(sanitizeOfflineRow(now + 10L, 1_000L, now + 5L, now + 100L, now))
    }

    @Test
    fun `a row partly before the window is clamped up to the window start`() {
        val row = sanitizeOfflineRow(dayStart - 7_200_000L, 10_800_000L, dayStart, dayEnd, now)
        assertEquals(dayStart to (dayStart + 3_600_000L), row)
        assertTrue("never before the window", row!!.first >= dayStart)
    }

    @Test
    fun `a row partly after the window is clamped down to the window end`() {
        val row = sanitizeOfflineRow(dayEnd - 1_000L, 7_200_000L, dayStart, dayEnd, now)
        assertEquals((dayEnd - 1_000L) to dayEnd, row)
        assertTrue("never after the window", row!!.second <= dayEnd)
    }

    @Test
    fun `a valid row is not altered by sanitising`() {
        val start = dayStart + 1_234L
        val duration = 4_567_890L
        assertEquals(start to (start + duration), sanitizeOfflineRow(start, duration, dayStart, dayEnd, now))
    }

    // --- helpers mirroring production ---------------------------------------

    /** The gap walk of `HeuristicEngine.scanForGaps`, as in GapScanTest. */
    private fun computeGaps(tracked: List<Interval>, start: Long, end: Long, now: Long): List<Interval> {
        val gaps = mutableListOf<Interval>()
        var current = start
        for (interval in mergeIntervals(tracked)) {
            if (interval.first > current) gaps.add(current to interval.first)
            current = maxOf(current, interval.second)
        }
        if (end > current) gaps.add(current to end)
        return gaps.mapNotNull { (a, b) -> if (a < now) a to minOf(b, now) else null }
    }

    /** `expandActivityCascade` with the sanitiser — the shipped shape. */
    private fun cascadeBounds(rows: List<Interval>, start: Long, end: Long, now: Long): Pair<Long, Long> =
        grow(rows.mapNotNull { sanitizeTrackedRow(it.first, it.second - it.first, now) }, start, end)

    /** The cascade loop itself, shared by both shapes above. */
    private fun grow(all: List<Interval>, start: Long, end: Long): Pair<Long, Long> {
        var minS = start
        var maxE = end
        var changed = true
        while (changed) {
            changed = false
            val prev = all.findLast { it.second >= minS - 1_000L && it.first < minS }
            if (prev != null) { minS = prev.first; changed = true }
            val next = all.find { it.first <= maxE + 1_000L && it.second > maxE }
            if (next != null) { maxE = next.second; changed = true }
        }
        return minS to maxE
    }
}
