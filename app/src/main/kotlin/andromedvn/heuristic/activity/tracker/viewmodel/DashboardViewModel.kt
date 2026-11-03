package andromedvn.heuristic.activity.tracker.viewmodel

import android.content.Context
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import andromedvn.heuristic.activity.tracker.data.*
import andromedvn.heuristic.activity.tracker.domain.DetailLevel
import andromedvn.heuristic.activity.tracker.domain.HeuristicEngine
import andromedvn.heuristic.activity.tracker.domain.hourBucketsOfDay
import andromedvn.heuristic.activity.tracker.ui.components.ChartPoint
import andromedvn.heuristic.activity.tracker.ui.components.HighlightEvent
import andromedvn.heuristic.activity.tracker.utils.TimeUtils
import andromedvn.heuristic.activity.tracker.domain.PresenceClassifier
import andromedvn.heuristic.activity.tracker.utils.UsageStatsEngine
import andromedvn.heuristic.activity.tracker.utils.HatLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DismissedSession(val packageName: String, val appName: String, val startMillis: Long, val endMillis: Long)

data class DashboardUiState(
    val timeRange: TimeRangeLabel = TimeRangeLabel.DAY, val dateLabelRelative: String = "Today", val dateLabelExact: String = "", val startTime: Long = 0L, val endTime: Long = 0L,
    val hasNextPeriod: Boolean = false, val hasPreviousPeriod: Boolean = true, val totalScreenTimeFormatted: String = "0m", val totalAppExecutionFormatted: String = "0m", 
    val totalOfflineTimeFormatted: String = "0m", val totalCombinedTimeFormatted: String = "0m", val totalScreenTimeMillis: Long = 0L,
    val activityList: List<UnifiedActivity> = emptyList(), val appDetailChartData: List<ChartPoint> = emptyList(), val offlineDetailChartData: List<ChartPoint> = emptyList(), 
    val majorGaps: List<UsageGap> = emptyList(),
    val dismissedScreenOffSpans: List<DismissedSession> = emptyList(), val chartData: List<ChartPoint> = emptyList(), val gapChartData: List<ChartPoint> = emptyList(),
    val screenOffItems: List<ScreenOffItem> = emptyList(), val totalScreenOffMillis: Long = 0L,
    val recentSuggestions: List<String> = emptyList(),
    val chartHighlight: HighlightEvent? = null, val isLoading: Boolean = false, val isStalled: Boolean = false, val isDataReliable: Boolean = true, val osRetentionDays: Int = 7,
    /**
     * False when the last load threw. An empty gap list then means "HAT could not read
     * your data", NOT "you have no gaps". The UI must never claim your timeline is
     * fully tracked on the strength of a failed query.
     */
    val loadSucceeded: Boolean = true, 
    val returnScrollIndex: Int = -1, val returnScrollOffset: Int = -1, val lastUpdatedTimeStr: String = "",
    val oldestDataTimestamp: Long = 0L, val animationSalt: String = java.util.UUID.randomUUID().toString(),
    val hasSeenHistoryLimitWarning: Boolean = false,
    val bypassHistoryLimit: Boolean = false
)

