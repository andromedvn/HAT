package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pins the exact overlap predicate that the vault-restore merge used to ask
 * SQLite once per imported row:
 *
 *     timestamp < end AND timestamp + duration > start
 *
 * An off-by-one here silently corrupts restored history, so the edge cases are
 * asserted directly rather than inferred from a passing merge.
 */
class IntervalOverlapIndexTest {

    // --- edge cases that must not drift ------------------------------------

    @Test
    fun `empty index never reports an overlap`() {
        val index = IntervalOverlapIndex.build(emptyList())
        assertFalse(index.overlapsAny(0L, 100L))
        assertEquals(0, index.size)
    }

    @Test
    fun `touching intervals do not overlap`() {
        // The load-bearing one: `existingEnd > start` is 100 > 100, which is
        // false. Half-open intervals, [0,100) and [100,200) are disjoint, and
        // a restore must be able to place both.
        val index = IntervalOverlapIndex.build(listOf(0L to 100L))
        assertFalse(index.overlapsAny(100L, 200L))
    }

    @Test
    fun `an interval that starts exactly where another ends does not overlap`() {
        val index = IntervalOverlapIndex.build(listOf(100L to 200L))
        assertFalse(index.overlapsAny(0L, 100L))
    }

    @Test
    fun `exact duplicates overlap`() {
        val index = IntervalOverlapIndex.build(listOf(100L to 200L))
        assertTrue(index.overlapsAny(100L, 200L))
    }

    @Test
    fun `an existing interval fully containing the query overlaps`() {
        val index = IntervalOverlapIndex.build(listOf(0L to 1000L))
        assertTrue(index.overlapsAny(100L, 200L))
    }

    @Test
    fun `a query fully containing an existing interval overlaps`() {
        val index = IntervalOverlapIndex.build(listOf(100L to 200L))
        assertTrue(index.overlapsAny(0L, 1000L))
    }

    @Test
    fun `a one-millisecond overlap at the leading edge counts`() {
        val index = IntervalOverlapIndex.build(listOf(100L to 200L))
        assertTrue(index.overlapsAny(99L, 101L))
    }

    @Test
    fun `a one-millisecond overlap at the trailing edge counts`() {
        val index = IntervalOverlapIndex.build(listOf(100L to 200L))
        assertTrue(index.overlapsAny(199L, 300L))
    }

    @Test
    fun `disjoint intervals far apart do not overlap`() {
        val index = IntervalOverlapIndex.build(listOf(0L to 100L, 5000L to 5100L))
        assertFalse(index.overlapsAny(200L, 300L))
    }

    @Test
    fun `a zero-length query is a point test`() {
        // [50,50) inside [0,100): 0 < 50 and 100 > 50. Durations are not
        // guaranteed non-zero, so this is reachable from a real vault.
        assertTrue(IntervalOverlapIndex.build(listOf(0L to 100L)).overlapsAny(50L, 50L))
        // [100,100) merely touches the end of [0,100), 100 > 100 is false.
        assertFalse(IntervalOverlapIndex.build(listOf(0L to 100L)).overlapsAny(100L, 100L))
        // [0,0) before everything.
        assertFalse(IntervalOverlapIndex.build(listOf(100L to 200L)).overlapsAny(0L, 0L))
    }

    /**
     * Guards the prefix-maximum lookup specifically. Checking only the nearest
     * neighbour by start would wrongly report "no overlap" here, admitting a
     * row the SQL rejects.
     */
    @Test
    fun `a long interval earlier in the list is found past a short nearer one`() {
        val index = IntervalOverlapIndex.build(listOf(0L to 1000L, 500L to 600L))
        assertTrue(index.overlapsAny(700L, 800L))
    }

    @Test
    fun `unsorted input is handled`() {
        val index = IntervalOverlapIndex.build(listOf(500L to 600L, 0L to 1000L, 300L to 350L))
        assertTrue(index.overlapsAny(700L, 800L))
        assertTrue(index.overlapsAny(50L, 60L))
        // [0,1000) spans all three rows, so anything inside it overlaps.
        assertTrue(index.overlapsAny(200L, 250L))
        assertFalse(index.overlapsAny(1100L, 1200L))
    }

    // --- incremental adds, as the merge uses it ---------------------------

    @Test
    fun `an added interval is visible to later checks`() {
        // The merge inserts as it goes, and the SQL saw the table grow. Skipping
        // the add would let two overlapping imported rows both land.
        val index = IntervalOverlapIndex.build(emptyList())
        assertFalse(index.overlapsAny(0L, 100L))
        index.add(0L, 100L)
        assertTrue(index.overlapsAny(50L, 150L))
    }

    @Test
    fun `adds spanning several folds stay correct`() {
        val index = IntervalOverlapIndex.build(emptyList())
        for (i in 0 until 500L) index.add(i * 1000L, i * 1000L + 10L)
        assertEquals(500, index.size)
        assertTrue(index.overlapsAny(123_000L, 123_005L))
        // Rows sit at [i*1000, i*1000+10); 123_015 falls in the gap after 123_010.
        assertFalse(index.overlapsAny(123_015L, 123_990L))
        assertTrue(index.overlapsAny(0L, 5L))
    }

    @Test
    fun `addAll matches repeated adds`() {
        val bulk = IntervalOverlapIndex.build(emptyList())
        bulk.addAll(listOf(0L to 100L, 100L to 200L, 500L to 600L))
        val incremental = IntervalOverlapIndex.build(emptyList())
        for (iv in listOf(0L to 100L, 100L to 200L, 500L to 600L)) incremental.add(iv.first, iv.second)
        for (q in listOf(0L to 100L, 50L to 60L, 99L to 101L, 100L to 200L, 300L to 400L, 550L to 560L, 600L to 700L)) {
            assertEquals(incremental.overlapsAny(q.first, q.second), bulk.overlapsAny(q.first, q.second))
        }
    }

    // --- differential test against the literal SQL predicate ---------------

    @Test
    fun `matches a brute force evaluation of the SQL predicate on random data`() {
        // The oracle below IS the original query, evaluated in Kotlin:
        // exists a row where `rowStart < end && rowEnd > start`.
        val rng = Random(20240917)
        repeat(40) { trial ->
            val rows = (0 until 60).map {
                val s = rng.nextLong(0L, 20_000L)
                s to (s + rng.nextLong(0L, 3_000L))
            }
            val index = IntervalOverlapIndex.build(rows)
            repeat(60) {
                val qs = rng.nextLong(0L, 20_000L)
                val qe = qs + rng.nextLong(0L, 3_000L)
                val expected = rows.any { it.first < qe && it.second > qs }
                assertEquals(
                    "trial=$trial query=[$qs,$qe)",
                    expected,
                    index.overlapsAny(qs, qe)
                )
            }
        }
    }

    @Test
    fun `matches the SQL predicate with interleaved adds`() {
        val rng = Random(555)
        val rows = (0 until 40).map {
            val s = rng.nextLong(0L, 10_000L)
            s to (s + rng.nextLong(0L, 2_000L))
        }
        val index = IntervalOverlapIndex.build(rows)
        val inserted = rows.toMutableList()
        repeat(300) {
            val qs = rng.nextLong(0L, 10_000L)
            val qe = qs + rng.nextLong(0L, 2_000L)
            val expected = inserted.any { it.first < qe && it.second > qs }
            assertEquals(expected, index.overlapsAny(qs, qe))
            // Mirror the merge: only rows that did NOT overlap get inserted.
            if (!expected) {
                index.add(qs, qe)
                inserted.add(qs to qe)
            }
        }
    }
}
