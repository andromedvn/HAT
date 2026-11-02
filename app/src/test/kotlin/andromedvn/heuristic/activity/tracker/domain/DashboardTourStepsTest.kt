package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tour curriculum is copy the owner ratified (2026-11-01: "make the user
 * familiar with every screen"). Eleven steps across four real screens; the
 * charter bans em dashes and hype words in every user-facing string, so the
 * test enforces that here, where the strings live, instead of trusting review.
 */
class DashboardTourStepsTest {

    @Test
    fun `tour covers four screens in the ratified order`() {
        val screens = DashboardTourSteps.steps.map { it.screen }.distinct()
        assertEquals(
            listOf(
                DashboardTourSteps.SCREEN_DASHBOARD,
                DashboardTourSteps.SCREEN_GAPS,
                DashboardTourSteps.SCREEN_STATS,
                DashboardTourSteps.SCREEN_SETTINGS
            ),
            screens
        )
    }

    @Test
    fun `every step has real copy that respects the charter`() {
        val banned = listOf("seamlessly", "effortlessly", "unlock", "journey")
        DashboardTourSteps.steps.forEach { step ->
            assertTrue("step ${step.id} has blank copy", step.copy.isNotBlank())
            assertFalse("step ${step.id} contains an em or en dash", step.copy.contains('—') || step.copy.contains('–'))
            banned.forEach { word ->
                assertFalse("step ${step.id} uses banned word $word", step.copy.lowercase().contains(word))
            }
        }
    }

    @Test
    fun `exactly one step completes the tour and it is the last`() {
        val completing = DashboardTourSteps.steps.filter { it.completesTour }
        assertEquals(1, completing.size)
        assertEquals(DashboardTourSteps.steps.last().id, completing.first().id)
        assertEquals(DashboardTourSteps.SCREEN_SETTINGS, completing.first().screen)
    }

    @Test
    fun `every screen's steps share that screen's route`() {
        DashboardTourSteps.steps.forEach { step ->
            assertEquals("step ${step.id} route mismatch", step.screen, step.route)
        }
    }

    @Test
    fun `every screen change is taught through a pulsing real button`() {
        // The owner's rule (2026-11-02): the tour must not teleport between
        // screens. Whenever the tour moves to a screen the user was not on,
        // one of the two steps must carry a transitionKey: the tour pulses
        // the real button and the tap performs that button's navigation.
        val targets = DashboardTourSteps.steps.mapNotNull { it.targetKey }.toSet()
        for (i in 1 until DashboardTourSteps.steps.size) {
            val prev = DashboardTourSteps.steps[i - 1]
            val curr = DashboardTourSteps.steps[i]
            if (curr.screen != prev.screen) {
                val taught = prev.transitionKey != null || curr.transitionKey != null
                assertTrue(
                    "screen change ${prev.id} -> ${curr.id} teleports (no transitionKey)",
                    taught
                )
            }
        }
    }

    @Test
    fun `transition keys pulse buttons that are actually registered targets`() {
        val targets = DashboardTourSteps.steps.mapNotNull { it.targetKey }.toSet()
        DashboardTourSteps.steps.filter { it.transitionKey != null }.forEach { step ->
            assertTrue(
                "step ${step.id} pulses ${step.transitionKey}, which no step targets",
                step.transitionKey in targets
            )
        }
    }
}
