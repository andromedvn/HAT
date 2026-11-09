package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.TimeRangeLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class ChartMode { DASHBOARD, APP_USAGE, OFFLINE, DETAIL, GAPS }

data class ChartPoint(val label: String, val values: List<Float>, val displayValue: String, val segmentValues: List<String>, val fullDateLabel: String = label, val syncTag: String = "", val isCurrent: Boolean = false, val isFuture: Boolean = false, val isNextFuture: Boolean = false)

/**
 * Stable, locale-independent identity for the bucket a millisecond value falls in.
 *
 * [ChartPoint.syncTag] is the tag the engine stamps on every bucket, so a list row and
 * a bar can be matched on this key instead of on a display string. The old comparison
 * went through SessionItem.getTimeRangeStr, which formats with Locale.getDefault(); a
 * device set to e.g. Turkish or Japanese produced a different string for the same
 * instant and the row never highlighted.
 *
 * Patterns are pinned to [Locale.ENGLISH]; the key is a function of the instant and the
 * range only. ONE definition: the engine stamps tags with it, the chart hit-testing and
 * the stats screens rebuild row keys with it. Do not re-inline the patterns.
 */
fun chartSyncKey(millis: Long, range: TimeRangeLabel): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return when (range) {
        TimeRangeLabel.DAY -> cal.get(Calendar.HOUR_OF_DAY).toString()
        TimeRangeLabel.WEEK -> SimpleDateFormat("EEE", Locale.ENGLISH).format(cal.time)
        TimeRangeLabel.MONTH -> SimpleDateFormat("MMM d", Locale.ENGLISH).format(cal.time)
    }
}
