package andromedvn.heuristic.activity.tracker.domain

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One step of the app tour. [transitionKey] marks a TRANSITION step: the tour
 * does not teleport to the next screen. Instead the real dashboard button that
 * leads there is highlighted with a pulsing ring, Next is hidden, and tapping
 * the button (the cutout) performs the button's own navigation. The user
 * learns how to reach each screen, because every screen in HAT is entered
 * from the dashboard (hub and spoke).
 */
data class TourStep(
    val id: String,
    val screen: String,
    val route: String,
    val copy: String,
    val targetKey: String? = null,
    val transitionKey: String? = null,
    val completesTour: Boolean = false
)

object DashboardTourSteps {
    const val SCREEN_DASHBOARD = "dashboard"
    const val SCREEN_GAPS = "label_gaps"

    /**
     * The label-dialog step never renders in the main-window overlay: the
     * dialog is its own window, so it hosts its own tour banner (r12).
     */
    const val SCREEN_LABEL_DIALOG = "label_dialog"

    // The owner-ratified curriculum (2026-11-04): ten steps, ending on the
    // settings gear. Tapping the gear opens Settings and the tour ends there.
    val steps = listOf(
        // Dashboard
        TourStep("timeSelector", SCREEN_DASHBOARD, SCREEN_DASHBOARD, "This part lets you view different time windows: day, week, or month.", "timeSelector"),
        TourStep("chart", SCREEN_DASHBOARD, SCREEN_DASHBOARD, "This is the chart. Tap a bar to see the time and activity details inside it, or long press a bar to highlight the apps that were present that hour.", "chart"),
        TourStep("statCards", SCREEN_DASHBOARD, SCREEN_DASHBOARD, "These cards show the totals. Apps Usage is your total screen time, and Offline Activity is your total labeled gaps.", "statCards"),
        TourStep("appRows", SCREEN_DASHBOARD, SCREEN_DASHBOARD, "This is the session list. It fills with app usage cards and your offline activity cards. Long press a card to highlight its bars in the chart; tap one to open its detailed session breakdown.", "appRows"),
        // Transition: the real Label Gaps button pulses; tapping it opens the screen.
        TourStep("labelGaps", SCREEN_DASHBOARD, SCREEN_DASHBOARD, "This button opens the unlabeled gaps screen. Tap to open it.", "labelGaps", transitionKey = "labelGaps"),
        // Gaps screen
        TourStep("gapsScreen", SCREEN_GAPS, SCREEN_GAPS, "From here you can manage each unlabeled gap however you like. First thing first: the only cards that show up here are the ones where you are not using your phone.", "gapsScreen"),
        TourStep("gapCard", SCREEN_GAPS, SCREEN_GAPS, "Tap any gap card to open its label dialog, or long press and select other gaps to merge and label them.", "firstGapCard"),
        // Transition: the first gap card pulses; tapping it opens the label dialog.
        TourStep("gapCardPulse", SCREEN_GAPS, SCREEN_GAPS, "Tap this unlabeled gap card.", "firstGapCard", transitionKey = "firstGapCard"),
        // Inside the label dialog: the dialog hosts its own tour banner.
        TourStep("labelDialog", SCREEN_LABEL_DIALOG, SCREEN_GAPS, "This is the label dialog. Name and customize the gap you tapped, then save. It will show up in the dashboard's session list.", "labelDialog"),
        // Transition back through the hub: the gear pulses; tapping it opens
        // Settings, and the tour ends there.
        TourStep("settingsGear", SCREEN_DASHBOARD, SCREEN_DASHBOARD, "Tap this icon to open the settings, where you can customize how the app functions.", "settingsGear", transitionKey = "settingsGear"),
    )

    fun forScreen(screen: String): List<TourStep> = steps.filter { it.screen == screen }
}

/**
 * One tour, every screen. The overlay lives at the NavHost level and reads
 * [active] plus the current step; skipping or finishing sets both flags and
 * persists hasSeenTour.
 */
object AppTourState {
    var active by mutableStateOf(false)
    var stepIndex by mutableIntStateOf(0)

    /**
     * True while the auto-scroll is running: the cutout then tracks the moving
     * target 1:1 (snap) instead of tween-chasing it, which read as jelly.
     */
    var snapCutout by mutableStateOf(false)

    val current: TourStep? get() = if (active) DashboardTourSteps.steps.getOrNull(stepIndex) else null

    fun start() {
        active = true
        stepIndex = 0
    }

    fun end() {
        active = false
        stepIndex = 0
    }
}
