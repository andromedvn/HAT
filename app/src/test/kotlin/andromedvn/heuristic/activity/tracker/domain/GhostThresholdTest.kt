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
 * An audit claimed: "`ghostMins <= 0` yields `Long.MAX_VALUE`, so a user who
 * sets the minimum to 0 gets an empty list — zero should mean 'no minimum',
 * return everything." That reading is wrong, and the settings UI is the
 * evidence:
 *
 *     HatSlidingSelector<Int>("Idle App Detection",
 *         "Flag an app as a 'Suspicious Idle Session' if it stays on screen
 *          without interaction.",
 *         listOf("Off" to 0, "30m" to 30, "1h" to 60, "Custom" to -1), ...)
 *
 * (SettingsScreen.kt:296.) 0 is the option the user picks to switch idle
 * detection OFF, and the custom-value dialog accepts 0, which the selector then
 * renders as "Off". The same convention governs all four heuristic settings:
 * archive sync, gap minimum and merge tolerance are each `listOf("Off" to 0,
 * ...)`.
 *
 * So the empty list at 0 is the FEATURE WORKING, not a defect. Reinterpreting 0
 * as "no minimum" would switch the feature back on for every user who turned it
 * off, and would surface every sub-second idle blip as a "Suspicious Idle
 * Session". This class therefore pins the existing behaviour, and pins the
 * reasoning, so the sentinel is not "fixed" later by someone who has not read
 * the selector.
 *
 * The one genuine fragility is worth stating: "off" is encoded as
 * `Long.MAX_VALUE` rather than as an explicit branch, so it is correct only
 * while no duration can reach `Long.MAX_VALUE`. A real "no minimum" option
 * would need a distinct stored value (e.g. -1) and a new selector entry.
 */
class GhostThresholdTest {

    private val pkg = "com.a"

    private fun ghost(start: Long, duration: Long, packageName: String = pkg) =
        Triple(packageName, start, start + duration)

    private fun select(
        ghosts: List<Triple<String, Long, Long>>,
        ghostMins: Int,
        acks: List<AcknowledgedGhost> = emptyList()
    ): List<UsageGap> =
        selectGhostGapsForPackage(ghosts, pkg, ghostThresholdMillis(ghostMins), acks)

    // --- 0 means Off --------------------------------------------------------

    @Test
    fun `a setting of zero encodes the feature being off`() {
        assertEquals(Long.MAX_VALUE, ghostThresholdMillis(0))
    }

    @Test
    fun `the off threshold is unreachable by any finite span`() {
        val threshold = ghostThresholdMillis(0)
        assertTrue(
            "this is what makes the empty list correct rather than accidental",
            listOf(0L, 1L, 3_600_000L, 86_400_000L, Long.MAX_VALUE - 1L).none { it >= threshold }
        )
    }

    @Test
    fun `with the feature off the user is shown no idle sessions at all`() {
        val ghosts = listOf(
            ghost(1_000L, 1L),          // 1 ms
            ghost(10_000L, 59_000L),
            ghost(100_000L, 3_600_000L)
        )
        assertEquals(
            "Off must show nothing, however long the idle stretch was",
            emptyList<UsageGap>(),
            select(ghosts, 0)
        )
    }

    @Test
    fun `a negative value is also off`() {
        // The selector's "Custom" sentinel is -1, and a restored vault could
        // carry anything. Nothing non-positive is a usable duration.
        assertEquals(Long.MAX_VALUE, ghostThresholdMillis(-1))
        assertEquals(Long.MAX_VALUE, ghostThresholdMillis(Int.MIN_VALUE))
    }

    @Test
    fun `the smallest usable setting is one minute`() {
        assertEquals(60_000L, ghostThresholdMillis(1))
        assertNotEquals("one minute is on, zero is off", ghostThresholdMillis(0), ghostThresholdMillis(1))
    }

    // --- a positive threshold filters --------------------------------------

    @Test
    fun `a small positive threshold drops the shorter ghosts and keeps the longer`() {
        val ghosts = listOf(
            ghost(1_000L, 59_000L),        // 59 s  — below a 1-minute minimum
            ghost(100_000L, 60_000L),      // 60 s  — exactly the minimum
            ghost(200_000L, 3_600_000L)    // 60 min — well above it
        )
        val selected = select(ghosts, 1)

        assertEquals(2, selected.size)
        assertEquals(listOf(100_000L, 200_000L), selected.map { it.startMillis })
    }

    @Test
    fun `a duration exactly equal to the threshold is kept`() {
        // The comparison is `>=`, so the boundary value is offered.
        assertEquals(1, select(listOf(ghost(1_000L, 3_600_000L)), 60).size)
    }