class DashboardViewModel(private val repository: ActivityRepository, private val heuristicEngine: HeuristicEngine, private val context: Context) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()
    
    private val stateCache = LruCache<String, DashboardUiState>(7)
    
    private var selectedDayCal = Calendar.getInstance()
    private var selectedWeekCal = Calendar.getInstance()
    private var selectedMonthCal = Calendar.getInstance()
    
    private var currentSortType = SortType.RECENT
    val currentSortTypeValue: SortType get() = currentSortType 
    private var lastSystemDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    private var loadJob: Job? = null
    
    private var isFirstLoadComplete = false

    init { 
        HatLogger.log("DashboardViewModel: Initialized FOSS")
        viewModelScope.launch { 
            repository.settings.collect { settings -> 
                val needsRefresh = currentSortType != settings.sortType || _uiState.value.bypassHistoryLimit != settings.bypassHistoryLimit
                _uiState.value = _uiState.value.copy(
                    hasSeenHistoryLimitWarning = settings.hasSeenHistoryLimitWarning,
                    bypassHistoryLimit = settings.bypassHistoryLimit
                )
                if (needsRefresh) { currentSortType = settings.sortType; loadDashboardData(force = true, isBackgroundTick = false) } 
            } 
        }
        loadDashboardData(force = true, isBackgroundTick = false) 
    }

    fun triggerBackgroundArchiveSync() = viewModelScope.launch { repository.syncArchive() }

    /**
     * Presence evidence for gap cards (2026-11-02, presence arena composite):
     * classifies each gap from the persisted raw event stream and returns a
     * factual card line, or null when the evidence is undecided (which is
     * the honest answer for every gap older than the raw-event field).
     * Charter rules: facts only, past tense, no em dashes.
     */
    data class PresenceCopy(val line: String, val evidence: String? = null)

    suspend fun presenceVerdicts(gaps: List<Pair<Long, Long>>): Map<Long, PresenceCopy> = withContext(Dispatchers.IO) {
        if (gaps.isEmpty()) return@withContext emptyMap()
        val minStart = gaps.minOf { it.first }
        val maxEnd = gaps.maxOf { it.second }
        val timeoutMs = try {
            android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_OFF_TIMEOUT
            ).toLong()
        } catch (e: Exception) { 60_000L }
        val events = repository.storage.loadRawEvents(minStart - PresenceClassifier.PRE_WINDOW_MS, maxEnd + PresenceClassifier.POST_LOOKAHEAD_MS)
        if (events.isEmpty()) return@withContext emptyMap()
        val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        val out = HashMap<Long, PresenceCopy>(gaps.size)
        for ((start, end) in gaps) {
            val verdict = PresenceClassifier.classify(events, start, end, timeoutMs)
            val copy = when (verdict.kind) {
                PresenceClassifier.Kind.ANCHORED -> PresenceCopy(
                    "You were using the phone during this time.",
                    verdict.anchorTs?.let { "First use at ${fmt.format(java.util.Date(it))}" })
                PresenceClassifier.Kind.CHOSE_TO_STOP -> PresenceCopy(
                    "You locked the phone yourself.",
                    verdict.anchorTs?.let { "Locked at ${fmt.format(java.util.Date(it))}" })
                PresenceClassifier.Kind.TIMEOUT -> PresenceCopy(
                    // Guarded copy (arena catch): the phone sat only if a
                    // return was actually recorded; an open gap claims less.
                    if (verdict.resolveTs != null) "The screen timed out on its own. The phone sat until you came back."
                    else "The screen timed out on its own.",
                    verdict.resolveTs?.let { "Unlocked at ${fmt.format(java.util.Date(it))}" })
                PresenceClassifier.Kind.MEDIA_CONTINUED -> PresenceCopy(
                    "This app kept running through the quiet.",
                    verdict.resolveTs?.let { "Screen back on at ${fmt.format(java.util.Date(it))}" })
                PresenceClassifier.Kind.UNDECIDED -> null
            }
            if (copy != null) out[start] = copy
        }
        out
    }
    fun setChartHighlight(event: HighlightEvent?) { _uiState.value = _uiState.value.copy(chartHighlight = event) }
    
    fun markHistoryLimitWarningSeen() = viewModelScope.launch {
        val currentSettings = repository.settings.first()
        repository.storage.saveSettings(currentSettings.copy(hasSeenHistoryLimitWarning = true))
    }

    /** The spotlight tour ended (completed or skipped): never auto-run again. */
    fun setTimeRange(range: TimeRangeLabel) { 
        if (_uiState.value.timeRange == range) return
        HatLogger.log("DashboardViewModel: Time range changed to $range")
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(timeRange = range, chartHighlight = null)
            loadDashboardData(force = false, isBackgroundTick = false)
        }
    }
    
    fun saveScrollState(index: Int, offset: Int) { _uiState.value = _uiState.value.copy(returnScrollIndex = index, returnScrollOffset = offset) }
    fun clearScrollState() { _uiState.value = _uiState.value.copy(returnScrollIndex = -1, returnScrollOffset = -1) }
    fun loadSuggestionsForHour(hour: Int) { viewModelScope.launch { _uiState.value = _uiState.value.copy(recentSuggestions = repository.getSmartSuggestions(hour)) } }

    fun jumpToDate(timeInMillis: Long) {
        HatLogger.log("DashboardViewModel: Jumping to exact date")
        _uiState.value = _uiState.value.copy(chartHighlight = null)
        val cal = Calendar.getInstance().apply { this.timeInMillis = timeInMillis }
        selectedDayCal = cal.clone() as Calendar
        selectedWeekCal = cal.clone() as Calendar
        selectedMonthCal = cal.clone() as Calendar
        loadDashboardData(force = false, isBackgroundTick = false)
    }

    fun loadDashboardData(force: Boolean = false, isBackgroundTick: Boolean = false) {
        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (currentDay != lastSystemDay) { 
            selectedDayCal = Calendar.getInstance(); selectedWeekCal = Calendar.getInstance(); selectedMonthCal = Calendar.getInstance()
            lastSystemDay = currentDay
            stateCache.evictAll() 
        }
        
        HatLogger.log("DashboardViewModel: Loading data for ${_uiState.value.timeRange}")
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val range = _uiState.value.timeRange
            val activeCal = when (range) {
                TimeRangeLabel.DAY -> selectedDayCal
                TimeRangeLabel.WEEK -> selectedWeekCal
                TimeRangeLabel.MONTH -> selectedMonthCal
            }
            
            val start: Long
            val end: Long
            // Captured BEFORE any suspension point: activeCal is a shared,
            // mutable Calendar that day navigation can move mid-load. Reading
            // it again later (gap chart buckets) could pair one day's buckets
            // with another day's cards.
            val dayStart: Long
            when (range) {
                TimeRangeLabel.DAY -> { start = getStartOfDay(activeCal); end = getEndOfDay(activeCal); dayStart = start }
                TimeRangeLabel.WEEK -> { val cal = activeCal.clone() as Calendar; cal.firstDayOfWeek = Calendar.MONDAY; while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) cal.add(Calendar.DAY_OF_YEAR, -1); start = getStartOfDay(cal); cal.add(Calendar.DAY_OF_WEEK, 6); end = getEndOfDay(cal); dayStart = getStartOfDay(activeCal) }
                TimeRangeLabel.MONTH -> { val cal = activeCal.clone() as Calendar; cal.set(Calendar.DAY_OF_MONTH, 1); start = getStartOfDay(cal); cal.add(Calendar.MONTH, 1); cal.add(Calendar.DAY_OF_MONTH, -1); end = getEndOfDay(cal); dayStart = getStartOfDay(activeCal) }
            }

            val cacheKey = "${range.name}_${start}_${currentSortType}"

            if (force && !isBackgroundTick) {
                stateCache.evictAll()
                repository.forceInvalidateCache()
                _uiState.value = _uiState.value.copy(
                    activityList = emptyList(),
                    chartData = emptyList(),
                    gapChartData = emptyList(),
                    appDetailChartData = emptyList(),
                    offlineDetailChartData = emptyList(),
                    majorGaps = emptyList(),
                    dismissedScreenOffSpans = emptyList(),
                    chartHighlight = null,
                    animationSalt = java.util.UUID.randomUUID().toString(),
                                        isLoading = true,
                                        // An empty list during a forced reload must not read as "nothing to show".
                                        loadSucceeded = false
                                    )
                                } else if (!force) {
                val cached = stateCache.get(cacheKey)
                if (cached != null) {
                    HatLogger.log("DashboardViewModel: Instantly rendering from Memory Matrix Cache ($cacheKey)")
                    _uiState.value = cached.copy(
                        chartHighlight = null,
                        returnScrollIndex = -1,
                        returnScrollOffset = 0,
                        animationSalt = _uiState.value.animationSalt 
                    )
                    return@launch
                }
            }

            var loadFinished = false
                        _uiState.value = _uiState.value.copy(isStalled = false)

                        // HONESTY FIX (2026-10-01): this launch block had no catch at all. A query
                        // that threw killed the coroutine, leaving every list empty, and the gap
                        // screen then rendered "Your timeline is fully tracked!" - celebrating a
                        // failure as success. Wrap the body so a failure is reported as a failure.
                        try {
                        val spinnerJob = launch {
                delay(150)
                if (!loadFinished && !isBackgroundTick) _uiState.value = _uiState.value.copy(isLoading = true)
                delay(4850)
                if (!loadFinished && !isBackgroundTick) {
                    _uiState.value = _uiState.value.copy(isStalled = true, isLoading = false)
                    HatLogger.log("DashboardViewModel: Engine Stall Detected")
                }
            }
            
            val computedState = withContext(Dispatchers.Default) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val today = Calendar.getInstance()
                
                val oldestStamp = repository.getOldestDataTimestamp(); val oldestCal = Calendar.getInstance().apply { timeInMillis = oldestStamp }
                val hasNext = getEndOfDay(activeCal) < getStartOfDay(today)
                val hasPrev = if (_uiState.value.bypassHistoryLimit) true else getStartOfDay(activeCal) > getStartOfDay(oldestCal)
                
                val settings = repository.settings.first()
                val oldestSystemData = repository.getOldestSystemData()
                val retention = ((System.currentTimeMillis() - oldestSystemData) / 86400000L).toInt()
                val reliable = start >= oldestSystemData || start >= settings.installDateMillis
                val (relativeLabel, exactLabel) = getRelativeAndExactDateLabels(activeCal, range)
                
                val hiddenPkgs = repository.storage.getHiddenPackagesFlow().first()
                val pattern = if (range == TimeRangeLabel.WEEK) "EEE" else "dd"

                val appUsageDef = async { heuristicEngine.getAppUsage(start, end, range, hiddenPkgs) }
                val gapsDef = async { if (range == TimeRangeLabel.DAY) heuristicEngine.scanForGaps(start, end, hiddenPkgs) else emptyList() }
                val chartDataDef = async { if (range == TimeRangeLabel.DAY) heuristicEngine.getHourlyBreakdown(start, end, hiddenPkgs) else heuristicEngine.getDailyBreakdown(start, end, pattern, hiddenPkgs) }
                val appBreakdownDef = async { heuristicEngine.getAppBreakdown(start, end, range, hiddenPkgs) }
                val offlineBreakdownDef = async { heuristicEngine.getOfflineBreakdown(start, end, range) }
                val offlineActivitiesDef = async { heuristicEngine.getOfflineActivities(start, end, range) }

                val (rawScreenTime, totalAppExecution, topAppsList) = appUsageDef.await()
                val allGaps = gapsDef.await()
                val chartData = chartDataDef.await()
                val appDetailChartData = appBreakdownDef.await()
                val offlineDetailChartData = offlineBreakdownDef.await()
                val offlineItems = offlineActivitiesDef.await()
                
                // Offline time must be sharded against app foreground time, exactly
                // as getShardedOfflineIntervals does everywhere else. Summing raw
                // durations double-counted any overlap, so the app+offline stacked
                // chart on the dashboard could exceed the visible day.
                val totalOfflineMillis = heuristicEngine
                    .getShardedOfflineIntervals(start, end)
                    .sumOf { it.second - it.first }
                // HONESTY FIX (2026-10-16): the dashboard's "Total Apps Usage"
                // card used to display raw device foreground time, which INCLUDES
                // hidden apps, while the list under it and the stacked chart
                // exclude them, and the App Usage screen's chart total showed
                // the visible-apps sum. The same concept had two numbers on
                // two screens. Everything on screen now uses the visible-apps
                // accounting (totalAppExecution), so card = list = chart =
                // AppUsageStatsScreen, and hidden apps stay erased everywhere,
                // exactly as the README promises.
                val totalCombinedRaw = totalAppExecution + totalOfflineMillis
                val clampedCombined = minOf(totalCombinedRaw, end - start)
                // totalAppExecution is a per-app sum, so split-screen overlap
                // can legitimately push it past the day length on its own; the
                // clamp handles that, and offline can never display negative.
                val clampedOffline = if (totalCombinedRaw > end - start) ((end - start) - totalAppExecution).coerceAtLeast(0L) else totalOfflineMillis

                // `* 60L` promotes before the multiply; `* 60` would evaluate as
                // Int*Int and overflow above ~35k minutes, silently making every
                // gap qualify as "major". Same fix as HeuristicEngine.
                val minGapMillis = DetailLevel.minDurationMillis(settings)
                // The one visibility filter, applied everywhere (2026-10-22):
                // anything below the user's Minimum Duration simply does not
                // surface, in the list or the chart. The old "minor gaps"
                // section contradicted the setting by listing exactly the
                // millisecond blips the user filtered out, and the chart
                // bucketed them too, showing hour bars with no visible card
                // to explain them.
                val majorGaps = allGaps.filter { it.durationInMillis >= minGapMillis }

                // The locked-screen projection (Lock Screen Activity in the
                // UI): app active behind a locked phone, no unlock after.

                // The fourth category: apps that ran while the screen was
                // provably dark. Computed for the viewport, dismissed spans
                // already folded back into the usage totals by the engine.
                val screenOffItems = if (range == TimeRangeLabel.DAY) // The old Idle Minimum dial defaulted to 15 minutes and also gated this
        // list; the dial is gone, so the same 15-minute floor stays, fixed.
        heuristicEngine.getScreenOffActivity(start, end, 15 * 60_000L) else emptyList()
                val totalScreenOffMillis = screenOffItems.sumOf { it.durationInMillis }

                // Dismissed screen-off spans get the same Restore door
                // (2026-10-21): restoring one pulls the span back out of
                // that app's usage and it reappears as Screen-Off Activity.
                val dismissedScreenOffList = repository.storage.getDismissedScreenOffSpans(start, end).map { (pkg, s, e) ->
                    DismissedSession(pkg, repository.getAppName(pkg), s, e)
                }.sortedBy { it.startMillis }

                // Two dials since 2026-10-23 (the owner's call): Gap Minimum
                // carries stretches of silence, Idle Minimum carries the
                // three question-card kinds. One number either flooded the
                // questions or blinded the gaps; lived reality chose two.
                val gapChartData = if (range == TimeRangeLabel.DAY) {
                    val bucketMillis = 3600000f
                    // Calendar-walked buckets, not `midnight + i * 3_600_000L`:
                    // the fixed offset desynchronised from the wall clock after
                    // a DST transition (the 3 PM bucket covered real 2 PM) and
                    // never charted the last hour of a 25-hour day. The walk
                    // mirrors HeuristicEngine.buildChartPoints exactly, so the
                    // gap chart and the dashboard chart stay in lock-step.
                    hourBucketsOfDay(dayStart).map { bucket -> 
                        var gapTime = 0L
                        for (gap in majorGaps) { val s = maxOf(bucket.startMillis, gap.startMillis); val e = minOf(bucket.endMillis, gap.endMillis); if (e > s) gapTime += (e - s) }
                        val timeStr = TimeUtils.formatDuration(gapTime)
                        ChartPoint(getHourLabel(bucket.startHour), listOf((gapTime / bucketMillis).coerceIn(0f, 1f)), timeStr, listOf(timeStr), fullDateLabel = "${getHourLabel(bucket.startHour)} - ${getHourLabel(bucket.endHour)}", syncTag = bucket.startHour.toString()) 
                    } 
                } else emptyList()
                
                val sortedList = when (currentSortType) { SortType.RECENT -> (topAppsList + offlineItems).sortedByDescending { it.timestamp }; SortType.CHRONOLOGICAL -> (topAppsList + offlineItems).sortedBy { it.timestamp }; SortType.DURATION -> (topAppsList + offlineItems).sortedByDescending { it.durationInMillis } }
                val updateFormat = SimpleDateFormat("'last updated at' hh:mm:ss a", Locale.getDefault())

                _uiState.value.copy(
                    startTime = start, endTime = end, dateLabelRelative = relativeLabel, dateLabelExact = exactLabel, hasNextPeriod = hasNext, hasPreviousPeriod = hasPrev,
                    totalScreenTimeFormatted = TimeUtils.formatDuration(totalAppExecution), totalAppExecutionFormatted = TimeUtils.formatDuration(totalAppExecution),
                    totalOfflineTimeFormatted = TimeUtils.formatDuration(clampedOffline), totalCombinedTimeFormatted = TimeUtils.formatDuration(clampedCombined),
                    totalScreenTimeMillis = totalAppExecution, activityList = sortedList, appDetailChartData = appDetailChartData,
                    offlineDetailChartData = offlineDetailChartData, majorGaps = majorGaps,
                    dismissedScreenOffSpans = dismissedScreenOffList, chartData = chartData, gapChartData = gapChartData,
                    screenOffItems = screenOffItems, totalScreenOffMillis = totalScreenOffMillis,
                    recentSuggestions = repository.getSmartSuggestions(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)), isDataReliable = reliable, osRetentionDays = retention, loadSucceeded = true, lastUpdatedTimeStr = updateFormat.format(Date()), oldestDataTimestamp = oldestStamp
                )
            }
            yield()
            loadFinished = true
            isFirstLoadComplete = true
            spinnerJob.cancel()
            
            val finalState = computedState.copy(isLoading = false, isStalled = false)
                        stateCache.put(cacheKey, finalState)
                        _uiState.value = finalState
                        HatLogger.log("DashboardViewModel: Load complete, committed to Memory Matrix.")
                        } catch (t: Throwable) {
                            // Do not swallow CancellationException into a fake "couldn't read" state.
                            if (t is kotlinx.coroutines.CancellationException) throw t
                            HatLogger.logError("DashboardViewModel", "Load failed: ${t.message}")
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                isStalled = false,
                                loadSucceeded = false
                            )
                        }
                    }
    }

    suspend fun resolveContiguousGap(start: Long, end: Long, onResult: (Long, Long) -> Unit) {
        val hiddenPkgs = repository.storage.getHiddenPackagesFlow().first()
        val (trueS, trueE) = heuristicEngine.expandVoid(start, end, hiddenPkgs)
        onResult(trueS, trueE)
    }

    suspend fun checkCascadingDelete(title: String, start: Long, end: Long, onResult: (Long, Long) -> Unit) {
        val (trueS, trueE) = heuristicEngine.expandActivityCascade(title, start, end)
        onResult(trueS, trueE)
    }
    
    fun previousDay() {
        val activeCal = when (_uiState.value.timeRange) { 
            TimeRangeLabel.DAY -> selectedDayCal
            TimeRangeLabel.WEEK -> selectedWeekCal
            TimeRangeLabel.MONTH -> selectedMonthCal
        }
        
        val oldestCal = Calendar.getInstance().apply { timeInMillis = _uiState.value.oldestDataTimestamp }
        if (!_uiState.value.bypassHistoryLimit && getStartOfDay(activeCal) <= getStartOfDay(oldestCal)) {
            HatLogger.log("DashboardViewModel: Blocked rapid tap. Reached absolute historical boundary.")
            return
        }

        HatLogger.log("DashboardViewModel: Navigating to previous period")
        _uiState.value = _uiState.value.copy(chartHighlight = null)
        when (_uiState.value.timeRange) { 
            TimeRangeLabel.DAY -> selectedDayCal.add(Calendar.DAY_OF_YEAR, -1)
            TimeRangeLabel.WEEK -> selectedWeekCal.add(Calendar.WEEK_OF_YEAR, -1)
            TimeRangeLabel.MONTH -> selectedMonthCal.add(Calendar.MONTH, -1) 
        }
        loadDashboardData(force = false, isBackgroundTick = false) 
    }
    
    fun nextDay() {
        val activeCal = when (_uiState.value.timeRange) { 
            TimeRangeLabel.DAY -> selectedDayCal
            TimeRangeLabel.WEEK -> selectedWeekCal
            TimeRangeLabel.MONTH -> selectedMonthCal
        }
        
        val today = Calendar.getInstance()
        if (getEndOfDay(activeCal) >= getStartOfDay(today)) {
            HatLogger.log("DashboardViewModel: Blocked rapid tap. Reached absolute future boundary.")
            return
        }

        HatLogger.log("DashboardViewModel: Navigating to next period")
        _uiState.value = _uiState.value.copy(chartHighlight = null)
        when (_uiState.value.timeRange) { 
            TimeRangeLabel.DAY -> selectedDayCal.add(Calendar.DAY_OF_YEAR, 1)
            TimeRangeLabel.WEEK -> selectedWeekCal.add(Calendar.WEEK_OF_YEAR, 1)
            TimeRangeLabel.MONTH -> selectedMonthCal.add(Calendar.MONTH, 1) 
        }
        loadDashboardData(force = false, isBackgroundTick = false) 
    }
    
    fun saveGap(start: Long, end: Long, label: String, iconName: String) = viewModelScope.launch { 
        HatLogger.log("DashboardViewModel: Saving single gap: $label")
        var curr = start
        while(curr < end) {
            val cal = Calendar.getInstance().apply { timeInMillis = curr }
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
            val chunkEnd = minOf(end, cal.timeInMillis)
            repository.saveOfflineActivity(label, chunkEnd - curr, curr, iconName)
            curr = chunkEnd + 1 
        }
        loadDashboardData(force = true, isBackgroundTick = false) 
    }

    fun saveMultipleGaps(gaps: List<UsageGap>, label: String, iconName: String) = viewModelScope.launch { 
        HatLogger.log("DashboardViewModel: Saving multiple gaps: $label")
        for (gap in gaps) {
            var curr = gap.startMillis
            while(curr < gap.endMillis) {
                val cal = Calendar.getInstance().apply { timeInMillis = curr }
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                val chunkEnd = minOf(gap.endMillis, cal.timeInMillis)
                repository.saveOfflineActivity(label, chunkEnd - curr, curr, iconName)
                curr = chunkEnd + 1 
            }
        }
        loadDashboardData(force = true, isBackgroundTick = false) 
    }

    /**
     * Undo a screen-off dismissal (2026-10-21): the span stops counting as
     * usage and returns to the Screen-Off Activity list. Restores symmetry
     * with idle dismissals, which always had a Restore door; until now the
     * screen-off one was permanent from the UI even though the storage
     * function existed unused.
     */
    fun restoreDismissedScreenOffSpan(pkg: String, start: Long, end: Long) = viewModelScope.launch {
        repository.storage.restoreScreenOffSpan(pkg, start, end)
        repository.forceInvalidateCache()
        loadDashboardData(force = true, isBackgroundTick = false)
    }

    /**
     * Name a Screen-Off Activity span: the span becomes an offline label,
     * chunked per day like every other label so a midnight-crossing span
     * ("TikTok ran 23:00 to 07:00") lands one row per day. The engine drops
     * the span from the screen-off list through offline-overlap subtraction;
     * nothing is added back to usage.
     */
    fun labelScreenOffSpan(pkg: String, start: Long, end: Long, label: String, iconName: String) = viewModelScope.launch {
        HatLogger.log("DashboardViewModel: Labeling screen-off span ($pkg): $label")
        var curr = start
        while (curr < end) {
            val cal = Calendar.getInstance().apply { timeInMillis = curr }
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
            val chunkEnd = minOf(end, cal.timeInMillis)
            repository.saveOfflineActivity(label, chunkEnd - curr, curr, iconName)
            curr = chunkEnd + 1
        }
        repository.forceInvalidateCache()
        loadDashboardData(force = true, isBackgroundTick = false)
    }

    /**
     * "This really was usage": the exact span merges back into that app's
     * usage totals via the screen_off_dismissed row, with millisecond
     * precision, and disappears from the screen-off list.
     */
    fun dismissScreenOffSpan(pkg: String, start: Long, end: Long) = viewModelScope.launch {
        HatLogger.log("DashboardViewModel: Dismissing screen-off span ($pkg) back into usage")
        repository.storage.dismissScreenOffSpan(pkg, start, end)
        repository.forceInvalidateCache()
        loadDashboardData(force = true, isBackgroundTick = false)
    }

    fun deleteOfflineActivity(title: String, start: Long, end: Long) = viewModelScope.launch { 
        repository.deleteOfflineActivitiesByTitle(title, start, end)
        repository.forceInvalidateCache()
        loadDashboardData(force = true, isBackgroundTick = false) 
    }

    private fun getStartOfDay(cal: Calendar): Long { val clone = cal.clone() as Calendar; clone.set(Calendar.HOUR_OF_DAY, 0); clone.set(Calendar.MINUTE, 0); clone.set(Calendar.SECOND, 0); clone.set(Calendar.MILLISECOND, 0); return clone.timeInMillis }
    private fun getEndOfDay(cal: Calendar): Long { val clone = cal.clone() as Calendar; clone.set(Calendar.HOUR_OF_DAY, 23); clone.set(Calendar.MINUTE, 59); clone.set(Calendar.SECOND, 59); clone.set(Calendar.MILLISECOND, 999); return clone.timeInMillis }
    private fun getHourLabel(hour: Int): String = when(hour) { 0 -> "12 AM"; 12 -> "12 PM"; else -> if(hour<12) "$hour AM" else "${hour-12} PM" }
    private fun getRelativeAndExactDateLabels(cal: Calendar, range: TimeRangeLabel): Pair<String, String> {
        val today = Calendar.getInstance(); val todayStart = getStartOfDay(today); val targetStart = getStartOfDay(cal)
        val diffDays = ((todayStart - targetStart) / (1000 * 60 * 60 * 24)).toInt()
        val exactFormat = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())
        return when (range) {
            TimeRangeLabel.DAY -> Pair(when (diffDays) { 0 -> "Today"; 1 -> "Yesterday"; else -> "$diffDays days ago" }, exactFormat.format(cal.time))
            TimeRangeLabel.WEEK -> {
                val cloneCal = cal.clone() as Calendar; cloneCal.firstDayOfWeek = Calendar.MONDAY; while (cloneCal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) cloneCal.add(Calendar.DAY_OF_YEAR, -1)
                val start = getStartOfDay(cloneCal); cloneCal.add(Calendar.DAY_OF_WEEK, 6)
                val wDiff = ((getStartOfDay(Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY; while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) add(Calendar.DAY_OF_YEAR, -1) }) - start) / (1000L * 60 * 60 * 24 * 7)).toInt()
                Pair(when (wDiff) { 0 -> "This Week"; 1 -> "Last Week"; else -> "$wDiff weeks ago" }, "${SimpleDateFormat("MMM d", Locale.getDefault()).format(start)} - ${SimpleDateFormat("MMM d", Locale.getDefault()).format(cloneCal.time)}")
            }
            TimeRangeLabel.MONTH -> {
                val mDiff = (today.get(Calendar.YEAR) - cal.get(Calendar.YEAR)) * 12 + today.get(Calendar.MONTH) - cal.get(Calendar.MONTH)
                Pair(when (mDiff) { 0 -> "This Month"; 1 -> "Last Month"; else -> "$mDiff months ago" }, SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time))
            }
        }
    }
}
@Suppress("UNCHECKED_CAST")
class DashboardViewModelFactory(private val repository: ActivityRepository, private val context: Context) : ViewModelProvider.Factory { 
    override fun <T : ViewModel> create(modelClass: Class<T>): T { 
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) return DashboardViewModel(repository, HeuristicEngine(repository, UsageStatsEngine(context)), context) as T
        throw IllegalArgumentException("Unknown ViewModel class") 
    } 
}
