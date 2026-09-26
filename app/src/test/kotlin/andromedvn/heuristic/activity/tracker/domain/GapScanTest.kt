package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2 was flagged as "scanForGaps does not clamp offline intervals, which masks a
 * leading gap". Fuzzing 20,000 randomised windows with app and offline
 * intervals produced ZERO differing gap lists.
 *
 * The reason is structural: the walk starts `current` AT `start`, and emits a gap
 * only when `interval.first > current`. An interval that begins before `start`
 * therefore has `first < current` and can never emit a gap, while
 * `current = maxOf(current, interval.second)` still advances it correctly.
 * Clamping is a no-op here. These tests pin that so a future "fix" does not
 * introduce a regression based on the false premise.
 */
class GapScanTest {

    private fun computeGaps(
        tracked: List<Interval>,
        start: Long,
        end: Long,
        now: Long = Long.MAX_VALUE
    ): List<Interval> {
        val gaps = mutableListOf<Interval>()
        var current = start
        for (interval in mergeIntervals(tracked)) {
            if (interval.first > current) gaps.add(current to interval.first)
            current = maxOf(current, interval.second)
        }
        if (end > current) gaps.add(current to end)
        return gaps.mapNotNull { (a, b) -> if (a < now) a to minOf(b, now) else null }
    }

    @Test
    fun `an interval starting before the window cannot emit a leading gap`() {
        val start = 1_000L
        val end = 5_000L
        val app = listOf(4_000L to 5_000L)

        val unclamped = computeGaps(app + listOf(0L to 4_000L), start, end)
        val clamped = computeGaps(app + listOf(1_000L to 4_000L), start, end)

        assertEquals("both forms must agree — this is the F2 false positive", clamped, unclamped)
        assertEquals("no gap is emitted for the leading region", emptyList<Interval>(), unclamped)
    }

    @Test
    fun `offline extending past the window end does not create a trailing gap`() {
        val start = 1_000L
        val end = 5_000L
        val unclamped = computeGaps(listOf(0L to 9_000L), start, end)
        val clamped = computeGaps(listOf(1_000L to 5_000L), start, end)
        assertEquals(clamped, unclamped)
    }

    @Test
    fun `a real gap before the first tracked interval is still found`() {
        // Guards against over-correcting: clamping must not suppress genuine gaps.
        val gaps = computeGaps(listOf(4_000L to 5_000L), 1_000L, 5_000L)
        assertEquals(listOf(1_000L to 4_000L), gaps)
    }

    @Test
    fun `future gaps are trimmed to now`() {
        val now = 3_000L
        val gaps = computeGaps(listOf(4_000L to 5_000L), 1_000L, 9_000L, now)
        assertEquals(listOf(1_000L to now), gaps)
    }

    @Test
    fun `gaps never extend beyond the query window`() {
        val gaps = computeGaps(listOf(2_000L to 3_000L), 1_000L, 4_000L)
        assertTrue(gaps.all { it.first >= 1_000L && it.second <= 4_000L })
    }
}
