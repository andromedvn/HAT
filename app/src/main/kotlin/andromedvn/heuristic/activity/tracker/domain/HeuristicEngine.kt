package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.*
import andromedvn.heuristic.activity.tracker.ui.components.ChartMode
import andromedvn.heuristic.activity.tracker.ui.components.ChartPoint
import andromedvn.heuristic.activity.tracker.utils.TimeUtils
import andromedvn.heuristic.activity.tracker.utils.UsageStatsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HeuristicEngine(private val repository: ActivityRepository, private val engine: UsageStatsEngine) {

    private fun getSyncTagsForIntervals(intervals: List<Pair<Long, Long>>, range: TimeRangeLabel): Set<String> {
        val tags = mutableSetOf<String>()
        val cal = Calendar.getInstance()
        for (iv in intervals) {
            var curr = iv.first
            while (curr < iv.second) {
                cal.timeInMillis = curr
                when (range) {
                    TimeRangeLabel.DAY -> { 
                        tags.add(cal.get(Calendar.HOUR_OF_DAY).toString())
                        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                        cal.add(Calendar.HOUR_OF_DAY, 1) 
                    }
                    TimeRangeLabel.WEEK -> { 
                        tags.add(SimpleDateFormat("EEE", Locale.ENGLISH).format(cal.time))
                        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                        cal.add(Calendar.DAY_OF_YEAR, 1) 
                    }
                    TimeRangeLabel.MONTH -> { 
                        tags.add(SimpleDateFormat("MMM d", Locale.ENGLISH).format(cal.time))
                        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                        cal.add(Calendar.DAY_OF_YEAR, 1) 
                    }
                }
                curr = cal.timeInMillis
            }
        }
        return tags
    }

    suspend fun getAppUsage(start: Long, end: Long, range: TimeRangeLabel, ghostMins: Int, hiddenPkgs: Set<String>, ackGhosts: List<AcknowledgedGhost>): Triple<Long, Long, List<AppUsageItem>> = withContext(Dispatchers.Default) {
        val ghostMillis = if (ghostMins <= 0) Long.MAX_VALUE else ghostMins * 60L * 1000L
        val intervalsMap = repository.getFilteredIntervals(start, end, hiddenPkgs)
        val rawGhosts = engine.getGhostCandidateIntervals(start, end)
        var totalAppExecution = 0L

        val apps = mutableListOf<AppUsageItem>()
        for ((pkg, intervals) in intervalsMap) {
            yield()
            val merged = engine.mergeIntervals(intervals)
            val total = merged.sumOf { it.second - it.first }
            
            val pkgGhosts = rawGhosts.filter { it.first == pkg }
                .filter { (it.third - it.second) >= ghostMillis }
                .filterNot { g -> ackGhosts.any { it.packageName == pkg && it.startMillis == g.second && it.endMillis == g.third } }
                .map { UsageGap(it.second, it.third, false) }
            
            totalAppExecution += total

            val latest = merged.maxOfOrNull { it.second } ?: start
            apps.add(AppUsageItem(pkg, repository.getAppName(pkg), total, latest, getSyncTagsForIntervals(merged, range), ghostIntervals = pkgGhosts))
        }
        
        val validApps = apps.filter { it.durationInMillis > 0L || it.ghostIntervals.isNotEmpty() }.sortedByDescending { it.durationInMillis }
        val trueDeviceIntervals = repository.getAllIntervalsCached(start, end).values.flatten()
        val trueDeviceScreenTime = engine.mergeIntervals(trueDeviceIntervals).sumOf { it.second - it.first }
        Triple(maxOf(trueDeviceScreenTime, 0L), maxOf(totalAppExecution, 0L), validApps)
    }

    suspend fun getAppDetail(packageName: String, start: Long, end: Long, ghostMins: Int, ackGhosts: List<AcknowledgedGhost>): AppUsageItem? = withContext(Dispatchers.Default) {
        val ghostMillis = if (ghostMins <= 0) Long.MAX_VALUE else ghostMins * 60L * 1000L
        val intervalsMap = repository.getAllIntervalsCached(start, end)
        val intervals = intervalsMap[packageName] ?: emptyList()
        val merged = engine.mergeIntervals(intervals)
        val total = merged.sumOf { it.second - it.first }
        val rawGhosts = engine.getGhostCandidateIntervals(start, end)
        
        val pkgGhosts = rawGhosts.filter { it.first == packageName }
            .filter { (it.third - it.second) >= ghostMillis }
            .filterNot { g -> ackGhosts.any { it.packageName == packageName && it.startMillis == g.second && it.endMillis == g.third } }
            .map { UsageGap(it.second, it.third, false) }
        
        if (total <= 0L && pkgGhosts.isEmpty()) return@withContext null
        AppUsageItem(packageName, repository.getAppName(packageName), total, merged.maxOfOrNull { it.second } ?: start, emptySet(), ghostIntervals = pkgGhosts)
    }

    suspend fun getOfflineActivities(start: Long, end: Long, range: TimeRangeLabel): List<OfflineActivityItem> = withContext(Dispatchers.Default) {
        val entities = repository.getRawOfflineActivitiesBetween(start, end).filter { it.durationInMillis > 0L }
        if (entities.isEmpty()) return@withContext emptyList()
        
        val allAppIntervals = repository.getAllIntervalsCached(start, end).values.flatten()
        val mergedApp = engine.mergeIntervals(allAppIntervals)
        
        val grouped = entities.groupBy { it.title to it.iconName }
        grouped.map { (key, list) ->
            val (title, icon) = key
            val rawIntervals = list.map { maxOf(start, it.timestamp) to minOf(end, it.timestamp + it.durationInMillis) }.filter { it.second > it.first }
            val mergedOffline = engine.mergeIntervals(rawIntervals)
            val shardedOffline = engine.subtractIntervals(mergedOffline, mergedApp)
            val totalDuration = shardedOffline.sumOf { it.second - it.first }
            val minTimestamp = shardedOffline.minOfOrNull { it.first } ?: start
            val tags = getSyncTagsForIntervals(shardedOffline, range)
            
            OfflineActivityItem(title, title, totalDuration, minTimestamp, icon, tags) 
        }.filter { it.durationInMillis > 0L }
    }

    suspend fun getOfflineDetail(title: String, start: Long, end: Long): OfflineActivityItem? = withContext(Dispatchers.Default) {
        val entities = repository.getRawOfflineActivitiesByTitleBetween(title, start, end)
        if (entities.isEmpty()) return@withContext null
        
        val allAppIntervals = repository.getAllIntervalsCached(start, end).values.flatten()
        val mergedApp = engine.mergeIntervals(allAppIntervals)
        
        val rawIntervals = entities.map { maxOf(start, it.timestamp) to minOf(end, it.timestamp + it.durationInMillis) }.filter { it.second > it.first }
        val mergedOffline = engine.mergeIntervals(rawIntervals)
        val shardedOffline = engine.subtractIntervals(mergedOffline, mergedApp)
        
        val totalDur = shardedOffline.sumOf { it.second - it.first }
        val minTs = shardedOffline.minOfOrNull { it.first } ?: start
        
        if (totalDur <= 0L) return@withContext null
        OfflineActivityItem(title, title, totalDur, minTs, entities.first().iconName, emptySet())
    }
    
    private suspend fun getShardedOfflineIntervals(start: Long, end: Long): List<Pair<Long, Long>> {
        val entities = repository.getRawOfflineActivitiesBetween(start, end)
        if (entities.isEmpty()) return emptyList()
        val rawOffline = entities.map { maxOf(start, it.timestamp) to minOf(end, it.timestamp + it.durationInMillis) }.filter { it.second > it.first }
        val mergedOffline = engine.mergeIntervals(rawOffline)
        val allAppIntervals = repository.getAllIntervalsCached(start, end).values.flatten()
        val mergedApp = engine.mergeIntervals(allAppIntervals)
        return engine.subtractIntervals(mergedOffline, mergedApp)
    }
    
    private suspend fun getShardedOfflineIntervalsForTitle(title: String, start: Long, end: Long): List<Pair<Long, Long>> {
        val entities = repository.getRawOfflineActivitiesByTitleBetween(title, start, end)
        if (entities.isEmpty()) return emptyList()
        val rawOffline = entities.map { maxOf(start, it.timestamp) to minOf(end, it.timestamp + it.durationInMillis) }.filter { it.second > it.first }
        val mergedOffline = engine.mergeIntervals(rawOffline)
        val allAppIntervals = repository.getAllIntervalsCached(start, end).values.flatten()
        val mergedApp = engine.mergeIntervals(allAppIntervals)
        return engine.subtractIntervals(mergedOffline, mergedApp)
    }

    suspend fun scanForGaps(start: Long, end: Long, hiddenPkgs: Set<String> = emptySet()): List<UsageGap> = withContext(Dispatchers.Default) {
        val allIntervalsMap = repository.getAllIntervalsCached(start - 86400000L, end + 86400000L)
        val appIntervals = repository.getFilteredIntervals(start, end, hiddenPkgs).values.flatten()
        val hiddenIntervals = allIntervalsMap.filterKeys { it in hiddenPkgs }.values.flatten()
        val mergedHidden = engine.mergeIntervals(hiddenIntervals)
        
        val offlineItems = repository.getRawOfflineActivitiesBetween(start, end).filter { it.durationInMillis > 0L }
        val offlineIntervals = offlineItems.map { it.timestamp to (it.timestamp + it.durationInMillis) }
        
        val combinedIntervals = appIntervals + offlineIntervals
        val allTracked = engine.mergeIntervals(combinedIntervals)
        
        val gaps = mutableListOf<UsageGap>()
        var current = start
        
        for (interval in allTracked) { 
            if (interval.first > current) {
                val hasHidden = mergedHidden.any { it.first < interval.first && it.second > current }
                gaps.add(UsageGap(current, interval.first, hasHidden))
            }
            current = maxOf(current, interval.second) 
        }
        if (end > current) {
            val hasHidden = mergedHidden.any { it.first < end && it.second > current }
            gaps.add(UsageGap(current, end, hasHidden))
        }
        
        val now = System.currentTimeMillis()
        gaps.filter { it.startMillis < now }.map { UsageGap(it.startMillis, minOf(it.endMillis, now), it.hasHiddenAppFootprint) }
    }

    suspend fun expandVoid(start: Long, end: Long, hiddenPkgs: Set<String>): Pair<Long, Long> = withContext(Dispatchers.Default) {
        val scanStart = start - (3L * 86400000L)
        val scanEnd = end + (3L * 86400000L)
        val appIntervals = repository.getFilteredIntervals(scanStart, scanEnd, hiddenPkgs).values.flatten()
        val offlineItems = repository.getRawOfflineActivitiesBetween(scanStart, scanEnd).filter { it.durationInMillis > 0L }
        val offlineIntervals = offlineItems.map { it.timestamp to (it.timestamp + it.durationInMillis) }
        val combined = engine.mergeIntervals(appIntervals + offlineIntervals)
        
        var trueStart = start
        var trueEnd = end
        
        val pre = combined.findLast { it.second <= start + 1000L }
        if (pre != null) trueStart = pre.second
        
        val post = combined.find { it.first >= end - 1000L }
        if (post != null) trueEnd = post.first
        
        trueStart to minOf(trueEnd, System.currentTimeMillis())
    }

    suspend fun expandActivityCascade(title: String, start: Long, end: Long): Pair<Long, Long> = withContext(Dispatchers.Default) {
        val all = repository.getRawOfflineActivitiesByTitleBetween(title, start - (7L * 86400000L), end + (7L * 86400000L)).sortedBy { it.timestamp }
        var minS = start
        var maxE = end
        var changed = true
        while (changed) {
            changed = false
            val prev = all.findLast { it.timestamp + it.durationInMillis >= minS - 1000L && it.timestamp < minS }
            if (prev != null) { minS = prev.timestamp; changed = true }
            val next = all.find { it.timestamp <= maxE + 1000L && it.timestamp + it.durationInMillis > maxE }
            if (next != null) { maxE = next.timestamp + next.durationInMillis; changed = true }
        }
        minS to maxE
    }

    suspend fun getAppSessions(packageName: String, start: Long, end: Long, clusterThresholdMins: Int): List<SessionItem> = withContext(Dispatchers.Default) {
        val clusterThreshold = clusterThresholdMins * 60 * 1000L
        val allIntervals = repository.getAllIntervalsCached(start, end)
        val merged = engine.mergeIntervals(allIntervals[packageName] ?: emptyList())
        if (merged.isEmpty()) return@withContext emptyList()
        
        val clustered = mutableListOf<SessionItem>()
        var cS = merged[0].first; var cE = merged[0].second; var cDur = cE - cS
        val currentRaw = mutableListOf(merged[0])
        
        for (i in 1 until merged.size) {
            val next = merged[i]
            val gapStart = cE
            val gapEnd = next.first
            val gapDur = gapEnd - gapStart
            
            if (gapDur <= clusterThreshold) {
                cE = next.second; cDur += (next.second - next.first)
                currentRaw.add(next)
            } else { 
                if (cDur > 0L) clustered.add(SessionItem(cS, cE, cDur, currentRaw.toList()))
                cS = next.first; cE = next.second; cDur = next.second - next.first
                currentRaw.clear(); currentRaw.add(next)
            }
        }
        if (cDur > 0L) clustered.add(SessionItem(cS, cE, cDur, currentRaw.toList()))
        clustered.sortedByDescending { it.startTime }
    }

    suspend fun getOfflineSessions(title: String, start: Long, end: Long, clusterThresholdMins: Int): List<SessionItem> = withContext(Dispatchers.Default) {
        val clusterThreshold = clusterThresholdMins * 60 * 1000L
        val shardedOffline = getShardedOfflineIntervalsForTitle(title, start, end)
        if (shardedOffline.isEmpty()) return@withContext emptyList()
        val merged = engine.mergeIntervals(shardedOffline)
        
        val clustered = mutableListOf<SessionItem>()
        var cS = merged[0].first; var cE = merged[0].second; var cDur = cE - cS
        val currentRaw = mutableListOf(merged[0])
        
        for (i in 1 until merged.size) {
            val next = merged[i]
            val gapStart = cE
            val gapEnd = next.first
            val gapDur = gapEnd - gapStart
            
            if (gapDur <= clusterThreshold) {
                cE = next.second; cDur += (next.second - next.first)
                currentRaw.add(next)
            } else { 
                if (cDur > 0L) clustered.add(SessionItem(cS, cE, cDur, currentRaw.toList()))
                cS = next.first; cE = next.second; cDur = next.second - next.first
                currentRaw.clear(); currentRaw.add(next)
            }
        }
        if (cDur > 0L) clustered.add(SessionItem(cS, cE, cDur, currentRaw.toList()))
        clustered.sortedByDescending { it.startTime }
    }

    private fun getHourLabel(hour: Int): String = when(hour) { 0 -> "12 AM"; 12 -> "12 PM"; else -> if(hour<12) "$hour AM" else "${hour-12} PM" }
    
    private suspend fun buildChartPoints(start: Long, range: TimeRangeLabel, appIntervals: List<Pair<Long, Long>>, offlineIntervals: List<Pair<Long, Long>>, mode: ChartMode): List<ChartPoint> = withContext(Dispatchers.Default) {
        val bucketType = if (range == TimeRangeLabel.DAY) Calendar.HOUR_OF_DAY else Calendar.DAY_OF_YEAR
        val bucketMillis = if (range == TimeRangeLabel.DAY) 3600000f else 86400000f
        val appBuckets = engine.sliceIntervals(appIntervals, bucketType)
        
        val currentOffList = mutableListOf<Pair<Long, Long>>()
        currentOffList.addAll(offlineIntervals)
        val clampedOfflineBuckets = engine.sliceIntervals(currentOffList, bucketType)

        val cal = Calendar.getInstance().apply { timeInMillis = start }
        val count = when(range) { TimeRangeLabel.DAY -> 24; TimeRangeLabel.WEEK -> 7; TimeRangeLabel.MONTH -> cal.getActualMaximum(Calendar.DAY_OF_MONTH) }
        val currentMillis = System.currentTimeMillis()
        val points = mutableListOf<ChartPoint>()
        
        for (i in 0 until count) {
            yield()
            val bucketStart = cal.timeInMillis
            val tempCal = cal.clone() as Calendar
            when (range) { 
                TimeRangeLabel.DAY -> tempCal.add(Calendar.HOUR_OF_DAY, 1)
                TimeRangeLabel.WEEK -> tempCal.add(Calendar.DAY_OF_YEAR, 1)
                TimeRangeLabel.MONTH -> tempCal.add(Calendar.DAY_OF_YEAR, 1) 
            }
            val bucketEnd = tempCal.timeInMillis
            val isCurrent = currentMillis in bucketStart until bucketEnd
            val isFuture = bucketStart > currentMillis
            val isNextFuture = isFuture && (bucketStart - currentMillis <= bucketMillis.toLong())

            val label: String; val fullLabel: String; val syncTag: String
            if (range == TimeRangeLabel.DAY) {
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                label = getHourLabel(hour)
                fullLabel = "${getHourLabel(hour)} - ${getHourLabel((hour+1)%24)}"
                syncTag = hour.toString()
            } else if (range == TimeRangeLabel.WEEK) {
                val dayStr = SimpleDateFormat("EEE", Locale.ENGLISH).format(cal.time)
                label = dayStr; fullLabel = dayStr; syncTag = dayStr
            } else {
                label = cal.get(Calendar.DAY_OF_MONTH).toString()
                val dateStr = SimpleDateFormat("MMM d", Locale.ENGLISH).format(cal.time)
                fullLabel = dateStr; syncTag = dateStr
            }

            cal.timeInMillis = bucketEnd
            val aTime = appBuckets[bucketStart] ?: 0L
            val oTime = clampedOfflineBuckets[bucketStart] ?: 0L
            val vals = mutableListOf<Float>()
            val segs = mutableListOf<String>()
            
            if (mode == ChartMode.DASHBOARD || mode == ChartMode.GAPS) { 
                vals.add((aTime / bucketMillis).coerceIn(0f, 1f)); vals.add((oTime / bucketMillis).coerceIn(0f, 1f))
                segs.add(TimeUtils.formatDuration(aTime)); segs.add(TimeUtils.formatDuration(oTime))
            } else if (mode == ChartMode.APP_USAGE || (mode == ChartMode.DETAIL && appIntervals.isNotEmpty())) { 
                vals.add((aTime / bucketMillis).coerceIn(0f, 1f)); segs.add(TimeUtils.formatDuration(aTime)) 
            } else { 
                vals.add((oTime / bucketMillis).coerceIn(0f, 1f)); segs.add(TimeUtils.formatDuration(oTime)) 
            }
            points.add(ChartPoint(label, vals, TimeUtils.formatDuration(aTime + oTime), segs, fullLabel, syncTag, isCurrent, isFuture, isNextFuture))
        }
        points
    }

    suspend fun getHourlyBreakdown(start: Long, end: Long, hiddenPkgs: Set<String>): List<ChartPoint> = withContext(Dispatchers.Default) {
        val mergedAppIntervals = engine.mergeIntervals(repository.getFilteredIntervals(start, end, hiddenPkgs).values.flatten())
        val shardedOfflineIntervals = getShardedOfflineIntervals(start, end)
        buildChartPoints(start, TimeRangeLabel.DAY, mergedAppIntervals, shardedOfflineIntervals, ChartMode.DASHBOARD)
    }
    
    suspend fun getDailyBreakdown(start: Long, end: Long, pattern: String, hiddenPkgs: Set<String>): List<ChartPoint> = withContext(Dispatchers.Default) { 
        val range = if (pattern == "EEE") TimeRangeLabel.WEEK else TimeRangeLabel.MONTH 
        val mergedAppIntervals = engine.mergeIntervals(repository.getFilteredIntervals(start, end, hiddenPkgs).values.flatten())
        val shardedOfflineIntervals = getShardedOfflineIntervals(start, end)
        buildChartPoints(start, range, mergedAppIntervals, shardedOfflineIntervals, ChartMode.DASHBOARD) 
    }
    
    suspend fun getActivityChartData(type: String, id: String, start: Long, end: Long, range: TimeRangeLabel): List<ChartPoint> = withContext(Dispatchers.Default) {
        if (type == "app") {
            val appIntervals = engine.mergeIntervals(repository.getAllIntervalsCached(start, end)[id] ?: emptyList())
            buildChartPoints(start, range, appIntervals, emptyList(), ChartMode.DETAIL)
        } else { 
            val shardedOfflineIntervals = getShardedOfflineIntervalsForTitle(id, start, end)
            buildChartPoints(start, range, emptyList(), shardedOfflineIntervals, ChartMode.DETAIL) 
        }
    }
    
    suspend fun getAppBreakdown(start: Long, end: Long, range: TimeRangeLabel, hiddenPkgs: Set<String>): List<ChartPoint> = withContext(Dispatchers.Default) { 
        val mergedAppIntervals = engine.mergeIntervals(repository.getFilteredIntervals(start, end, hiddenPkgs).values.flatten())
        buildChartPoints(start, range, mergedAppIntervals, emptyList(), ChartMode.APP_USAGE) 
    }
    
    suspend fun getOfflineBreakdown(start: Long, end: Long, range: TimeRangeLabel): List<ChartPoint> = withContext(Dispatchers.Default) { 
        val shardedOfflineIntervals = getShardedOfflineIntervals(start, end)
        buildChartPoints(start, range, emptyList(), shardedOfflineIntervals, ChartMode.OFFLINE) 
    }
}
