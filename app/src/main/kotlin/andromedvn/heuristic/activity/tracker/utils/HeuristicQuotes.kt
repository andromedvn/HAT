package andromedvn.heuristic.activity.tracker.utils

import andromedvn.heuristic.activity.tracker.ui.components.ChartMode
import andromedvn.heuristic.activity.tracker.ui.components.ChartPoint

/**
 * The tip copy (2026-11-01, owner-picked line by line): the HAT voice.
 * Plain words, short sentences, two lines maximum: the fact, then the way
 * out. The history-limit tips are battery-aware: when HAT is not exempted
 * they warn that battery saving can stop the archive; once exempted they
 * point to backups and the archive instead. The charter rules apply
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
        chartMode: ChartMode,
        isBatteryExempt: Boolean = true
    ): String {
        if (point == null) return ""

        val isFuture = point.isFuture

        // 1. Unrecorded History Logic
        if (!isDataReliable && !isFuture) {
            if (!hasSeenHistoryLimitWarning) {
                val cause = "It keeps about ~$osRetentionDays days of history, and HAT was not installed yet, so nothing saved them."
                return if (isBatteryExempt) {
                    "Android already deleted this.\n$cause Got a backup? Restore it to recover these days. The archive keeps the rest from now on."
                } else {
                    "Android already deleted this.\n$cause Battery saving can stop HAT's archive too. Exempt HAT to keep the rest."
                }
            }
            return if (isBatteryExempt) {
                "Deleted before HAT could save it.\nNothing to recover here. The archive keeps the rest from now on."
            } else {
                "Deleted before HAT could save it.\nNothing to recover here. With battery saving on, the archive can be stopped too. Exempt HAT to keep the rest."
            }
        }

        // 2. The Future
        if (isFuture) {
            val isHeavyScreenTime = totalScreenTimeMillis > 4L * 60L * 60L * 1000L
            return if (isHeavyScreenTime) {
                "You are looking at hours that have not happened yet.\nWhat do you plan to do with it?"
            } else {
                "These hours have not happened yet. HAT records them as they do."
            }
        }

        // 3. Week / Month Overviews (Past)
        if (!isDayView) {
            return "This day has no records.\nThe phone was off or dead, Usage access was off, or Android cleared the data. The Gaps screen can still log it."
        }

        // 4. The Past (Day View)
        return when (chartMode) {
            ChartMode.GAPS -> "All accounted for.\nApp time and labeled offline time cover this whole period. Nothing needs a labeling here."
            ChartMode.APP_USAGE -> "Hmm... No apps were present this hour.\nView the unlabeled gaps instead."
            ChartMode.OFFLINE -> "Nothing here.\nGaps is where this time gets named."
            else -> "No screen activity or manual logs here\nTap View Unlabeled Gaps below and tell HAT what really happened."
        }
    }
}
