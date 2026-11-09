package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Lean bridge (GENERATED FILE - do not edit by hand).
 *
 * Every expected value below was COMPUTED by the Lean 4 formalization of this
 * package's interval algebra (HAT-lean-proof/HatMath.lean, executed via
 * `lake exe bridge`). HatMath.lean machine-proves the honesty identities:
 * merge preserves covered time exactly, subtraction splits exactly
 * (pieces total = source - removed overlap), and window clamping never
 * invents or grows time.
 *
 * This test pins that the SHIPPED Kotlin and the proof artifact agree.
 * If it fails, one of them drifted: fix the Kotlin, or re-derive the proof.
 * Regenerate after changing HAT-lean-proof.
 *
 * Note on `clamp`: the raw intersect formula can return an inverted span
 * (start > end) when the input lies fully outside the window. HAT's engine
 * filters zero/negative-length spans downstream; this test pins the raw
 * formula both sides share.
 */
class LeanProofVectorTest {

    private fun clampRaw(winStart: Long, winEnd: Long, s: Long, e: Long): Pair<Long, Long> =
        maxOf(s, winStart) to minOf(e, winEnd)


    @Test
    fun `merge_touching`() {
        assertEquals(listOf((100L to 300L)), mergeIntervals(listOf((100L to 200L), (200L to 300L))))
    }

    @Test
    fun `merge_contained`() {
        assertEquals(listOf((100L to 300L)), mergeIntervals(listOf((100L to 300L), (150L to 250L))))
    }

    @Test
    fun `merge_extending`() {
        assertEquals(listOf((100L to 400L)), mergeIntervals(listOf((100L to 200L), (150L to 400L))))
    }

    @Test
    fun `merge_disjoint`() {
        assertEquals(listOf((100L to 200L), (300L to 400L)), mergeIntervals(listOf((100L to 200L), (300L to 400L))))
    }

    @Test
    fun `merge_unsorted_disjoint`() {
        assertEquals(listOf((100L to 200L), (300L to 400L)), mergeIntervals(listOf((300L to 400L), (100L to 200L))))
    }

    @Test
    fun `merge_unsorted_overlapping`() {
        assertEquals(listOf((100L to 400L)), mergeIntervals(listOf((150L to 400L), (100L to 250L))))
    }

    @Test
    fun `merge_identical`() {
        assertEquals(listOf((100L to 200L)), mergeIntervals(listOf((100L to 200L), (100L to 200L))))
    }

    @Test
    fun `merge_negative_times`() {
        assertEquals(listOf((-500L to 50L)), mergeIntervals(listOf((-500L to -100L), (-150L to 50L))))
    }

    @Test
    fun `merge_day_scale`() {
        assertEquals(listOf((0L to 7200000L)), mergeIntervals(listOf((0L to 3600000L), (3599999L to 7200000L))))
    }

    @Test
    fun `sub_split_both`() {
        assertEquals(listOf((0L to 200L), (300L to 1000L)), subtractIntervals(listOf((0L to 1000L)), listOf((200L to 300L))))
    }

    @Test
    fun `sub_cover_start`() {
        assertEquals(listOf((1500L to 2000L)), subtractIntervals(listOf((1000L to 2000L)), listOf((500L to 1500L))))
    }

    @Test
    fun `sub_cover_end`() {
        assertEquals(listOf((0L to 600L)), subtractIntervals(listOf((0L to 1000L)), listOf((600L to 1600L))))
    }

    @Test
    fun `sub_inside`() {
        assertEquals(listOf((0L to 400L), (600L to 1000L)), subtractIntervals(listOf((0L to 1000L)), listOf((400L to 600L))))
    }

    @Test
    fun `sub_cover_all`() {
        assertEquals(emptyList<Pair<Long, Long>>(), subtractIntervals(listOf((0L to 1000L)), listOf((-100L to 2000L))))
    }

    @Test
    fun `sub_untouched`() {
        assertEquals(listOf((0L to 1000L)), subtractIntervals(listOf((0L to 1000L)), listOf((2000L to 3000L))))
    }

    @Test
    fun `sub_touching_start`() {
        assertEquals(listOf((250L to 1000L)), subtractIntervals(listOf((0L to 1000L)), listOf((0L to 250L))))
    }

    @Test
    fun `sub_touching_end`() {
        assertEquals(listOf((0L to 750L)), subtractIntervals(listOf((0L to 1000L)), listOf((750L to 1000L))))
    }

    @Test
    fun `sub_single_millisecond`() {
        assertEquals(listOf((0L to 499L), (500L to 1000L)), subtractIntervals(listOf((0L to 1000L)), listOf((499L to 500L))))
    }

}
