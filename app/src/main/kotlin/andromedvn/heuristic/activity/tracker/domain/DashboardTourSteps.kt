package andromedvn.heuristic.activity.tracker.domain

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The app tour (2026-11-01, owner: "make the user familiar with every screen"):
 * eleven spotlight steps across four real screens. Each step names the screen
 * it lives on; when the tour advances past a screen's last step the host
 * navigates to the next route and the next screen's host picks the tour up.
 * Skipping anywhere ends the whole tour.
 *
 * Screen-level steps (gaps list, stats, settings) target the screen's root
 * container: the spotlight frames the screen itself while the copy teaches it.
 */
data class TourStep(
    val id: String,
    val screen: String,
    val route: String,
    val copy: String,
    val targetKey: String? = null,
    val completesTour: Boolean = false
)

object DashboardTourSteps {
    const val SCREEN_DASHBOARD = "dashboard"
    const val SCREEN_GAPS = "label_gaps"
    const val SCREEN_STATS = "app_usage_stats"
    const val SCREEN_SETTINGS = "settings"

    val steps = listOf(
        // Dashboard
        TourStep("timeSelector", SCREEN_DASHBOARD, "dashboard", "Pick a range: day, week, or month. The chart follows.", "timeSelector"),
        TourStep("chart", SCREEN_DASHBOARD, "dashboard", "Your phone time, from Android's own log. Tap a bar for detail.", "chart"),
        TourStep("statCards", SCREEN_DASHBOARD, "dashboard", "Totals for apps and offline time. Tap either for a breakdown.", "statCards"),
        TourStep("appRows", SCREEN_DASHBOARD, "dashboard", "Apps your phone used, heaviest first. Tap one to see its sessions.", "appRows"),
        TourStep("labelGaps", SCREEN_DASHBOARD, "dashboard", "Spans with no name wait here. Open it and label your first one. Naming a gap folds that time into the app you pick.", "labelGaps"),
        // Label Gaps screen
        TourStep("gapsScreen", SCREEN_GAPS, "label_gaps", "This is the ledger of unaccounted time: screen-off spans, idle stretches, gaps. Everything Android cannot name.", "gapsScreen"),
        TourStep("gapsLabel", SCREEN_GAPS, "label_gaps", "Tap a span, pick what it was, save. The label flows back into your timeline.", "gapsScreen"),
        // App usage stats screen
        TourStep("statsScreen", SCREEN_STATS, "app_usage_stats", "Every app, ranked by the hours it took. The picture behind the bars.", "statsScreen"),
        TourStep("statsDetail", SCREEN_STATS, "app_usage_stats", "Tap any app to walk its sessions minute by minute.", "statsScreen"),
        // Settings screen
        TourStep("settingsScreen", SCREEN_SETTINGS, "settings", "Everything configurable lives here: themes, thresholds, backups.", "settingsScreen"),
        TourStep("settingsHidden", SCREEN_SETTINGS, "settings", "Hidden Apps keeps chosen apps off your timeline. The tour ends here; the rest you will find on your own.", "settingsScreen", completesTour = true)
    )

    fun forScreen(screen: String): List<TourStep> = steps.filter { it.screen == screen }
}

/**
 * One tour, every screen. Hosts read [active] plus the current step's screen;
 * skipping or finishing sets both flags and persists hasSeenTour.
 */
object AppTourState {
    var active by mutableStateOf(false)
    var stepIndex by mutableIntStateOf(0)

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
