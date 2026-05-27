package andromedvn.heuristic.activity.tracker.utils

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
}
