package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tour curriculum is copy the owner ratified (2026-11-04: ten steps,
 * ending on the settings gear, with the label dialog toured from inside its
 * own window). The charter bans em dashes and hype words in every user-facing
 * string, so the test enforces that here, where the strings live, instead of
 * trusting review.
 */
class DashboardTourStepsTest {

    @Test
    fun `tour covers its screens in the ratified order`() {
        val screens = DashboardTourSteps.steps.map { it.screen }.distinct()
        assertEquals(
            listOf(
                DashboardTourSteps.SCREEN_DASHBOARD,
                DashboardTourSteps.SCREEN_GAPS,
                DashboardTourSteps.SCREEN_LABEL_DIALOG
            ),
            screens
        )
    }

    @Test
    fun `every step has real copy that respects the charter`() {
        val banned = listOf("seamlessly", "effortlessly", "unlock", "journey")
        DashboardTourSteps.steps.forEach { step ->
            assertTrue("step ${step.id} has blank copy", step.copy.isNotBlank())
            // The banned characters are spelled as escapes: the copy scan itself bans the
// literal glyphs from every source file, including this enforcer.
assertFalse("step ${step.id} contains an em or en dash", step.copy.contains('\u2014') || step.copy.contains('\u2013'))
            banned.forEach { word ->
                assertFalse("step ${step.id} uses banned word $word", step.copy.lowercase().contains(word))
            }
        }
    }

    @Test
    fun `the tour ends on the settings gear`() {
        val last = DashboardTourSteps.steps.last()
        assertEquals("settingsGear", last.id)
        assertTrue(last.transitionKey != null)
        assertEquals(DashboardTourSteps.SCREEN_DASHBOARD, last.screen)
    }

    @Test
    fun `every screen's steps share that screen's route except the dialog step`() {
        DashboardTourSteps.steps.forEach { step ->
            if (step.screen == DashboardTourSteps.SCREEN_LABEL_DIALOG) {
                // The dialog lives on the gaps route; the banner renders there.
                assertEquals(DashboardTourSteps.SCREEN_GAPS, step.route)
            } else {
                assertEquals("step ${step.id} route mismatch", step.screen, step.route)
            }
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
