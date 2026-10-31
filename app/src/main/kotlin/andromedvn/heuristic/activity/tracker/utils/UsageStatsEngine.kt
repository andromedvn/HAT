package andromedvn.heuristic.activity.tracker.utils

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import andromedvn.heuristic.activity.tracker.domain.RawEvent
import andromedvn.heuristic.activity.tracker.domain.replayGhostIntervals
import andromedvn.heuristic.activity.tracker.domain.replayScreenOffIntervals
import andromedvn.heuristic.activity.tracker.domain.replayUnattendedIntervals
import andromedvn.heuristic.activity.tracker.domain.replayUsageIntervals
import kotlinx.coroutines.yield
import java.util.Calendar

class UsageStatsEngine(context: Context) {
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private data class AppState(val pkg: String, val time: Long)

    suspend fun getOldestEventTimestamp(): Long {
        val end = System.currentTimeMillis()
        val start = end - (365L * 86400000L) 
        val events = usageStatsManager.queryEvents(start, end) ?: return end
        
        val event = UsageEvents.Event()
        var yieldCounter = 0
        while (events.hasNextEvent()) {
            if (yieldCounter++ % 100 == 0) yield()
            events.getNextEvent(event)
            if (event.timeStamp > 0) return event.timeStamp
        }
        return end 
    }

    /**
     * Drains UsageStatsManager into plain [RawEvent]s so the actual replay logic
     * can be unit-tested off-device. The framework call stays here; everything
     * downstream is pure.
     */
    private suspend fun collectEvents(bufferedStart: Long, end: Long): List<RawEvent> {
        val events = usageStatsManager.queryEvents(bufferedStart, end) ?: return emptyList()
        val out = ArrayList<RawEvent>(1024)
        val event = UsageEvents.Event()
        var counter = 0
        while (events.hasNextEvent()) {
            if (counter++ % 100 == 0) yield()
            events.getNextEvent(event)
            out.add(
                RawEvent(
                    type = event.eventType,
                    timestamp = event.timeStamp,
                    packageName = event.packageName ?: "",
                    className = event.className
                )
            )
        }
        return out
    }

    /**
     * The raw stream exactly as the replays receive it (2026-10-23):
     * millisecond timestamps, in the order queryEvents yields them, NOT
     * re-sorted. The replays apply a stable sort by timestamp, so arrival
     * order decides which event wins when milliseconds tie, and second-
     * granularity log dumps cannot reveal that order. This export is the
     * only honest way to trace a replay decision on a real device.
     */
    suspend fun collectRawEventsForExport(start: Long, end: Long): List<RawEvent> = collectEvents(start, end)

    suspend fun getUsageIntervals(start: Long, end: Long, hiddenApps: Set<String>): Map<String, List<Pair<Long, Long>>> {
        val collected = collectEvents(start - 86400000L, end)
        val cap = minOf(end, System.currentTimeMillis())
        // Filtering the completed map is stricter than skipping hidden events
        // mid-replay: an app that was already resumed when the user hid it
        // would still leak its interval on the next terminator.
        return replayUsageIntervals(collected, start, end, cap)
            .filterKeys { it !in hiddenApps }
    }

    /**
     * Both idle projections from ONE event drain (2026-10-21): the
     * locked-screen spans (ghosts) and the untouched-app spans (idle
     * sessions). The regression audit already counted three to four full
     * stream drains per dashboard load; a fifth for the new projection was
     * avoided by replaying both over the same collected list. The framework
     * call stays here; everything downstream is pure and unit-tested.
     *
     * First list: app activity while the phone was locked or the screen was
     * off with no unlock after (Lock Screen Activity in the UI).
     * Second list: app in front, screen on, unlocked, no touch for a
     * stretch (Idle Sessions in the UI, the maintainer's original feature).
     */
    suspend fun getIdleProjections(start: Long, end: Long): Pair<List<Triple<String, Long, Long>>, List<Triple<String, Long, Long>>> {
        val collected = collectEvents(start - 86400000L, end)
        val cap = minOf(end, System.currentTimeMillis())
        val ghosts = replayGhostIntervals(collected, start, end, cap)
            .map { Triple(it.packageName, it.start, it.end) }
        val unattended = replayUnattendedIntervals(collected, start, end, cap)
            .map { Triple(it.packageName, it.start, it.end) }
        return ghosts to unattended
    }

    /**
     * Spans in which an app was resumed while the screen was provably dark:
     * background re-binds, music mode, lock-screen apps. Not usage, not
     * ghosts; the fourth category, surfaced for the user to name or dismiss.
     */
    suspend fun getScreenOffIntervals(start: Long, end: Long): Map<String, List<Pair<Long, Long>>> {
        val collected = collectEvents(start - 86400000L, end)
        val cap = minOf(end, System.currentTimeMillis())
        return replayScreenOffIntervals(collected, start, end, cap)
    }

    suspend fun mergeIntervals(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        yield()
        return andromedvn.heuristic.activity.tracker.domain.mergeIntervals(intervals)
    }

    suspend fun subtractIntervals(source: List<Pair<Long, Long>>, remove: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        yield()
        return andromedvn.heuristic.activity.tracker.domain.subtractIntervals(source, remove)
    }

    suspend fun sliceIntervals(intervals: List<Pair<Long, Long>>, bucketType: Int): Map<Long, Long> {
        val buckets = mutableMapOf<Long, Long>()
        val tz = java.util.TimeZone.getDefault()
        val cal = Calendar.getInstance()
        
        var yieldCounter = 0
        for ((start, end) in intervals) {
            if (yieldCounter++ % 50 == 0) yield()
            var current = start
            while (current < end) {
                if (bucketType == Calendar.HOUR_OF_DAY) {
                    val offset = tz.getOffset(current)
                    val bucketStart = current - ((current + offset) % 3600000L)
                    val chunkEnd = minOf(end, bucketStart + 3600000L)
                    buckets[bucketStart] = (buckets[bucketStart] ?: 0L) + (chunkEnd - current)
                    current = chunkEnd
                } else {
                    cal.timeInMillis = current
                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                    val bucketStart = cal.timeInMillis
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    val chunkEnd = minOf(end, cal.timeInMillis)
                    buckets[bucketStart] = (buckets[bucketStart] ?: 0L) + (chunkEnd - current)
                    current = chunkEnd
                }
            }
        }
        return buckets
    }
}
