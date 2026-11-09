package andromedvn.heuristic.activity.tracker.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

object TimeUtils {
    fun formatDuration(durationInMillis: Long): String {
        if (durationInMillis <= 0L) return "0m"
        if (durationInMillis == 86400000L) return "24h"

        val d = durationInMillis / 86400000L
        val h = (durationInMillis % 86400000L) / 3600000L
        val m = (durationInMillis % 3600000L) / 60000L
        val s = (durationInMillis % 60000L) / 1000L
        val ms = durationInMillis % 1000L

        return when {
            d > 0 -> if (h > 0) "${d}d ${h}h" else "${d}d"
            h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
            m > 0 -> if (s > 0) "${m}m ${s}s" else "${m}m"
            s > 0 -> "${s}s"
            else -> "${ms}ms"
        }
    }

    /** One shared time-of-day formatter ("h:mm a"); was hand-built at ten call sites. */
    fun formatTime(millis: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

    /** Time-of-day with seconds ("h:mm:ss a"), the gap dialog's precision. */
    fun formatTimeSecs(millis: Long): String =
        SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(millis))

    /** Filename stamp shared by the diagnostics exports and the vault backup. */
    fun stamp(): String =
        SimpleDateFormat("yyyy_MM_dd", Locale.getDefault()).format(Date())

    /**
     * Duration with seconds ("7h 16m 32s"), the gap dialog's DURATION row.
     * Same zero-dropping as [formatDuration] except seconds always show -
     * they are the row's whole point.
     */
    fun formatDurationSecs(durationInMillis: Long): String {
        if (durationInMillis <= 0L) return "0s"
        val totalSecs = durationInMillis / 1000L
        val h = totalSecs / 3600L
        val m = (totalSecs % 3600L) / 60L
        val s = totalSecs % 60L
        val parts = mutableListOf<String>()
        if (h > 0) parts.add("${h}h")
        if (m > 0) parts.add("${m}m")
        parts.add("${s}s")
        return parts.joinToString(" ")
    }

    /** Days since the local epoch, noon-normalized so DST shifts cannot split a day. */
    private fun dayNumber(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return Math.round(cal.timeInMillis / 86400000.0)
    }

    /**
     * The gap dialog's day word (owner spec, 2026-11-08): "today",
     * "yesterday", then the date - "Nov 1" style, year appended only when it
     * differs from now. No "2 days ago" - that phrasing was cut.
     */
    fun relativeDayLabel(millis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = dayNumber(now) - dayNumber(millis)
        if (diff <= 0L) return "today"
        if (diff == 1L) return "yesterday"
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        val nowCal = Calendar.getInstance()
        nowCal.timeInMillis = now
        val base = SimpleDateFormat("MMM d", Locale.ENGLISH).format(cal.time)
        return if (cal.get(Calendar.YEAR) != nowCal.get(Calendar.YEAR)) "$base ${cal.get(Calendar.YEAR)}" else base
    }

    /** DST-safe whole-day difference between two instants (now - then). */
    fun dayNumberPublic(thenMillis: Long, nowMillis: Long): Long =
        dayNumber(nowMillis) - dayNumber(thenMillis)

    /** How many calendar days a span touches (a 11:59PM-12:01AM gap touches 2). */
    fun spanDays(startMillis: Long, endMillis: Long): Int =
        (dayNumber(endMillis) - dayNumber(startMillis)).toInt() + 1
}
