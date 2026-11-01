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
}
