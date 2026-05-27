package andromedvn.heuristic.activity.tracker.utils

import andromedvn.heuristic.activity.tracker.ui.components.ChartMode
import andromedvn.heuristic.activity.tracker.ui.components.ChartPoint

object HeuristicQuotes {

    fun getQuote(
        point: ChartPoint?,
        totalScreenTimeMillis: Long,
        isDayView: Boolean,
        isDataReliable: Boolean,
        hasSeenHistoryLimitWarning: Boolean,
        osRetentionDays: Int,
        chartMode: ChartMode
    ): String {
        if (point == null) return ""

        val isFuture = point.isFuture

        // 1. Unrecorded History Logic
        if (!isDataReliable && !isFuture) {
            if (!hasSeenHistoryLimitWarning) {
                return "This goes back further than HAT can reach.\nAndroid only keeps about ~$osRetentionDays days of usage history. Anything older was already deleted before installation."
            }
            return "Android deleted this data before HAT could save it.\nThere's nothing here to recover."
        }

        // 2. The Future
        if (isFuture) {
            val isHeavyScreenTime = totalScreenTimeMillis > 4 * 60 * 60 * 1000L
            return if (isHeavyScreenTime) {
                "You've been on your phone a fair amount today.\nThis block hasn't happened yet."
            } else {
                "This time hasn't happened yet. HAT will log it when it does."
            }
        }

        // 3. Week / Month Overviews (Past)
        if (!isDayView) {
            return "Nothing recorded here.\nIf the phone was off that day, you can log the time manually from the Gaps screen."
        }

        // 4. The Past (Day View)
        return when (chartMode) {
            ChartMode.GAPS -> "No gaps here.\nScreen time and offline logs account for this entire period."
            ChartMode.APP_USAGE -> "No app usage here.\nOffline time won't show in this view — check the Gaps chart for the full picture."
            ChartMode.OFFLINE -> "Nothing logged here yet.\nHead to Gaps to label this time."
            else -> "No screen activity or manual logs were detected here.\nIf you were away from the device, categorize this time by tapping 'View Unlabeled Gaps' below."
        }
    }
}
