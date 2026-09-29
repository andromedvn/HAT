package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.AcknowledgedGhost
import andromedvn.heuristic.activity.tracker.data.UsageGap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ghost minimum-duration setting.
 *
 * `getAppUsage` and `getAppDetail` both began with
 *
 *     val ghostMillis = if (ghostMins <= 0) Long.MAX_VALUE else ghostMins * 60L * 1000L
 *     ...
 *     .filter { (it.third - it.second) >= ghostMillis }
 *
 * `Long.MAX_VALUE` is the ONE value no finite duration can satisfy: the
 * comparison `duration >= Long.MAX_VALUE` is false for every real ghost span,
 * for every app, for every day. So a user who set the minimum to 0 — which
 * reads as "no minimum, show me everything" — was shown an EMPTY ghost list.
 * The setting that should surface the most candidates surfaced the fewest, and
 * the empty list was indistinguishable from "no idle time detected".
 *
 * 0 now means 0L, the lowest possible threshold. A genuinely large threshold
 * is still honoured exactly, because the minute-to-millis conversion stays in
 * Long and no plausible span reaches it.
 */
class GhostThresholdTest {

    private val pkg = "com.a"

    private fun ghost(start: Long, duration: Long, packageName: String = pkg) =
        Triple(packageName, start, start + duration)

    // --- the threshold itself ----------------------------------------------

    @Test
    fun `a threshold of zero means no minimum`() {
        assertEquals(0L, ghostThresholdMillis(0))
    }

    @Test
    fun `the old expression for zero produced a threshold nothing can satisfy`() {
        // The regression, stated as arithmetic rather than as a symptom.
        val oldForm = if (0 <= 0) Long.MAX_VALUE else 0L
        assertEquals(Long.MAX_VALUE, oldForm)
        assertTrue(
            "no finite ghost span can reach the old zero threshold",
            listOf(0L, 1L, 3_600_000L, 86_400_000L, Long.MAX_VALUE - 1L).none { it >= oldForm }
        )
    }

    @Test
    fun `a positive threshold is exact in milliseconds`() {
        assertEquals(3_600_000L, ghostThresholdMillis(60))
        assertEquals(60_000L, ghostThresholdMillis(1))
        assertEquals(86_400_000L, ghostThresholdMillis(1440))
        for (mins in 0..1440 step 7) {
            assertEquals(mins.toLong() * 60_000L, ghostThresholdMillis(mins))
        }
    }

    @Test
    fun `a negative setting is treated as no minimum rather than as impossible`() {
        // The settings UI cannot produce a negative, but a restored vault or a
        // future slider could, and the old form turned any value <= 0 into
        // "match nothing".
        assertEquals(0L, ghostThresholdMillis(-1))
        assertEquals(0L, ghostThresholdMillis(Int.MIN_VALUE))
    }

    @Test
    fun `an absurd threshold is honoured and does not overflow`() {
        // `Int * Int` would overflow at ~35k minutes. The conversion is Long
        // throughout, so the largest possible setting is a real number.
        val absurd = ghostThresholdMillis(Int.MAX_VALUE)
        assertEquals(128_849_018_820_000L, absurd)
        assertTrue(absurd > 0L)
        assertNotEquals("and it is not a wrapped negative", -1L, absurd)
        assertTrue("well inside Long", absurd < Long.MAX_VALUE)
    }

    // --- 0 returns everything ----------------------------------------------

    @Test
    fun `a threshold of zero returns every ghost including the shortest`() {
        val ghosts = listOf(
            ghost(1_000L, 1L),          // 1 ms
            ghost(10_000L, 59_000L),
            ghost(100_000L, 3_600_000L)
        )
        val selected = selectGhostGapsForPackage(ghosts, pkg, ghostThresholdMillis(0), emptyList())

        assertEquals(3, selected.size)
        assertEquals(listOf(1_000L to 1_001L, 10_000L to 69_000L, 100_000L to 3_700_000L), selected.map { it.startMillis to it.endMillis })
    }

    @Test
    fun `a threshold of zero returns ghosts for every package, not just the first`() {
        val ghosts = listOf(
            ghost(1_000L, 500L, "com.a"),
            ghost(2_000L, 500L, "com.b"),
            ghost(3_000L, 500L, "com.c")
        )
        assertEquals(1, selectGhostGapsForPackage(ghosts, "com.a", ghostThresholdMillis(0), emptyList()).size)
        assertEquals(1, selectGhostGapsForPackage(ghosts, "com.b", ghostThresholdMillis(0), emptyList()).size)
        assertEquals(1, selectGhostGapsForPackage(ghosts, "com.c", ghostThresholdMillis(0), emptyList()).size)
    }

    // --- a small positive threshold filters -------------------------------

