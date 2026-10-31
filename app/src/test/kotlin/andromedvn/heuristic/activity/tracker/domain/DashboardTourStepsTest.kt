package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tour curriculum is copy the owner ratified (2026-10-31): five steps,
 * ending at Label Gaps with a real navigation. The charter bans em dashes
 * and hype words in every user-facing string, so the test enforces that here,
 * where the strings live, instead of trusting review.
 */
class DashboardTourStepsTest {

    @Test
    fun `tour has exactly five steps in the ratified order`() {
        val ids = DashboardTourSteps.steps.map { it.id }
        assertEquals(listOf("timeSelector", "chart", "statCards", "appRows", "labelGaps"), ids)
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
    fun `final step opens Label Gaps and completes the tour`() {
        val last = DashboardTourSteps.steps.last()
        assertEquals("labelGaps", last.id)
        assertTrue(last.opensLabelGaps)
        assertTrue(last.completesTour)
        DashboardTourSteps.steps.dropLast(1).forEach { step ->
            assertFalse("only the last step may complete the tour: ${step.id}", step.completesTour)
        }
    }
}