    @Test
    fun `a duration one millisecond under the threshold is dropped`() {
        assertEquals(emptyList<UsageGap>(), select(listOf(ghost(1_000L, 3_599_999L)), 60))
    }

    @Test
    fun `the default setting of one hour shows only hour-long idle stretches`() {
        // `UserSettings.ghostTimeTriggerMins` defaults to 60.
        val ghosts = listOf(ghost(1_000L, 3_599_999L), ghost(2_000L, 3_600_000L))
        assertEquals(listOf(2_000L), select(ghosts, 60).map { it.startMillis })
    }

    @Test
    fun `a threshold in hours drops everything below it`() {
        val ghosts = listOf(
            ghost(1_000L, 3_600_000L),      // 1 h
            ghost(10_000L, 7_199_999L),     // just under 2 h
            ghost(20_000L, 7_200_000L)      // exactly 2 h
        )
        assertEquals(listOf(20_000L), select(ghosts, 120).map { it.startMillis })
    }

    // --- a huge threshold returns nothing ----------------------------------

    @Test
    fun `a huge threshold returns nothing`() {
        val ghosts = listOf(ghost(1_000L, 1L), ghost(10_000L, 86_400_000L))
        assertEquals(
            "even a full day is shorter than a two-day minimum",
            emptyList<UsageGap>(),
            select(ghosts, 2_880)
        )
    }

    @Test
    fun `the largest possible threshold returns nothing for any span`() {
        val ghosts = (0..100).map { ghost(it * 1_000_000L, 999_999_999L) }
        assertEquals(emptyList<UsageGap>(), select(ghosts, Int.MAX_VALUE))
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

    @Test
    fun `a positive threshold is exact in milliseconds`() {
        for (mins in 1..1440 step 7) {
            assertEquals(mins.toLong() * 60_000L, ghostThresholdMillis(mins))
        }
        assertEquals(86_400_000L, ghostThresholdMillis(1440))
    }

    // --- monotonicity -------------------------------------------------------

    @Test
    fun `raising the minimum can only ever remove candidates`() {
        // From 1 minute upwards. 0 is not on this scale at all — it is the off
        // sentinel, so the count jumps from 0 candidates to all of them there.
        val ghosts = (1..20).map { ghost(it * 1_000_000L, it * 60_000L) }
        var previous = Int.MAX_VALUE
        for (mins in 1..25) {
            val count = select(ghosts, mins).size
            assertTrue("threshold $mins grew the list from $previous to $count", count <= previous)
            previous = count
        }
        // The largest of the 20 is 20 minutes, so a 1-minute minimum keeps all
        // and a 25-minute minimum empties the list.
        assertEquals(20, select(ghosts, 1).size)
        assertEquals(0, select(ghosts, 25).size)
    }

    @Test
    fun `off is the only threshold that behaves discontinuously`() {
        val ghosts = (1..20).map { ghost(it * 1_000_000L, it * 60_000L) }
        assertEquals("off shows nothing", 0, select(ghosts, 0).size)
        assertEquals("one minute shows all 20", 20, select(ghosts, 1).size)
    }

    // --- acknowledgement and package scoping -------------------------------

    @Test
    fun `an acknowledged ghost is still suppressed`() {
        val ghosts = listOf(ghost(1_000L, 3_600_000L), ghost(10_000L, 3_600_000L))
        val acks = listOf(AcknowledgedGhost(pkg, 1_000L, 3_601_000L))
        assertEquals(listOf(10_000L), select(ghosts, 60, acks).map { it.startMillis })
    }

    @Test
    fun `an acknowledgement for another package does not suppress a ghost`() {
        val ghosts = listOf(ghost(1_000L, 3_600_000L))
        val acks = listOf(AcknowledgedGhost("com.other", 1_000L, 3_601_000L))
        assertEquals(1, select(ghosts, 60, acks).size)
    }

    @Test
    fun `ghosts for other packages are never offered`() {
        val ghosts = listOf(ghost(1_000L, 3_600_000L, "com.a"), ghost(2_000L, 3_600_000L, "com.b"))
        assertEquals(
            1,
            selectGhostGapsForPackage(ghosts, "com.a", ghostThresholdMillis(60), emptyList()).size
        )
    }

    @Test
    fun `a selected gap carries the exact replayed span`() {
        // The ack key is an exact three-field match, so the span shown to the
        // user must be the span the replay produced, unaltered.
        val start = 1_234_567L
        val end = 9_876_543L
        val gap = selectGhostGapsForPackage(
            listOf(ghost(start, end - start)), pkg, ghostThresholdMillis(1), emptyList()
        ).single()

        assertEquals(start, gap.startMillis)
        assertEquals(end, gap.endMillis)
        assertEquals(end - start, gap.durationInMillis)
        assertEquals(false, gap.hasHiddenAppFootprint)
    }
}
