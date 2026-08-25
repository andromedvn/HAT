package andromedvn.heuristic.activity.tracker.data

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import andromedvn.heuristic.activity.tracker.utils.TimeUtils

enum class TimeRangeLabel { DAY, WEEK, MONTH }
enum class ThemeType { DYNAMIC, STATIC }
enum class SortType { RECENT, CHRONOLOGICAL, DURATION }
enum class ProcessCategory { STANDARD, SYSTEM_UI, LAUNCHER, SELF }

@Serializable
data class UserSettings(
    val themeType: ThemeType = ThemeType.STATIC, 
    val sortType: SortType = SortType.RECENT, 
    val staticColor: Long = 0xFFFF8000, 
    val extractedColor: Long = 0xFF4287F5,
    val hasSeenOnboarding: Boolean = false,
    val hasSeenTutorialPrompt: Boolean = false,
    val hasSeenHistoryLimitWarning: Boolean = false,
    val installDateMillis: Long = System.currentTimeMillis(),
    val bypassHistoryLimit: Boolean = false,
    val archiveSyncIntervalHours: Int = 12,
    val minGapThresholdMins: Int = 5,
    val sessionClusteringMins: Int = 1,
    val ghostTimeTriggerMins: Int = 60
)

@Serializable
data class CombinedPreferences(
    val settings: UserSettings,
    val hiddenApps: Set<String>,
    val ignoredSessions: List<IgnoredSession> = emptyList(),
    val acknowledgedGhosts: List<AcknowledgedGhost> = emptyList()
)

@Serializable
data class OfflineActivityEntity(val id: Long = 0L, val title: String, val durationInMillis: Long, val timestamp: Long, val colorHex: Long = 0, val iconName: String = "Walk")

@Serializable
data class IgnoredSession(val packageName: String, val startMillis: Long, val endMillis: Long)

@Serializable
data class AcknowledgedGhost(val packageName: String, val startMillis: Long, val endMillis: Long)

@Serializable
data class TimeSpan(val s: Long, val e: Long)

@Serializable
data class ArchivedAppUsage(val pkg: String, val spans: List<TimeSpan>)

@Serializable
data class ArchivedDay(val dayStartMillis: Long, val usage: List<ArchivedAppUsage>)

sealed interface UnifiedActivity {
    val uniqueKey: String
    val title: String
    val durationInMillis: Long
    val timestamp: Long 
    val activeTimeTags: Set<String>
    val timeFormatted: String get() = TimeUtils.formatDuration(durationInMillis)
}

data class AppUsageItem(
    val packageName: String, override val title: String, override val durationInMillis: Long, override val timestamp: Long, override val activeTimeTags: Set<String> = emptySet(), val ghostIntervals: List<UsageGap> = emptyList()
) : UnifiedActivity { override val uniqueKey: String get() = packageName }

data class OfflineActivityItem(
    val id: String, override val title: String, override val durationInMillis: Long, override val timestamp: Long, val iconName: String, override val activeTimeTags: Set<String> = emptySet()
) : UnifiedActivity { override val uniqueKey: String get() = "offline_$id" }

data class UsageGap(val startMillis: Long, val endMillis: Long, val hasHiddenAppFootprint: Boolean = false) {
    val durationInMillis: Long get() = endMillis - startMillis
    val timeRangeFormatted: String get() { val sdf = SimpleDateFormat("h:mm a", Locale.getDefault()); return "${sdf.format(startMillis)} - ${sdf.format(endMillis)}" }
    val durationFormatted: String get() = TimeUtils.formatDuration(durationInMillis)
}

data class SessionItem(
    val startTime: Long, val endTime: Long, val activeDuration: Long, val subIntervals: List<Pair<Long, Long>> = emptyList()
) {
    val spanDuration: Long get() = endTime - startTime
    fun getTimeRangeStr(range: TimeRangeLabel): String {
        val sdfTime = SimpleDateFormat("h:mm a", Locale.getDefault())
        return when (range) {
            TimeRangeLabel.DAY -> "${sdfTime.format(startTime)} - ${sdfTime.format(endTime)}"
            TimeRangeLabel.WEEK -> "${SimpleDateFormat("EEE", Locale.getDefault()).format(startTime)}, ${sdfTime.format(startTime)}"
            TimeRangeLabel.MONTH -> "${SimpleDateFormat("MMM d", Locale.getDefault()).format(startTime)}, ${sdfTime.format(startTime)}"
        }
    }
    val activeDurationStr: String get() = TimeUtils.formatDuration(activeDuration)
}
