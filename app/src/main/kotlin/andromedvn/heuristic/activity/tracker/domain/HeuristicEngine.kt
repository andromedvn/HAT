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
    suspend fun getAppUsage(start: Long, end: Long, range: TimeRangeLabel, hiddenPkgs: Set<String>): Triple<Long, Long, List<AppUsageItem>> = withContext(Dispatchers.Default) {
        val intervalsMap = repository.getFilteredIntervals(start, end, hiddenPkgs)
        var totalAppExecution = 0L

        // Spans the user dismissed back into usage ("this really was usage")
        // merge into the per-app totals here, with millisecond precision.
        val dismissed = repository.storage.getDismissedScreenOffSpans(start, end)

        val apps = mutableListOf<AppUsageItem>()
        for ((pkg, intervals) in intervalsMap) {
            yield()
            val withDismissed = intervals + dismissed.filter { it.first == pkg }.map { it.second to it.third }
            val merged = engine.mergeIntervals(withDismissed)
            val total = merged.sumOf { it.second - it.first }

            totalAppExecution += total

            val latest = merged.maxOfOrNull { it.second } ?: start
            apps.add(AppUsageItem(pkg, repository.getAppName(pkg), total, latest, getSyncTagsForIntervals(merged, range)))
        }

        val validApps = apps.filter { it.durationInMillis > 0L }.sortedByDescending { it.durationInMillis }
        val trueDeviceIntervals = repository.getAllIntervalsCached(start, end).values.flatten()
        val trueDeviceScreenTime = engine.mergeIntervals(trueDeviceIntervals).sumOf { it.second - it.first }
        Triple(maxOf(trueDeviceScreenTime, 0L), maxOf(totalAppExecution, 0L), validApps)
    }

    /**
     * The Screen-Off Activity for the window: per-app totals of time the app
     * was resumed while the screen was off, with resolved spans removed and
     * the rest left for the user to name. Spans shorter than minSpanMillis
     * are dropped individually, so a sync app's micro-blips never flood the
     * section even when their sum crosses the floor. Apps are ordered by
     * their first qualifying span, so the list reads in day order.
     *
     * Resolution semantics, kept strictly one-way:
     *  - DISMISSED spans (stored in screen_off_dismissed) count as usage
     *    again; they are subtracted here and added back by getAppUsage.
     *  - LABELED spans are covered by an offline activity; any overlap with
     *    offline rows is subtracted here and NOT added back anywhere. The
     *    label owns that time now.
     */
    suspend fun getScreenOffActivity(start: Long, end: Long, minSpanMillis: Long): List<ScreenOffItem> = withContext(Dispatchers.Default) {
        val dismissed = repository.storage.getDismissedScreenOffSpans(start, end)
        val now = System.currentTimeMillis()
        val raw = engine.getScreenOffIntervals(start, end)
        val offlineCovered = mergeIntervals(
            repository.getRawOfflineActivitiesBetween(start, end)
                .mapNotNull { sanitizeTrackedRow(it.timestamp, it.durationInMillis, now) }
        )
        val out = mutableListOf<ScreenOffItem>()
        for ((pkg, spans) in raw) {
            yield()
            var kept = spans
            for ((dPkg, dStart, dEnd) in dismissed) {
                if (dPkg == pkg) {
                    // PREFIX rule (2026-10-23, the growing-span fix): a span
                    // still growing when the user answered it ("count it as
                    // usage" on pocket music) keeps extending its end, and
                    // subtracting only the dismissed interval let the grown
                    // tail resurface. A span starting at the same millisecond
                    // as a dismissal IS that span; the answer covers it
                    // however long it grows.
                    kept = kept.filterNot { it.first == dStart }
                    kept = engine.subtractIntervals(kept, listOf(dStart to dEnd))
                }
            }
            kept = engine.subtractIntervals(kept, offlineCovered)
            // Per-SPAN filtering, matching what the changelog promises and
            // what the user expects: a noisy sync app whose micro-blips sum
            // past the floor must not flood the section with "0m" cards.
            // The old per-app-total filter kept every blip of any app whose
            // TOTAL crossed the floor (regression audit, 2026-10-21).
            val merged = engine.mergeIntervals(kept).filter { (s, e) -> e - s >= minSpanMillis }
            if (merged.isEmpty()) continue
            val total = merged.sumOf { it.second - it.first }
            val earliest = merged.first().first
            out.add(ScreenOffItem(pkg, repository.getAppName(pkg), total, earliest, merged))
        }
        // Chronological by each app's first qualifying span, so the section
        // reads in day order (regression audit, 2026-10-21).
        out.sortedBy { it.timestamp }
    }

    suspend fun getAppDetail(packageName: String, start: Long, end: Long): AppUsageItem? = withContext(Dispatchers.Default) {
        val intervalsMap = repository.getAllIntervalsCached(start, end)
        val intervals = intervalsMap[packageName] ?: emptyList()
        // Dismissed screen-off spans are usage again; add them to this app's
        // intervals before totalling, mirroring getAppUsage.
        val dismissed = repository.storage.getDismissedScreenOffSpans(start, end).filter { it.first == packageName }.map { it.second to it.third }
        val merged = engine.mergeIntervals(intervals + dismissed)
        val total = merged.sumOf { it.second - it.first }

        if (total <= 0L) return@withContext null
        AppUsageItem(packageName, repository.getAppName(packageName), total, merged.maxOfOrNull { it.second } ?: start, emptySet())
    }

    suspend fun getOfflineActivities(start: Long, end: Long, range: TimeRangeLabel): List<OfflineActivityItem> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val entities = repository.getRawOfflineActivitiesBetween(start, end)
        if (entities.isEmpty()) return@withContext emptyList()

        val allAppIntervals = repository.getAllIntervalsCached(start, end).values.flatten()
        val mergedApp = engine.mergeIntervals(allAppIntervals)

        // Group by TITLE ALONE, never by title+icon.
        //
        // BUG (fixed 2026-09-30): grouping by `title to iconName` emitted one item
        // per (title, icon) pair. One label saved twice with two different icons
        // therefore produced two items that both carried the same `id` (the
        // title), so both rendered into the same LazyColumn slot and Compose threw
        // `IllegalArgumentException: Key "offline_sleep" was already used` the moment
        // the user tapped Total Offline Time. This stayed hidden for as long as the
        // offline read returned nothing at all (see OfflineOverlapSql).
        //
        // One title is one activity, so it must be one row. The icon is display
        // decoration: pick the most common one so the result is stable rather than
        // depending on row order.
        val grouped = entities.groupBy { it.title }
        grouped.map { (title, list) ->
            val icon = list.groupingBy { it.iconName }.eachCount().maxByOrNull { it.value }?.key
                ?: list.first().iconName
            val rawIntervals = list.mapNotNull { sanitizeOfflineRow(it.timestamp, it.durationInMillis, start, end, now) }
            val mergedOffline = engine.mergeIntervals(rawIntervals)
            val shardedOffline = engine.subtractIntervals(mergedOffline, mergedApp)
            val totalDuration = shardedOffline.sumOf { it.second - it.first }
                        val minTimestamp = shardedOffline.minOfOrNull { it.first } ?: start
                        val tags = getSyncTagsForIntervals(shardedOffline, range)

                        // Honest reporting (user decision 2026-10-01): if this one entry is backed by
                        // several stored rows, SAY SO on the entry rather than silently collapsing
                        // them or quietly deleting them. The count rides along with the item and the
                        // screen renders it inline.
                        val storedRowCount = list.size

                        OfflineActivityItem(title, title, totalDuration, minTimestamp, icon, tags, storedRowCount)
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
        // `current` still advances - GapScanTest). The end is capped at `now`
        // anyway, which is belt-and-braces here rather than a behaviour change:
        // the trailing `a < now` filter already removed every gap the runaway
        // end could have swallowed. What the guard does newly reject is a row
        // that starts in the future, which no legitimate label can do.
        val offlineIntervals = offlineItems.mapNotNull { sanitizeTrackedRow(it.timestamp, it.durationInMillis, now) }

        // Screen-Off Activity spans, minus anything the user already sent back
        // to usage. Kept separately so each gap can name what sat beside it.
        val dismissedSpans = repository.storage.getDismissedScreenOffSpans(start, end)
        var screenOffSpans = engine.getScreenOffIntervals(start, end).values.flatten()
        for ((_, dStart, dEnd) in dismissedSpans) screenOffSpans = engine.subtractIntervals(screenOffSpans, listOf(dStart to dEnd))

        // Labeled intervals, one display name per tracked span, for the gap
        // explainers. App intervals name the app; offline rows name the label
        // the user chose. Screen-off spans stay in the accounted set above so
        // they suppress gaps, but they are NOT gap neighbors by name: they
        // sit in their own section right above, with their own explainers.
        val labeled = mutableListOf<GapNeighbor>()
        for ((pkg, ivs) in repository.getFilteredIntervals(start, end, hiddenPkgs)) {
            // Pro's neighbor rule (r12r): system UI, the launcher and HAT
            // itself never name a gap neighbor. Going home is not app usage,
            // and "System UI" as a neighbor is noise.
            if (repository.getAppCategory(pkg) != ProcessCategory.STANDARD) continue
            for ((s, e) in ivs) labeled.add(GapNeighbor(repository.getAppName(pkg), s, e))
        }
        // Pair each label with ITS OWN sanitized interval, never with
        // offlineIntervals[i]: sanitization can drop rows (non-positive
        // duration, future start), and the index-paired version then
        // shifted every following title onto the wrong interval, making a
        // neighbor explainer name the wrong label (regression audit,
        // 2026-10-21). The accounted set above still uses the interval
        // list; this loop re-runs the same pure check per item so title
        // and interval can never drift apart.
        for (item in offlineItems) {
            val interval = sanitizeTrackedRow(item.timestamp, item.durationInMillis, now)
            if (interval != null) labeled.add(GapNeighbor(item.title, interval.first, interval.second))
        }
        for ((dPkg, dStart, dEnd) in dismissedSpans) labeled.add(GapNeighbor(repository.getAppName(dPkg), dStart, dEnd))

        // Dismissed screen-off spans are usage by the user's own answer ("it
        // was really in use"), the same as getAppUsage treats them. Without
        // this they vanished from the accounted set here and resurfaced as a
        // phantom gap card plus a chart bar.
        val dismissedAsUsage = dismissedSpans.map { it.second to it.third }
        val combinedIntervals = appIntervals + offlineIntervals + screenOffSpans + dismissedAsUsage
        val allTracked = engine.mergeIntervals(combinedIntervals)

        // Provable screen-off time as a neighbor (r12q, the never-silent
        // context rule): the device's dark windows, minus everything already
        // accounted (app sessions, labels, screen-off spans) and minus the
        // dismissed spans (they are named by their app above), so a dark
        // fragment is named only where nothing else sits.
        val darkOnly = mergeIntervals(
            subtractIntervals(
                subtractIntervals(repository.getDeviceScreenOffIntervals(start, end), allTracked),
                dismissedSpans.map { it.second to it.third }
            )
        )
        for ((ds, de) in darkOnly) labeled.add(GapNeighbor("The screen was off", ds, de, screenOff = true))

        // Nearest neighbor lookup for the explainers. A gap's "before" is the
        // latest named span ending at or before the gap's start; its "after"
        // is the earliest named span starting at or after the gap's end. When
        // several spans coalesced into one merged interval, the nearest real
        // named thing on each side is what the user experienced there.
        val byEnd = labeled.sortedByDescending { it.endMillis }
        val byStart = labeled.sortedBy { it.startMillis }

        fun nearestBefore(t: Long): GapNeighbor? {
            // Latest interval whose end is <= t (+1s: the walk's coalescing
            // tolerance, so a span ending exactly at the boundary counts).
            return byEnd.firstOrNull { it.endMillis <= t + 1000L }
        }
        fun nearestAfter(t: Long): GapNeighbor? {
            return byStart.firstOrNull { it.startMillis >= t - 1000L }
        }

        val gaps = mutableListOf<UsageGap>()
        var current = start

        for (interval in allTracked) {
            if (interval.first > current) {
                val hasHidden = mergedHidden.any { it.first < interval.first && it.second > current }
                gaps.add(UsageGap(current, interval.first, hasHidden, nearestBefore(current), nearestAfter(interval.first)))
            }
            current = maxOf(current, interval.second)
        }
        if (end > current) {
            val hasHidden = mergedHidden.any { it.first < end && it.second > current }
            // The trailing gap runs to the window edge: its "after" side is
            // the present, and the dialog says so (ongoing).
            gaps.add(UsageGap(current, end, hasHidden, nearestBefore(current), GapNeighbor("", end, end, ongoing = true)))
        }

        gaps.filter { it.startMillis < now }.map { UsageGap(it.startMillis, minOf(it.endMillis, now), it.hasHiddenAppFootprint, it.precededBy, it.followedBy) }
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
        // A day view is bucketed by the LOCAL day, which is 23 wall-clock
        // hours on a spring-forward day and 25 on a fall-back one. The old
        // hardcoded 24 walked past midnight on the former and never reached
        // 23:00 on the latter - see hourBucketsOfDay and DstClockChangeTest.
        // Week and month buckets are whole days and have no such boundary.
        val count = when(range) { TimeRangeLabel.DAY -> hourBucketsOfDay(start).size; TimeRangeLabel.WEEK -> 7; TimeRangeLabel.MONTH -> cal.getActualMaximum(Calendar.DAY_OF_MONTH) }
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
                // The bucket's true end hour, read from the advanced cursor:
                // (hour + 1) % 24 is WRONG on a spring-forward day, where the
                // 01:00 bucket ends at 03:00 because 02:00 never happens, and
                // on the first of the two 01:00 buckets of a fall-back day,
                // which ends at the second 01:00.
                val endHour = tempCal.get(Calendar.HOUR_OF_DAY)
                fullLabel = "${getHourLabel(hour)} - ${getHourLabel(endHour)}"
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
 * with `<=`, so a gap of exactly the threshold is bridged - see
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
 * from an 01:00 one - exactly the late-night habit the user does most often.
 */
fun circularHourDistance(hourOfDay: Int, otherHourOfDay: Int): Int =
    circularDialDistance(hourOfDay, otherHourOfDay, 24)

/**
 * Proximity weight for a suggestion [hoursApart] from the hour being labelled.
 * A score, not a duration - the distance it consumes is an exact Int count of
 * hours.
 */
fun hourProximityWeight(hoursApart: Int): Double =
    if (hoursApart == 0) 3.0 else if (hoursApart <= 2) 1.5 else 0.5

/**
 * `a + b` saturating at [Long.MAX_VALUE] instead of wrapping negative.
 *
 * A single vault row can carry a duration near Long.MAX_VALUE, and
 * `timestamp + duration` would then wrap to a negative end - which reads as
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
 * before their window - see GapScanTest: a leading interval can never emit a
 * gap, and `current` still advances. So this guard deliberately does NOT clamp
 * the start to a window. It only removes rows that cannot be real:
 *
 *  - a non-positive duration is rejected;
 *  - a row starting at or after [now] is rejected;
 *  - the add is saturating, so a duration near Long.MAX_VALUE cannot wrap the
 *    end to a negative value.
 *
 * [maxEnd] caps the end. Pass [Long.MAX_VALUE] to leave the end uncapped -
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
