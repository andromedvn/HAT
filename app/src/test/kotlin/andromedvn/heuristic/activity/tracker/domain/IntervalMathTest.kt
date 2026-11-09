package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalMathTest {

    // --- mergeIntervals ----------------------------------------------------

    @Test
    fun `merge returns empty for empty input`() {
        assertEquals(emptyList<Pair<Long, Long>>(), mergeIntervals(emptyList()))
    }

    @Test
    fun `merge combines overlapping intervals`() {
        val input = listOf(0L to 100L, 50L to 150L)
        assertEquals(listOf(0L to 150L), mergeIntervals(input))
    }

    @Test
    fun `merge combines touching intervals`() {
        // current.second >= next.first, so [0,100] + [100,200] is one interval.
        val input = listOf(0L to 100L, 100L to 200L)
        assertEquals(listOf(0L to 200L), mergeIntervals(input))
    }

    @Test
    fun `merge keeps disjoint intervals separate and sorted`() {
        val input = listOf(300L to 400L, 0L to 100L)
        assertEquals(listOf(0L to 100L, 300L to 400L), mergeIntervals(input))
    }

    @Test
    fun `merge keeps the furthest end when nested`() {
        val input = listOf(0L to 500L, 100L to 200L)
        assertEquals(listOf(0L to 500L), mergeIntervals(input))
    }

    // --- subtractIntervals -------------------------------------------------

    @Test
    fun `subtract removes a fully contained interval`() {
        val source = listOf(0L to 1000L)
        val remove = listOf(100L to 200L)
        assertEquals(listOf(0L to 100L, 200L to 1000L), subtractIntervals(source, remove))
    }

    @Test
    fun `subtract trims the leading edge`() {
        val source = listOf(0L to 1000L)
        val remove = listOf(0L to 300L)
        assertEquals(listOf(300L to 1000L), subtractIntervals(source, remove))
    }

    @Test
    fun `subtract trims the trailing edge`() {
        val source = listOf(0L to 1000L)
        val remove = listOf(700L to 2000L)
        assertEquals(listOf(0L to 700L), subtractIntervals(source, remove))
    }

    @Test
    fun `subtract removes an exact match entirely`() {
        val source = listOf(0L to 100L)
        val remove = listOf(0L to 100L)
        assertEquals(emptyList<Pair<Long, Long>>(), subtractIntervals(source, remove))
    }

    @Test
    fun `subtract is a no-op for disjoint intervals`() {
        val source = listOf(0L to 100L)
        val remove = listOf(500L to 600L)
        assertEquals(listOf(0L to 100L), subtractIntervals(source, remove))
    }

    // --- totalDuration -----------------------------------------------------

    @Test
    fun `totalDuration sums without merging`() {
        // Documents that this helper does NOT merge; callers must merge first.
        val input = listOf(0L to 100L, 50L to 150L)
        assertEquals(200L, totalDuration(input))
    }

    @Test
    fun `totalDuration is zero for empty input`() {
        assertEquals(0L, totalDuration(emptyList()))
    }
}
