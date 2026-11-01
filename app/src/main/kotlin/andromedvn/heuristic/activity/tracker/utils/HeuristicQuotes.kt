package andromedvn.heuristic.activity.tracker.utils

import andromedvn.heuristic.activity.tracker.ui.components.ChartMode
import andromedvn.heuristic.activity.tracker.ui.components.ChartPoint

/**
 * The tip copy (overhauled 2026-11-01, owner directive): the HAT voice.
 * Plain words, short sentences, two lines maximum: the fact, then the way
 * out. No speculation, no blame, no flourishes. The charter rules apply
 * (no em dashes, no hype words).
 */
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
                return "This goes back further than HAT can reach.\nAndroid keeps about ~$osRetentionDays days of usage history. Older records were deleted before HAT was installed. The archive protects everything from here on."
            }
            return "Android deleted this data before HAT could save it.\nThere is nothing here to recover."
        }

        // 2. The Future
        if (isFuture) {
            val isHeavyScreenTime = totalScreenTimeMillis > 4L * 60L * 60L * 1000L
            return if (isHeavyScreenTime) {
                "A heavy day so far.\nThis block has not happened yet."
            } else {
                "This time has not happened yet.\nHAT logs it when it does."
            }
        }

        // 3. Week / Month Overviews (Past)
        if (!isDayView) {
            return "Nothing recorded here.\nIf the phone was off that day, log the time manually from the Gaps screen."
        }

        // 4. The Past (Day View)
        return when (chartMode) {
            ChartMode.GAPS -> "No gaps here.\nScreen time and offline logs account for every minute of this period."
            ChartMode.APP_USAGE -> "No app usage in this hour.\nOffline time is hidden in this view. The Gaps chart shows the full picture."
            ChartMode.OFFLINE -> "Nothing logged yet.\nHead to Gaps to label this time."
            else -> "No screen activity or manual logs here.\nIf you were away from the device, name this time with View Unlabeled Gaps below."
        }
    }
}
