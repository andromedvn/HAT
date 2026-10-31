package andromedvn.heuristic.activity.tracker.domain

/**
 * The dashboard tour curriculum (ratified 2026-10-31): five spotlight steps on
 * the real dashboard, ending at Label Gaps where the user names their first
 * real span. Merge is taught inside the final step's copy because that is
 * where merging actually happens: naming a gap folds the span's time into the
 * labeled activity. Session detail (AppDetails) is taught in the appRows step.
 */
data class TourStep(
    val id: String,
    val copy: String,
    val opensLabelGaps: Boolean = false,
    val completesTour: Boolean = false
)

object DashboardTourSteps {
    val steps = listOf(
        TourStep("timeSelector", "Pick a range: day, week, or month."),
        TourStep("chart", "Your phone time, from Android's own log. Tap a bar for detail."),
        TourStep("statCards", "Totals for apps and offline time."),
        TourStep("appRows", "Apps your phone used. Tap one to see its sessions."),
        TourStep(
            "labelGaps",
            "Spans with no name wait here. Open it and label your first one. Naming a gap folds that time into the app you pick.",
            opensLabelGaps = true,
            completesTour = true
        )
    )
}
