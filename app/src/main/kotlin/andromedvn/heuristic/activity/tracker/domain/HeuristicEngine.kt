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

    /**
     * Returns `Triple(trueDeviceScreenTime, totalAppExecution, validApps)`.
     *
     * Accounting invariants, deliberately NOT enforced by clamping:
     *
     * - [trueDeviceScreenTime] is the merged duration of **all** app intervals,
     *   hidden included. It is the device's real foreground time.
     * - [totalAppExecution] is the sum of **per-app** durations over non-hidden
     *   packages. Because it is a per-app sum it is **not** mutually exclusive
     *   with [trueDeviceScreenTime]: on split-screen, or when a package
     *   re-resumes, two packages can have overlapping intervals, so the sum can
     *   legitimately exceed the merged total.
     * - Hidden app time appears in neither [totalAppExecution] (filtered out) nor
     *   in sharded offline time (subtracted as real usage). It is erased by
     *   design; `scanForGaps` marks the affected gaps with
     *   `hasHiddenAppFootprint` so the gap list can explain them.
     *
     * Consumers must treat [totalAppExecution] as a per-app figure, not a device
     * figure. Clamping it to [trueDeviceScreenTime] would silently hide real
     * split-screen overlap.
     */
    suspend fun getAppUsage(start: Long, end: Long, range: TimeRangeLabel, ghostMins: Int, hiddenPkgs: Set<String>, ackGhosts: List<AcknowledgedGhost>): Triple<Long, Long, List<AppUsageItem>> = withContext(Dispatchers.Default) {
        val ghostMillis = ghostThresholdMillis(ghostMins)
        val intervalsMap = repository.getFilteredIntervals(start, end, hiddenPkgs)
        val rawGhosts = engine.getGhostCandidateIntervals(start, end)
        var totalAppExecution = 0L

        val apps = mutableListOf<AppUsageItem>()
        for ((pkg, intervals) in intervalsMap) {
            yield()
            val merged = engine.mergeIntervals(intervals)
            val total = merged.sumOf { it.second - it.first }

            val pkgGhosts = selectGhostGapsForPackage(rawGhosts, pkg, ghostMillis, ackGhosts)

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
        val ghostMillis = ghostThresholdMillis(ghostMins)
        val intervalsMap = repository.getAllIntervalsCached(start, end)
        val intervals = intervalsMap[packageName] ?: emptyList()
        val merged = engine.mergeIntervals(intervals)
        val total = merged.sumOf { it.second - it.first }
        val rawGhosts = engine.getGhostCandidateIntervals(start, end)

        val pkgGhosts = selectGhostGapsForPackage(rawGhosts, packageName, ghostMillis, ackGhosts)

        if (total <= 0L && pkgGhosts.isEmpty()) return@withContext null
        AppUsageItem(packageName, repository.getAppName(packageName), total, merged.maxOfOrNull { it.second } ?: start, emptySet(), ghostIntervals = pkgGhosts)
    }

    suspend fun getOfflineActivities(start: Long, end: Long, range: TimeRangeLabel): List<OfflineActivityItem> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val entities = repository.getRawOfflineActivitiesBetween(start, end)
        if (entities.isEmpty()) return@withContext emptyList()

        val allAppIntervals = repository.getAllIntervalsCached(start, end).values.flatten()
        val mergedApp = engine.mergeIntervals(allAppIntervals)

        val grouped = entities.groupBy { it.title to it.iconName }
        grouped.map { (key, list) ->
            val (title, icon) = key
            val rawIntervals = list.mapNotNull { sanitizeOfflineRow(it.timestamp, it.durationInMillis, start, end, now) }
            val mergedOffline = engine.mergeIntervals(rawIntervals)
            val shardedOffline = engine.subtractIntervals(mergedOffline, mergedApp)
            val totalDuration = shardedOffline.sumOf { it.second - it.first }
            val minTimestamp = shardedOffline.minOfOrNull { it.first } ?: start
            val tags = getSyncTagsForIntervals(shardedOffline, range)
            
            OfflineActivityItem(title, title, totalDuration, minTimestamp, icon, tags) 
        }.filter { it.durationInMillis > 0L }
    }

    suspend fun getOfflineDetail(title: String, start: Long, end: Long): OfflineActivityItem? = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val entities = repository.getRawOfflineActivitiesByTitleBetween(title, start, end)
        if (entities.isEmpty()) return@withContext null

        val allAppIntervals = repository.getAllIntervalsCached(start, end).values.flatten()
        val mergedApp = engine.mergeIntervals(allAppIntervals)

        val rawIntervals = entities.mapNotNull { sanitizeOfflineRow(it.timestamp, it.durationInMillis, start, end, now) }
        val mergedOffline = engine.mergeIntervals(rawIntervals)
        val shardedOffline = engine.subtractIntervals(mergedOffline, mergedApp)
        
        val totalDur = shardedOffline.sumOf { it.second - it.first }
        val minTs = shardedOffline.minOfOrNull { it.first } ?: start
        
        if (totalDur <= 0L) return@withContext null
        OfflineActivityItem(title, title, totalDur, minTs, entities.first().iconName, emptySet())
    }
    
    suspend fun getShardedOfflineIntervals(start: Long, end: Long): List<Pair<Long, Long>> {
        val now = System.currentTimeMillis()
        val entities = repository.getRawOfflineActivitiesBetween(start, end)
        if (entities.isEmpty()) return emptyList()
        val rawOffline = entities.mapNotNull { sanitizeOfflineRow(it.timestamp, it.durationInMillis, start, end, now) }
        val mergedOffline = engine.mergeIntervals(rawOffline)
        val allAppIntervals = repository.getAllIntervalsCached(start, end).values.flatten()
        val mergedApp = engine.mergeIntervals(allAppIntervals)
        return engine.subtractIntervals(mergedOffline, mergedApp)
    }
    
    private suspend fun getShardedOfflineIntervalsForTitle(title: String, start: Long, end: Long): List<Pair<Long, Long>> {
        val now = System.currentTimeMillis()
        val entities = repository.getRawOfflineActivitiesByTitleBetween(title, start, end)
        if (entities.isEmpty()) return emptyList()
        val rawOffline = entities.mapNotNull { sanitizeOfflineRow(it.timestamp, it.durationInMillis, start, end, now) }
        val mergedOffline = engine.mergeIntervals(rawOffline)
        val allAppIntervals = repository.getAllIntervalsCached(start, end).values.flatten()
        val mergedApp = engine.mergeIntervals(allAppIntervals)
        return engine.subtractIntervals(mergedOffline, mergedApp)
    }

    suspend fun scanForGaps(start: Long, end: Long, hiddenPkgs: Set<String> = emptySet()): List<UsageGap> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val allIntervalsMap = repository.getAllIntervalsCached(start - 86400000L, end + 86400000L)
        val appIntervals = repository.getFilteredIntervals(start, end, hiddenPkgs).values.flatten()
        val hiddenIntervals = allIntervalsMap.filterKeys { it in hiddenPkgs }.values.flatten()
        val mergedHidden = engine.mergeIntervals(hiddenIntervals)

        val offlineItems = repository.getRawOfflineActivitiesBetween(start, end)
        // Sanitised, NOT clamped to the window: the walk below already tolerates
        // a row that begins before `start` (it cannot emit a leading gap, and
        // `current` still advances — GapScanTest). The end is capped at `now`
        // anyway, which is belt-and-braces here rather than a behaviour change:
        // the trailing `a < now` filter already removed every gap the runaway
        // end could have swallowed. What the guard does newly reject is a row
        // that starts in the future, which no legitimate label can do.
        val offlineIntervals = offlineItems.mapNotNull { sanitizeTrackedRow(it.timestamp, it.durationInMillis, now) }
        
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

        gaps.filter { it.startMillis < now }.map { UsageGap(it.startMillis, minOf(it.endMillis, now), it.hasHiddenAppFootprint) }
    }

    suspend fun expandVoid(start: Long, end: Long, hiddenPkgs: Set<String>): Pair<Long, Long> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val scanStart = start - (3L * 86400000L)
        val scanEnd = end + (3L * 86400000L)
        val appIntervals = repository.getFilteredIntervals(scanStart, scanEnd, hiddenPkgs).values.flatten()
        val offlineItems = repository.getRawOfflineActivitiesBetween(scanStart, scanEnd)
        // `maxEnd = Long.MAX_VALUE`: this walk's "what precedes the gap" lookup
        // selects on `interval.second <= start + 1000`, so truncating a corrupt
        // row's end to `now` would let it masquerade as a real activity ending
        // where the gap begins. The end is left alone; the row is still
        // rejected if its duration is non-positive or it starts in the future.
        val offlineIntervals = offlineItems.mapNotNull {
            sanitizeTrackedRow(it.timestamp, it.durationInMillis, now, maxEnd = Long.MAX_VALUE)
        }
        val combined = engine.mergeIntervals(appIntervals + offlineIntervals)
        
        var trueStart = start
        var trueEnd = end
        
        val pre = combined.findLast { it.second <= start + 1000L }
        if (pre != null) trueStart = pre.second
        
        val post = combined.find { it.first >= end - 1000L }
        if (post != null) trueEnd = post.first
        
        trueStart to minOf(trueEnd, now)
    }

    suspend fun expandActivityCascade(title: String, start: Long, end: Long): Pair<Long, Long> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val rows = repository.getRawOfflineActivitiesByTitleBetween(title, start - (7L * 86400000L), end + (7L * 86400000L)).sortedBy { it.timestamp }
        // Sanitised up front so the `timestamp + duration` the loop evaluates
        // four times per row can neither wrap negative nor run into the
        // future. Unsanitised, a single corrupt row could push `maxE` years
        // ahead; the caller then offers that window to the user as a
        // contiguous block, and saving it writes an offline row that inflates
        // every day it touches. Capping at `now` only: a legitimately long
        // label (a fortnight of "Sick leave") is still honoured in full,
        // because no real label ends in the future.
        val all = rows.mapNotNull { sanitizeTrackedRow(it.timestamp, it.durationInMillis, now) }
        var minS = start
        var maxE = end
        var changed = true
        while (changed) {
            changed = false
            val prev = all.findLast { it.second >= minS - 1000L && it.first < minS }
            if (prev != null) { minS = prev.first; changed = true }
            val next = all.find { it.first <= maxE + 1000L && it.second > maxE }
            if (next != null) { maxE = next.second; changed = true }
        }
        minS to maxE
    }

    suspend fun getAppSessions(packageName: String, start: Long, end: Long, clusterThresholdMins: Int): List<SessionItem> = withContext(Dispatchers.Default) {
        // `* 60L` promotes to Long before the multiply. Writing `* 60` would
        // evaluate as Int*Int and overflow once the setting exceeds ~35k minutes.
        val clusterThreshold = minutesToMillisExact(clusterThresholdMins)
        val allIntervals = repository.getAllIntervalsCached(start, end)
        val merged = engine.mergeIntervals(allIntervals[packageName] ?: emptyList())

        clusterSessions(merged, clusterThreshold).sortedByDescending { it.startTime }
    }

    suspend fun getOfflineSessions(title: String, start: Long, end: Long, clusterThresholdMins: Int): List<SessionItem> = withContext(Dispatchers.Default) {
        val clusterThreshold = minutesToMillisExact(clusterThresholdMins)
        val shardedOffline = getShardedOfflineIntervalsForTitle(title, start, end)
        val merged = engine.mergeIntervals(shardedOffline)

        clusterSessions(merged, clusterThreshold).sortedByDescending { it.startTime }
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

// ============================================================================
// Pure helpers extracted from the suspend functions above so that the time
// arithmetic they depend on is testable off-device. No Context, no clock,
// no Android types in any of these bodies.
// ============================================================================

/**
 * Groups a MERGED, sorted, non-touching interval set into sessions, bridging any
 * internal gap of at most [clusterThreshold] millis.
 *
 * The single implementation behind both [HeuristicEngine.getAppSessions] and
 * [HeuristicEngine.getOfflineSessions]: the two loops were byte-for-byte
 * identical and only the *source* of `merged` differed (per-package app
 * intervals vs sharded offline for one title), which the callers still supply.
 *
 * Exact integer arithmetic throughout, no rounding. The threshold is compared
 * with `<=`, so a gap of exactly the threshold is bridged — see
 * ClusteringThresholdBoundaryTest. `activeDuration` is the sum of the real
 * sub-intervals and EXCLUDES the bridged gaps, so it is strictly less than
 * `endTime - startTime` whenever a cluster has an internal gap; a cluster with
 * no internal gap has the two equal.
 *
 * Not sorted: callers apply `sortedByDescending { it.startTime }`.
 */
fun clusterSessions(merged: List<Interval>, clusterThreshold: Long): List<SessionItem> {
    if (merged.isEmpty()) return emptyList()
    val clustered = mutableListOf<SessionItem>()
    var cS = merged[0].first; var cE = merged[0].second; var cDur = cE - cS
    val currentRaw = mutableListOf(merged[0])

    for (i in 1 until merged.size) {
        val next = merged[i]
        val gapDur = next.first - cE

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
    return clustered
}

/** [minutes] as exact milliseconds. The `60_000L` keeps the multiply in Long. */
fun minutesToMillisExact(minutes: Int): Long = minutes.toLong() * 60_000L

/**
 * The ghost minimum-duration setting, in exact milliseconds.
 *
 * 0 (or a negative value the settings UI cannot produce) means "no minimum",
 * so it must yield 0L — the lowest possible threshold, which every ghost
 * span satisfies.
 *
 * It used to yield `Long.MAX_VALUE`, which is the one value NO finite duration
 * can reach: `duration >= Long.MAX_VALUE` is false for every real span, so a
 * user who set the minimum to 0 was shown an empty ghost list. Zero is the
 * setting that should show the MOST, and it showed the least.
 *
 * A large positive setting is still honoured exactly: [Int.MAX_VALUE] minutes
 * is 128_849_018_820_000 ms, comfortably inside Long and unreachable by any
 * span, so an absurd threshold still returns nothing.
 */
fun ghostThresholdMillis(ghostMins: Int): Long =
    if (ghostMins <= 0) 0L else minutesToMillisExact(ghostMins)

/**
 * The ghost spans offered for [packageName] under a [thresholdMillis] minimum,
 * minus any the user has already acknowledged.
 *
 * Acknowledgement is an exact three-field match (package, start, end) on
 * purpose — see ClusteringThresholdBoundaryTest.
 */
fun selectGhostGapsForPackage(
    rawGhosts: List<Triple<String, Long, Long>>,
    packageName: String,
    thresholdMillis: Long,
    acknowledged: List<AcknowledgedGhost>
): List<UsageGap> = rawGhosts
    .filter { it.first == packageName }
    .filter { (it.third - it.second) >= thresholdMillis }
    .filterNot { g -> acknowledged.any { it.packageName == packageName && it.startMillis == g.second && it.endMillis == g.third } }
    .map { UsageGap(it.second, it.third, false) }

/**
 * Distance between two positions on a dial of [dialSize] units, measured the
 * SHORT way round: 0 for the same position, at most `dialSize / 2` otherwise.
 *
 * Inputs are normalised with [Math.floorMod], so an out-of-range value (a
 * caller passing -1 or 25) is wrapped rather than producing a negative
 * distance.
 */
fun circularDialDistance(a: Int, b: Int, dialSize: Int): Int {
    require(dialSize > 0) { "dialSize must be positive, was $dialSize" }
    val na = Math.floorMod(a, dialSize)
    val nb = Math.floorMod(b, dialSize)
    val raw = if (na > nb) na - nb else nb - na
    return if (raw < dialSize - raw) raw else dialSize - raw
}

/**
 * Hours between two hours of the day, the short way around the clock.
 *
 * 23:00 and 01:00 are 2 hours apart, not 22. 00:30 and 23:30 are 1 hour
 * apart. The same hour is always 0.
 *
 * Used by the suggestion weighting in
 * `ActivityRepository.getSmartSuggestions`, which compared raw
 * `abs(hour - targetHour)` and so scored a 23:00 habit as maximally distant
 * from an 01:00 one — exactly the late-night habit the user does most often.
 */
fun circularHourDistance(hourOfDay: Int, otherHourOfDay: Int): Int =
    circularDialDistance(hourOfDay, otherHourOfDay, 24)

/**
 * Proximity weight for a suggestion [hoursApart] from the hour being labelled.
 * A score, not a duration — the distance it consumes is an exact Int count of
 * hours.
 */
fun hourProximityWeight(hoursApart: Int): Double =
    if (hoursApart == 0) 3.0 else if (hoursApart <= 2) 1.5 else 0.5

/**
 * `a + b` saturating at [Long.MAX_VALUE] instead of wrapping negative.
 *
 * A single vault row can carry a duration near Long.MAX_VALUE, and
 * `timestamp + duration` would then wrap to a negative end — which reads as
 * "ends before it starts" and silently discards the row (or, worse, trims
 * another interval against it).
 */
private fun saturatingAddMillis(a: Long, b: Long): Long {
    if (b > 0L && a > Long.MAX_VALUE - b) return Long.MAX_VALUE
    if (b < 0L && a < Long.MIN_VALUE - b) return Long.MIN_VALUE
    return a + b
}

/**
 * A stored offline row, as a valid interval inside `[windowStart, windowEnd]`,
 * or null if the row cannot be trusted.
 *
 * Rows arrive from outside the code that is meant to guarantee they are
 * well-formed: `OfflineStorage.mergeDatabase` imports them from a
 * user-supplied vault file with a temporal-overlap check and no ordering or
 * plausibility check at all. Three guards, each of which a plain
 * `maxOf(start, ts) to minOf(end, ts + dur)` lacks:
 *
 *  - a non-positive duration is rejected. The clamp alone drops it too
 *    (`end > start` fails), but only as a side effect; here it is explicit and
 *    it happens before any arithmetic on the duration.
 *  - the end is capped at [now] as well as [windowEnd]. A row whose
 *    timestamp/duration pair runs past the present can otherwise claim a whole
 *    day, or a whole year, of time that has not happened.
 *  - a row starting at or after [now] is rejected outright: it describes a
 *    window that has not begun, so there is nothing to clamp.
 *
 * Clamping can only ever SHRINK a row, so it cannot break the day invariant
 * (app + offline <= length of day); every rejected or truncated row moves the
 * total towards zero, never past the day.
 */
fun sanitizeOfflineRow(
    timestamp: Long,
    durationInMillis: Long,
    windowStart: Long,
    windowEnd: Long,
    now: Long
): Interval? {
    if (durationInMillis <= 0L) return null
    val ceiling = if (windowEnd < now) windowEnd else now
    if (ceiling <= windowStart) return null
    val start = if (timestamp > windowStart) timestamp else windowStart
    val end = minOf(saturatingAddMillis(timestamp, durationInMillis), ceiling)
    return if (end > start) start to end else null
}

/**
 * A stored offline row for the walk-based paths (the gap scan, the label
 * cascade, the void expansion), which are already tolerant of rows that begin
 * before their window — see GapScanTest: a leading interval can never emit a
 * gap, and `current` still advances. So this guard deliberately does NOT clamp
 * the start to a window. It only removes rows that cannot be real:
 *
 *  - a non-positive duration is rejected;
 *  - a row starting at or after [now] is rejected;
 *  - the add is saturating, so a duration near Long.MAX_VALUE cannot wrap the
 *    end to a negative value.
 *
 * [maxEnd] caps the end. Pass [Long.MAX_VALUE] to leave the end uncapped —
 * required by `expandVoid`, whose "what activity precedes this gap" lookup
 * selects on `interval.second <= start + 1000` and would otherwise start
 * matching a corrupt row that has been truncated to [now], mistaking it for a
 * real activity that ends where the gap begins.
 */
fun sanitizeTrackedRow(
    timestamp: Long,
    durationInMillis: Long,
    now: Long,
    maxEnd: Long = now
): Interval? {
    if (durationInMillis <= 0L) return null
    if (timestamp >= now) return null
    val end = minOf(saturatingAddMillis(timestamp, durationInMillis), maxEnd)
    return if (end > timestamp) timestamp to end else null
}
