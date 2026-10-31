package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.UsageGap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gap-card explainer (2026-10-16). A gap card used to say only
 * "Unlabeled Gap", the one thing every card in a section titled MAJOR GAPS
 * already implies. The explainer names what the engine actually knows about
 * each gap: the nearest accounted thing on either side of it, an app name or
 * a label title. These tests pin the exact sentence grammar, because the
 * sentence IS the feature: it must say only what is known, and a null
 * neighbor must never be invented.
 */
class UsageGapExplainerTest {

    @Test
    fun `names both sides when both neighbors are known`() {
        val gap = UsageGap(0L, 100L, false, precededBy = "Chrome", followedBy = "Sleep")
        assertEquals("Nothing recorded between Chrome and Sleep", gap.contextExplainer)
    }

    @Test
    fun `names only the predecessor when the gap touches the end of the day`() {
        val gap = UsageGap(0L, 100L, false, precededBy = "Instagram")
        assertEquals("Nothing recorded after Instagram", gap.contextExplainer)
    }

    @Test
    fun `names only the successor when the gap touches the start of the day`() {
        val gap = UsageGap(0L, 100L, false, followedBy = "Work")
        assertEquals("Nothing recorded before Work", gap.contextExplainer)
    }

    @Test
    fun `says nothing was tracked when both sides are unknown`() {
        val gap = UsageGap(0L, 100L)
        assertEquals("Nothing recorded around this time", gap.contextExplainer)
    }

    @Test
    fun `defaults keep every existing three argument constructor and test compiling`() {
        // Every pre-explainer call site constructs UsageGap with 3 arguments.
        // Defaults must keep that source-compatible and must not silently
        // claim a neighbor that was never computed.
        val gap = UsageGap(0L, 100L, true)
        assertEquals(null, gap.precededBy)
        assertEquals(null, gap.followedBy)
        assertEquals(true, gap.hasHiddenAppFootprint)
        assertEquals("Nothing recorded around this time", gap.contextExplainer)
    }

    @Test
    fun `duration and range formats are unchanged by the explainer fields`() {
        val gap = UsageGap(0L, 3_600_000L, false, "A", "B")
        assertEquals(3_600_000L, gap.durationInMillis)
        assertEquals("1h", gap.durationFormatted)
        // Timezone-independent: the range renders "start - end" with a
        // separator, whatever the test machine's local offset does to epoch 0.
        assertEquals(true, gap.timeRangeFormatted.contains(" - "))
    }
}
