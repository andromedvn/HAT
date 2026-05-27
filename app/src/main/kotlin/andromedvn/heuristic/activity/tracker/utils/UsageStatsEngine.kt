package andromedvn.heuristic.activity.tracker.utils

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
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

    suspend fun getUsageIntervals(start: Long, end: Long, hiddenApps: Set<String>): Map<String, List<Pair<Long, Long>>> {
        val bufferedStart = start - 86400000L
        val events = usageStatsManager.queryEvents(bufferedStart, end) ?: return emptyMap()
        val intervals = mutableMapOf<String, MutableList<Pair<Long, Long>>>()
        val resumed = mutableMapOf<String, AppState>()
        
        val event = UsageEvents.Event()
        var yieldCounter = 0
        while (events.hasNextEvent()) {
            if (yieldCounter++ % 100 == 0) yield() 
            events.getNextEvent(event)
            
            if (event.eventType == 16 || event.eventType == 26) { 
                for ((_, state) in resumed) {
                    if (state.time < event.timeStamp) {
                        intervals.getOrPut(state.pkg) { mutableListOf() }.add(state.time to event.timeStamp)
                    }
                }
                resumed.clear()
                continue
            }

            val pkg = event.packageName ?: continue
            if (hiddenApps.contains(pkg)) continue
            val clazz = event.className ?: pkg

            if (event.eventType == 1) { // ACTIVITY_RESUMED
                resumed[clazz]?.let { oldState ->
                    if (oldState.time < event.timeStamp) intervals.getOrPut(oldState.pkg) { mutableListOf() }.add(oldState.time to (event.timeStamp - 1))
                }
                resumed[clazz] = AppState(pkg, event.timeStamp)
            } else if (event.eventType == 2 || event.eventType == 23) { // ACTIVITY_PAUSED or STOPPED
                val state = resumed.remove(clazz)
                if (state != null && state.time < event.timeStamp) {
                    intervals.getOrPut(state.pkg) { mutableListOf() }.add(state.time to event.timeStamp)
                }
            }
        }

        val cap = minOf(end, System.currentTimeMillis())
        resumed.values.forEach { state ->
            if (state.time < cap) {
                intervals.getOrPut(state.pkg) { mutableListOf() }.add(state.time to cap)
            }
        }
        
        val clampedIntervals = mutableMapOf<String, List<Pair<Long, Long>>>()
        for ((pkg, ivs) in intervals) {
            val valid = ivs.mapNotNull { (s, e) ->
                val cS = maxOf(s, start)
                val cE = minOf(e, end)
                if (cE > cS) cS to cE else null
            }
            if (valid.isNotEmpty()) clampedIntervals[pkg] = valid
        }
        
        return clampedIntervals
    }

    suspend fun getGhostCandidateIntervals(start: Long, end: Long): List<Triple<String, Long, Long>> {
        val events = usageStatsManager.queryEvents(start - 86400000L, end) ?: return emptyList()
        var isHuman = false
        var currentGhostPkg: String? = null
        var ghostStart = -1L
        val ghosts = mutableListOf<Triple<String, Long, Long>>()
        
        val event = UsageEvents.Event()
        var yieldCounter = 0
        while (events.hasNextEvent()) {
            if (yieldCounter++ % 100 == 0) yield()
            events.getNextEvent(event)
            when (event.eventType) {
                15 -> isHuman = false // SCREEN_INTERACTIVE
                18 -> isHuman = true  // KEYGUARD_HIDDEN (Definitive human presence)
                16, 26 -> { // SCREEN_NON_INTERACTIVE
                    if (!isHuman && currentGhostPkg != null && ghostStart != -1L) {
                        ghosts.add(Triple(currentGhostPkg, ghostStart, event.timeStamp))
                    }
                    isHuman = false
                    currentGhostPkg = null
                    ghostStart = -1L
                }
                1 -> { // ACTIVITY_RESUMED
                    if (!isHuman) {
                        currentGhostPkg = event.packageName
                        ghostStart = event.timeStamp
                    }
                }
                2, 23 -> { // ACTIVITY_PAUSED / STOPPED
                    if (!isHuman && currentGhostPkg != null && currentGhostPkg == event.packageName && ghostStart != -1L) {
                        ghosts.add(Triple(currentGhostPkg, ghostStart, event.timeStamp))
                        currentGhostPkg = null
                        ghostStart = -1L
                    }
                }
            }
        }
        if (!isHuman && currentGhostPkg != null && ghostStart != -1L && ghostStart < end) {
            ghosts.add(Triple(currentGhostPkg, ghostStart, minOf(end, System.currentTimeMillis())))
        }
        
        return ghosts.mapNotNull { 
            val cS = maxOf(it.second, start)
            val cE = minOf(it.third, end)
            if (cE > cS) Triple(it.first, cS, cE) else null
        }
    }

    suspend fun mergeIntervals(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.first }
        val merged = mutableListOf<Pair<Long, Long>>()
        var current = sorted[0]

        var yieldCounter = 0
        for (i in 1 until sorted.size) {
            if (yieldCounter++ % 100 == 0) yield()
            val next = sorted[i]
            if (current.second >= next.first) {
                current = current.copy(second = maxOf(current.second, next.second))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    suspend fun subtractIntervals(source: List<Pair<Long, Long>>, remove: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        var current = source
        for (rem in remove) {
            yield()
            val next = mutableListOf<Pair<Long, Long>>()
            for (src in current) {
                if (src.second <= rem.first || src.first >= rem.second) next.add(src) 
                else { 
                    if (src.first < rem.first) next.add(src.first to rem.first)
                    if (src.second > rem.second) next.add(rem.second to src.second) 
                }
            }
            current = next
        }
        return current
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
