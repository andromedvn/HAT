package andromedvn.heuristic.activity.tracker.domain

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The app tour (2026-11-01, second pass: "make it sound human"): eleven steps
 * across four real screens. Each step names the screen it lives on; when the
 * tour advances past a screen's last step the host navigates to the next
 * route and the next screen's host picks the tour up. Skipping anywhere ends
 * the whole tour. Copy speaks like a friend showing you around, not a manual.
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
        TourStep("timeSelector", SCREEN_DASHBOARD, "dashboard", "Day, week, or month. The bars follow whatever you pick.", "timeSelector"),
        TourStep("chart", SCREEN_DASHBOARD, "dashboard", "Every bar is an hour of your phone. Tap one and HAT tells you what it knows.", "chart"),
        TourStep("statCards", SCREEN_DASHBOARD, "dashboard", "The two totals: what lit up your screen, and everything that happened without it.", "statCards"),
        TourStep("appRows", SCREEN_DASHBOARD, "dashboard", "Your apps, heaviest first. Tap one and every session inside opens up.", "appRows"),
        TourStep("labelGaps", SCREEN_DASHBOARD, "dashboard", "This button is where the honesty lives. Open it, and name what the phone cannot.", "labelGaps"),
        // Label Gaps screen
        TourStep("gapsScreen", SCREEN_GAPS, "label_gaps", "Here is where the truth keeps itself: the off hours, the idle stretches, everything Android cannot name.", "gapsScreen"),
        TourStep("gapsLabel", SCREEN_GAPS, "label_gaps", "Tap a span, say what it really was, save. Your timeline folds it in like it was always there.", "gapsScreen"),
        // App usage stats screen
        TourStep("statsScreen", SCREEN_STATS, "app_usage_stats", "The full ranking. Every app, every hour it quietly took.", "statsScreen"),
        TourStep("statsDetail", SCREEN_STATS, "app_usage_stats", "Tap any app and walk its sessions, minute by minute.", "statsScreen"),
        // Settings screen
        TourStep("settingsScreen", SCREEN_SETTINGS, "settings", "Everything you can bend to your will: themes, thresholds, backups, all of it.", "settingsScreen"),
        TourStep("settingsHidden", SCREEN_SETTINGS, "settings", "Last thing: Hidden Apps quiets the noisy ones. That is the whole tour. The rest is yours to find.", "settingsScreen", completesTour = true)
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
