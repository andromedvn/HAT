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
    const val SCREEN_STATS = "app_usage_stats"
    const val SCREEN_SETTINGS = "settings"

    val steps = listOf(
        // Dashboard
        TourStep("timeSelector", SCREEN_DASHBOARD, SCREEN_DASHBOARD, "Tap Day, Week, or Month. The bars follow your pick.", "timeSelector"),
        TourStep("chart", SCREEN_DASHBOARD, SCREEN_DASHBOARD, "Each bar is one hour. Tap a bar to see its apps.", "chart"),
        TourStep("statCards", SCREEN_DASHBOARD, SCREEN_DASHBOARD, "One card counts screen time. The other counts time the screen was off.", "statCards"),
        TourStep("appRows", SCREEN_DASHBOARD, SCREEN_DASHBOARD, "Apps and offline time fill this list. Tap one to open its sessions.", "appRows"),
        // Transition: the real Label Gaps button pulses; tapping it opens the screen.
        TourStep("labelGaps", SCREEN_DASHBOARD, SCREEN_DASHBOARD, "This button opens your gaps. Tap it and name the first one.", "labelGaps", transitionKey = "labelGaps"),
        // Label Gaps screen
        TourStep("gapsScreen", SCREEN_GAPS, SCREEN_GAPS, "Every stretch when the screen was off. The phone cannot say what happened. You can.", "gapsScreen"),
        TourStep("gapsLabel", SCREEN_GAPS, SCREEN_GAPS, "Tap a span, name it, save. HAT takes your word for it.", "gapsScreen"),
        // Transition back through the hub: the Total Apps Usage card pulses; tapping it opens the ranking.
        TourStep("statCardsNav", SCREEN_DASHBOARD, SCREEN_DASHBOARD, "This card opens the Apps Usage ranking. Tap it.", "statCardApps", transitionKey = "statCardApps"),
        // App usage stats screen
        TourStep("statsDetail", SCREEN_STATS, SCREEN_STATS, "Tap an app to list every session and its length.", "statsScreen"),
        // Transition back through the hub: the header gear pulses; tapping it opens Settings.
        TourStep("settingsGear", SCREEN_DASHBOARD, SCREEN_DASHBOARD, "The gear up top opens Settings. Tap it.", "settingsGear", transitionKey = "settingsGear"),
        // Settings screen
        TourStep("settingsHidden", SCREEN_SETTINGS, SCREEN_SETTINGS, "Hidden Apps keeps chosen apps off your lists. That's the whole tour.", "settingsScreen", completesTour = true)
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
