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
    val hasSeenTour: Boolean = false,
    val hasSeenSupportPrompt: Boolean = false,
    val hasSeenHistoryLimitWarning: Boolean = false,
    val installDateMillis: Long = System.currentTimeMillis(),
    val bypassHistoryLimit: Boolean = false,
    val archiveSyncIntervalHours: Int = 12,
    // Rolling Vault (r12p, ported from HAT Pro): a background worker
    // re-exports the encrypted vault to the user-picked SAF folder on a
    // schedule, overwriting HAT_AutoBackup.zip in place. 0 = off.
    val autoBackupUri: String = "",
    val autoBackupIntervalHours: Int = 0,
    /**
     * Gap Minimum (2026-10-18, split from the question dial 2026-10-23):
     * stretches of recorded silence shorter than this never appear.
     * 0 = 1 min (the fresh default, leftmost per the owner's selector
     * convention), 1 = 5 min, 2 = 15 min, 3 = Custom. The mapping lives in
     * DetailLevel.kt, pure and tested. Custom minutes live in
     * customDetailMinutes below; old vaults without these fields keep
     * working. The legacy fields below stay for vault backward
     * compatibility: old vaults still deserialize, unread.
     */
    val detailLevel: Int = 0,
    /**
     * The user's custom Gap Minimum in minutes. Only read when detailLevel
     * is CUSTOM (3). -1 means unset: the dialog only ever writes values >= 1,
     * and DetailLevel falls back to 5 when it sees anything smaller, so old
     * settings JSON (which lacks this field) can never produce a broken
     * threshold.
     */
    val customDetailMinutes: Int = -1,
    /**
     * The archive cursor (2026-10-28): the last local midnight the archiver
     * has ATTEMPTED, whether or not that day produced rows. The old walk
     * restarted from MAX(start_millis) of archived rows, so a run of five or
     * more empty days (dead battery, airplane weekend) re-queried the same
     * empty days forever and everything after them was lost to Android's
     * retention window when it finally arrived. The cursor advances on empty
     * days too, so the walk can never wedge. 0 = unset: installs predating
     * this field fall back to the watermark-based start until the first
     * cursor write.
     */
    val archiveCursorMillis: Long = 0,
    // --- Legacy fields: kept for old-vault deserialization, no longer read ---
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

/**
 * One app's totals for the window: merged usage intervals, the display name,
 * and the sync tags for the ledger. Idle projections are gone (r9): the app
 * never guesses what happened while nobody was looking.
 */
data class AppUsageItem(
    val packageName: String, override val title: String, override val durationInMillis: Long, override val timestamp: Long, override val activeTimeTags: Set<String> = emptySet()
) : UnifiedActivity { override val uniqueKey: String get() = packageName }

/**
 * One app's Screen-Off Activity in the queried window: the total time it was
 * resumed while the screen was off, the most recent span's start,
 * and the spans themselves so the detail view can show each one.
 *
 * Not usage (nobody can use a phone with the screen off), not a ghost (those
 * stay for the screen-on-locked ambiguity). The user names each span or
 * dismisses it back into usage; nothing is auto-decided.
 */
data class ScreenOffItem(
    val packageName: String,
    override val title: String,
    override val durationInMillis: Long,
    override val timestamp: Long,
    val spans: List<Pair<Long, Long>> = emptyList()
) : UnifiedActivity {
    override val uniqueKey: String get() = "screenoff:$packageName"
    override val activeTimeTags: Set<String> get() = emptySet()
}

data class OfflineActivityItem(
    val id: String, override val title: String, override val durationInMillis: Long, override val timestamp: Long, val iconName: String, override val activeTimeTags: Set<String> = emptySet(),
    /**
     * How many stored rows collapse into this one entry because they share a title.
     *
     * 1 means this is a single label. A value above 1 means the same activity was
     * recorded more than once - usually because an older build inserted a fresh row
     * every time the same gap was labelled (it inserted with `id = 0`, so
     * `CONFLICT_REPLACE` had no key to collide with).
     *
     * HAT never deletes these on its own. The count is SHOWN inline on the entry so the
     * user can see it and decide, per their decision of 2026-10-01: duplicates are
     * surfaced, never silently removed.
     */
    val storedRowCount: Int = 1
) : UnifiedActivity {
    /**
     * Crash guard (2026-09-30). Compose throws
     * `IllegalArgumentException: Key "..." was already used` when a LazyColumn
     * receives two rows with the same key, which crashes the app on the spot.
     *
     * A restored or duplicated vault can legitimately contain the same title more
     * than once, so the key must be unique even when the data is not. Hashing the
     * identity into the key makes a collision vanishingly unlikely without
     * changing what the key means.
     */
    override val uniqueKey: String
        get() = "offline_${id}_${timestamp}_${durationInMillis}".hashCode().toString()
}

data class UsageGap(
    val startMillis: Long,
    val endMillis: Long,
    val hasHiddenAppFootprint: Boolean = false,
    /**
     * What the engine could see on either side of this gap (2026-10-16).
     *
     * A gap card used to say only "Unlabeled Gap", the one fact every card in
     * a section titled MAJOR GAPS already implies. But the scan walk sees the
     * tracked interval that ended right before the gap and the one that starts
     * right after it: an app name, or a label title like "Sleep". Naming them
     * gives the user the exact context that triggers "oh right, I went for a
     * walk after lunch" - evidence the engine already holds, previously
     * thrown away. Null at day boundaries where no tracked interval exists on
     * that side. Never invented: a null neighbor stays null.
     */
    val precededBy: String? = null,
    val followedBy: String? = null
) {
    val durationInMillis: Long get() = endMillis - startMillis
    val timeRangeFormatted: String get() { val sdf = SimpleDateFormat("h:mm a", Locale.getDefault()); return "${sdf.format(startMillis)} - ${sdf.format(endMillis)}" }
    val durationFormatted: String get() = TimeUtils.formatDuration(durationInMillis)

}

data class SessionItem(
    val startTime: Long, val endTime: Long, val activeDuration: Long, val subIntervals: List<Pair<Long, Long>> = emptyList()
) {
    val spanDuration: Long get() = endTime - startTime

    /**
     * The figure shown for a session in the UI, and the one that must be summed
     * when sessions are combined.
     *
     * This is the ACTIVE duration - the sum of the real sub-intervals, excluding
     * the idle gaps that were bridged by the session-clustering tolerance. It is
     * deliberately NOT [spanDuration] (the wall-clock span from first resume to
     * last pause), which is larger whenever a cluster contains an internal gap.
     *
     * Summing [spanDuration] across selected sessions made the header total
     * disagree with the sum of the rows beneath it. Always aggregate this.
     */
    val displayedDuration: Long get() = activeDuration
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

/**
 * Rows that cover the SAME stretch of time and would render as two entries for one gap.
 * Reported to the user; removal is always an explicit user action on a visible row.
 */
data class DuplicateLabelGroup(
    val startMillis: Long,
    val endMillis: Long,
    val rowIds: List<Long>,
    val title: String
)
