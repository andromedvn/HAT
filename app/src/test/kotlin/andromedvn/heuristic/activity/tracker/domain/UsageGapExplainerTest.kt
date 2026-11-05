package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.UsageGap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The UsageGap model contract that outlived the gap-card explainer (the
 * r12n dialog overhaul deleted the explainer property: the card shows
 * time and duration only, the neighbor lines live in the label dialog).
 * These tests pin the constructor defaults and the duration and range
 * formats: a null neighbor must never be invented, and the three-argument
 * constructor must stay source-compatible.
 */
class UsageGapExplainerTest {

    @Test
    fun `defaults keep every existing three argument constructor and test compiling`() {
        // Every call site constructs UsageGap with 3 arguments.
        // Defaults must keep that source-compatible and must not silently
        // claim a neighbor that was never computed.
        val gap = UsageGap(0L, 100L, true)
        assertEquals(null, gap.precededBy)
        assertEquals(null, gap.followedBy)
        assertEquals(true, gap.hasHiddenAppFootprint)
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