    @Test
    fun `a small positive threshold drops the shorter ghosts and keeps the longer`() {
        val ghosts = listOf(
            ghost(1_000L, 59_000L),        // 59 s  — below a 1-minute minimum
            ghost(100_000L, 60_000L),      // 60 s  — exactly the minimum
            ghost(200_000L, 3_600_000L)    // 60 min — well above it
        )
        val selected = selectGhostGapsForPackage(ghosts, pkg, ghostThresholdMillis(1), emptyList())

        assertEquals(2, selected.size)
        assertEquals(listOf(100_000L, 200_000L), selected.map { it.startMillis })
    }

    @Test
    fun `a threshold in hours drops everything below it`() {
        val ghosts = listOf(
            ghost(1_000L, 3_600_000L),      // 1 h
            ghost(10_000L, 7_199_999L),     // just under 2 h
            ghost(20_000L, 7_200_000L)      // exactly 2 h
        )
        val selected = selectGhostGapsForPackage(ghosts, pkg, ghostThresholdMillis(120), emptyList())
        assertEquals(listOf(20_000L), selected.map { it.startMillis })
    }

    @Test
    fun `a duration exactly equal to the threshold is kept`() {
        // The comparison is `>=`, so the boundary value is offered. A ghost of
        // exactly the configured minimum is not "shorter than the minimum".
        val ghosts = listOf(ghost(1_000L, 3_600_000L))
        assertEquals(1, selectGhostGapsForPackage(ghosts, pkg, ghostThresholdMillis(60), emptyList()).size)
    }

    @Test
    fun `a duration one millisecond under the threshold is dropped`() {
        val ghosts = listOf(ghost(1_000L, 3_599_999L))
        assertEquals(emptyList<UsageGap>(), selectGhostGapsForPackage(ghosts, pkg, ghostThresholdMillis(60), emptyList()))
    }

    // --- a huge threshold returns nothing ----------------------------------

    @Test
    fun `a huge threshold returns nothing`() {
        val ghosts = listOf(ghost(1_000L, 1L), ghost(10_000L, 86_400_000L))
        assertEquals(
            "even a full day is shorter than a two-day minimum",
            emptyList<UsageGap>(),
            selectGhostGapsForPackage(ghosts, pkg, ghostThresholdMillis(2_880), emptyList())
        )
    }

    @Test
    fun `the largest possible threshold returns nothing for any span`() {
        val ghosts = (0..100).map { ghost(it * 1_000_000L, 999_999_999L) }
        assertEquals(
            emptyList<UsageGap>(),
            selectGhostGapsForPackage(ghosts, pkg, ghostThresholdMillis(Int.MAX_VALUE), emptyList())
        )
    }

    @Test
    fun `the selection is monotonic in the threshold`() {
        // Raising the minimum can only ever remove candidates, never add one.
        val ghosts = (1..20).map { ghost(it * 1_000_000L, it * 60_000L) }
        var previous = Int.MAX_VALUE
        for (mins in 0..25) {
            val count = selectGhostGapsForPackage(ghosts, pkg, ghostThresholdMillis(mins), emptyList()).size
            assertTrue("threshold $mins grew the list from $previous to $count", count <= previous)
            previous = count
        }
        assertEquals("0 shows all 20", 20, selectGhostGapsForPackage(ghosts, pkg, ghostThresholdMillis(0), emptyList()).size)
    }

    // --- acknowledgement still applies at a zero threshold -----------------

    @Test
    fun `a zero threshold still honours an acknowledged ghost`() {
        // Fixing "0 shows nothing" must not turn into "0 shows everything,
        // including what the user dismissed".
        val ghosts = listOf(ghost(1_000L, 500L), ghost(10_000L, 500L))
        val acks = listOf(AcknowledgedGhost(pkg, 1_000L, 1_500L))

        val selected = selectGhostGapsForPackage(ghosts, pkg, ghostThresholdMillis(0), acks)

        assertEquals(1, selected.size)
        assertEquals(10_000L, selected.single().startMillis)
    }

    @Test
    fun `an acknowledgement for another package does not suppress a ghost`() {
        val ghosts = listOf(ghost(1_000L, 500L))
        val acks = listOf(AcknowledgedGhost("com.other", 1_000L, 1_500L))
        assertEquals(1, selectGhostGapsForPackage(ghosts, pkg, ghostThresholdMillis(0), acks).size)
    }

    @Test
    fun `a selected gap carries the exact replayed span`() {
        // The ack key is an exact three-field match, so the span reported to the
        // user must be the span the replay produced, unaltered.
        val start = 1_234_567L
        val end = 9_876_543L
        val selected = selectGhostGapsForPackage(listOf(ghost(start, end - start)), pkg, ghostThresholdMillis(0), emptyList())
        val gap = selected.single()

        assertEquals(start, gap.startMillis)
        assertEquals(end, gap.endMillis)
        assertEquals(end - start, gap.durationInMillis)
        assertEquals(false, gap.hasHiddenAppFootprint)
    }
}
