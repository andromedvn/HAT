package andromedvn.heuristic.activity.tracker.viewmodel

import android.content.Context
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import andromedvn.heuristic.activity.tracker.data.*
import andromedvn.heuristic.activity.tracker.domain.HeuristicEngine
import andromedvn.heuristic.activity.tracker.ui.components.ChartPoint
import andromedvn.heuristic.activity.tracker.ui.components.HighlightEvent
import andromedvn.heuristic.activity.tracker.utils.TimeUtils
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

data class IdleSession(val packageName: String, val appName: String, val gap: UsageGap)

data class DismissedSession(val packageName: String, val appName: String, val startMillis: Long, val endMillis: Long)

data class DashboardUiState(
    val timeRange: TimeRangeLabel = TimeRangeLabel.DAY, val dateLabelRelative: String = "Today", val dateLabelExact: String = "", val startTime: Long = 0L, val endTime: Long = 0L,
    val hasNextPeriod: Boolean = false, val hasPreviousPeriod: Boolean = true, val totalScreenTimeFormatted: String = "0m", val totalAppExecutionFormatted: String = "0m", 
    val totalOfflineTimeFormatted: String = "0m", val totalCombinedTimeFormatted: String = "0m", val totalScreenTimeMillis: Long = 0L,
    val activityList: List<UnifiedActivity> = emptyList(), val appDetailChartData: List<ChartPoint> = emptyList(), val offlineDetailChartData: List<ChartPoint> = emptyList(), 
    val majorGaps: List<UsageGap> = emptyList(), val minorGaps: List<UsageGap> = emptyList(), val idleSessions: List<IdleSession> = emptyList(), 
    val dismissedIdleSessions: List<DismissedSession> = emptyList(), val chartData: List<ChartPoint> = emptyList(), val gapChartData: List<ChartPoint> = emptyList(), 
    val recentSuggestions: List<String> = emptyList(),
    val chartHighlight: HighlightEvent? = null, val isLoading: Boolean = false, val isStalled: Boolean = false, val isDataReliable: Boolean = true, val osRetentionDays: Int = 7, 
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
    fun setChartHighlight(event: HighlightEvent?) { _uiState.value = _uiState.value.copy(chartHighlight = event) }
    
    fun markHistoryLimitWarningSeen() = viewModelScope.launch {
        val currentSettings = repository.settings.first()
        repository.storage.saveSettings(currentSettings.copy(hasSeenHistoryLimitWarning = true))
    }

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
            when (range) {
                TimeRangeLabel.DAY -> { start = getStartOfDay(activeCal); end = getEndOfDay(activeCal) }
                TimeRangeLabel.WEEK -> { val cal = activeCal.clone() as Calendar; cal.firstDayOfWeek = Calendar.MONDAY; while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) cal.add(Calendar.DAY_OF_YEAR, -1); start = getStartOfDay(cal); cal.add(Calendar.DAY_OF_WEEK, 6); end = getEndOfDay(cal) }
                TimeRangeLabel.MONTH -> { val cal = activeCal.clone() as Calendar; cal.set(Calendar.DAY_OF_MONTH, 1); start = getStartOfDay(cal); cal.add(Calendar.MONTH, 1); cal.add(Calendar.DAY_OF_MONTH, -1); end = getEndOfDay(cal) }
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
                    minorGaps = emptyList(),
                    idleSessions = emptyList(),
                    dismissedIdleSessions = emptyList(),
                    chartHighlight = null,
                    animationSalt = java.util.UUID.randomUUID().toString(),
                    isLoading = true
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
                val ackGhosts = repository.getAcknowledgedGhostsBetween(start - 86400000L, end + 86400000L)
                val pattern = if (range == TimeRangeLabel.WEEK) "EEE" else "dd"

                val appUsageDef = async { heuristicEngine.getAppUsage(start, end, range, settings.ghostTimeTriggerHours, hiddenPkgs, ackGhosts) }
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
                
                val totalOfflineMillis = offlineItems.sumOf { it.durationInMillis }
                val totalCombinedRaw = rawScreenTime + totalOfflineMillis
                val clampedCombined = minOf(totalCombinedRaw, end - start)
                val clampedOffline = if (totalCombinedRaw > end - start) (end - start) - rawScreenTime else totalOfflineMillis

                val minGapMillis = settings.minGapThresholdMins * 60 * 1000L
                val majorGaps = allGaps.filter { it.durationInMillis >= minGapMillis }
                val minorGaps = allGaps.filter { it.durationInMillis < minGapMillis }

                val idleList = topAppsList.flatMap { app -> app.ghostIntervals.map { gap -> IdleSession(app.packageName, app.title, gap) } }.sortedByDescending { it.gap.startMillis }
                
                val viewportAckGhosts = ackGhosts.filter { it.startMillis < end && it.endMillis > start }
                val dismissedList = viewportAckGhosts.map { 
                    DismissedSession(it.packageName, repository.getAppName(it.packageName), it.startMillis, it.endMillis) 
                }.sortedByDescending { it.startMillis }
                
                val gapChartData = if (range == TimeRangeLabel.DAY) { 
                    val bucketMillis = 3600000f
                    List(24) { i -> 
                        val hourStart = getStartOfDay(activeCal) + (i * 3600000L); val hourEnd = hourStart + 3600000L; var gapTime = 0L
                        for (gap in allGaps) { val s = maxOf(hourStart, gap.startMillis); val e = minOf(hourEnd, gap.endMillis); if (e > s) gapTime += (e - s) }
                        val timeStr = TimeUtils.formatDuration(gapTime)
                        ChartPoint(getHourLabel(i), listOf((gapTime / bucketMillis).coerceIn(0f, 1f)), timeStr, listOf(timeStr), fullDateLabel = "${getHourLabel(i)} - ${getHourLabel((i+1)%24)}", syncTag = i.toString()) 
                    } 
                } else emptyList()
                
                val sortedList = when (currentSortType) { SortType.RECENT -> (topAppsList + offlineItems).sortedByDescending { it.timestamp }; SortType.CHRONOLOGICAL -> (topAppsList + offlineItems).sortedBy { it.timestamp }; SortType.DURATION -> (topAppsList + offlineItems).sortedByDescending { it.durationInMillis } }
                val updateFormat = SimpleDateFormat("'last update today at' hh:mm:ss a", Locale.getDefault())

                _uiState.value.copy(
                    startTime = start, endTime = end, dateLabelRelative = relativeLabel, dateLabelExact = exactLabel, hasNextPeriod = hasNext, hasPreviousPeriod = hasPrev, 
                    totalScreenTimeFormatted = TimeUtils.formatDuration(rawScreenTime), totalAppExecutionFormatted = TimeUtils.formatDuration(totalAppExecution), 
                    totalOfflineTimeFormatted = TimeUtils.formatDuration(clampedOffline), totalCombinedTimeFormatted = TimeUtils.formatDuration(clampedCombined), 
                    totalScreenTimeMillis = rawScreenTime, activityList = sortedList, appDetailChartData = appDetailChartData, 
                    offlineDetailChartData = offlineDetailChartData, majorGaps = majorGaps, minorGaps = minorGaps, idleSessions = idleList, 
                    dismissedIdleSessions = dismissedList, chartData = chartData, gapChartData = gapChartData, 
                    recentSuggestions = repository.getSmartSuggestions(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)), isDataReliable = reliable, osRetentionDays = retention, lastUpdatedTimeStr = updateFormat.format(Date()), oldestDataTimestamp = oldestStamp
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

    fun convertGhostTime(pkg: String, start: Long, end: Long, label: String, iconName: String) = viewModelScope.launch { 
        HatLogger.log("DashboardViewModel: Converting ghost time: $label")
        var curr = start
        while(curr < end) {
            val cal = Calendar.getInstance().apply { timeInMillis = curr }
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
            val chunkEnd = minOf(end, cal.timeInMillis)
            repository.storage.addIgnoredSession(IgnoredSession(pkg, curr, chunkEnd))
            repository.saveOfflineActivity(label, chunkEnd - curr, curr, iconName)
            curr = chunkEnd + 1 
        }
        repository.forceInvalidateCache()
        loadDashboardData(force = true, isBackgroundTick = false) 
    }

    fun convertMultipleGhostTimes(pkg: String, gaps: List<UsageGap>, label: String, iconName: String) = viewModelScope.launch { 
        HatLogger.log("DashboardViewModel: Converting multiple ghost times: $label")
        for (gap in gaps) {
            var curr = gap.startMillis
            while(curr < gap.endMillis) {
                val cal = Calendar.getInstance().apply { timeInMillis = curr }
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                val chunkEnd = minOf(gap.endMillis, cal.timeInMillis)
                repository.storage.addIgnoredSession(IgnoredSession(pkg, curr, chunkEnd))
                repository.saveOfflineActivity(label, chunkEnd - curr, curr, iconName)
                curr = chunkEnd + 1 
            }
        }
        repository.forceInvalidateCache()
        loadDashboardData(force = true, isBackgroundTick = false) 
    }

    fun dismissGhost(pkg: String, start: Long, end: Long) = viewModelScope.launch {
        HatLogger.log("DashboardViewModel: Dismissing ghost time for $pkg")
        repository.storage.addAcknowledgedGhost(AcknowledgedGhost(pkg, start, end))
        repository.forceInvalidateCache()
        loadDashboardData(force = true, isBackgroundTick = false)
    }

    fun restoreDismissedGhost(pkg: String, start: Long, end: Long) = viewModelScope.launch {
        repository.restoreAcknowledgedGhost(pkg, start, end)
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
                Pair(when (wDiff) { 0 -> "This Week"; 1 -> "Last Week"; else -> "$wDiff weeks ago" }, "${SimpleDateFormat("MMM d", Locale.getDefault()).format(start)} - ${exactFormat.format(cloneCal.time)}")
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
