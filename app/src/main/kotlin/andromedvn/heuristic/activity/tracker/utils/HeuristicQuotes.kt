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
                    "Android already deleted this.\n$cause Got a backup? Restoring brings the old days back. From here on, the archive protects everything."
                } else {
                    "Android already deleted this.\n$cause Battery saving can stop HAT's archive too. Exempt HAT so this never happens again."
                }
            }
            return if (isBatteryExempt) {
                "Deleted before HAT could save it.\nNothing to recover here. The archive prevents it from now on."
            } else {
                "Deleted before HAT could save it.\nNothing to recover here. With battery saving on, the archive can be stopped too. Exempt HAT to keep the rest safe."
            }
        }

        // 2. The Future
        if (isFuture) {
            val isHeavyScreenTime = totalScreenTimeMillis > 4L * 60L * 60L * 1000L
            return if (isHeavyScreenTime) {
                "Yours to live, not to scroll.\nWhat do you plan to do with it?"
            } else {
                "Hasn't happened yet.\nHAT writes it down the moment it does."
            }
        }

        // 3. Week / Month Overviews (Past)
        if (!isDayView) {
            return "This day has no records.\nThe phone was off or dead, Usage access was off, or Android cleared what little there was. If you remember it, the Gaps screen can still log the time."
        }

        // 4. The Past (Day View)
        return when (chartMode) {
            ChartMode.GAPS -> "All accounted for.\nApp time and labeled offline time cover this whole period. Nothing here needs a name."
            ChartMode.APP_USAGE -> "No app used the screen this hour.\nOffline time does not show here. The Gaps chart has the full picture."
            ChartMode.OFFLINE -> "Nothing here.\nGaps is where this time gets named."
            else -> "No screen activity or manual logs here\nTap View Unlabeled Gaps below and tell HAT what really happened."
        }
    }
}
