package andromedvn.heuristic.activity.tracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.data.AppUsageItem
import andromedvn.heuristic.activity.tracker.data.TimeRangeLabel
import andromedvn.heuristic.activity.tracker.ui.components.ChartMode
import andromedvn.heuristic.activity.tracker.ui.components.ChartPoint
import andromedvn.heuristic.activity.tracker.ui.components.HighlightEvent
import andromedvn.heuristic.activity.tracker.ui.components.HatChart
import andromedvn.heuristic.activity.tracker.ui.components.HatDatePickerModal
import andromedvn.heuristic.activity.tracker.ui.components.RealActivityListItem
import andromedvn.heuristic.activity.tracker.ui.components.HatDynamicHeader
import andromedvn.heuristic.activity.tracker.ui.components.TimeSelector
import andromedvn.heuristic.activity.tracker.ui.components.verticalFadingEdges
import andromedvn.heuristic.activity.tracker.ui.components.HeuristicTipCard
import andromedvn.heuristic.activity.tracker.ui.components.EmptyStateCard
import andromedvn.heuristic.activity.tracker.utils.HeuristicQuotes
import andromedvn.heuristic.activity.tracker.viewmodel.DashboardViewModel
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun AppUsageStatsScreen(navController: NavController, viewModel: DashboardViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val chartScrollState = rememberLazyListState()
    val chartHighlight = uiState.chartHighlight
    val scope = rememberCoroutineScope()
    val primaryColor = MaterialTheme.colorScheme.primary

    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()

    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var returnScrollIndex by rememberSaveable { mutableIntStateOf(-1) }
    var returnScrollOffset by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(uiState.timeRange, uiState.dateLabelRelative) {
        viewModel.setChartHighlight(null)
        returnScrollIndex = -1
        returnScrollOffset = 0
        chartScrollState.scrollToItem(0)
    }
    
    val appList = remember(uiState.activityList) {
        uiState.activityList.filterIsInstance<AppUsageItem>().sortedByDescending { it.durationInMillis }
    }
    
    LaunchedEffect(appList) {
        if (chartHighlight != null && appList.isNotEmpty() && returnScrollIndex == -1) {
            val matchTag = chartHighlight.point?.syncTag ?: ""
            val index = appList.indexOfFirst { it.activeTimeTags.contains(matchTag) }
            if (index != -1) listState.animateScrollToItem(index + 1)
        }
    }

    val handleBack: () -> Unit = {
        if (showDatePicker) { showDatePicker = false }
        else if (chartHighlight != null) { viewModel.setChartHighlight(null) }
        else if (returnScrollIndex != -1) { scope.launch { listState.scrollToItem(returnScrollIndex, returnScrollOffset); returnScrollIndex = -1; viewModel.setChartHighlight(null) } } 
        else { navController.popBackStack() }
    }

    val isResumed = lifecycleState == Lifecycle.State.RESUMED
    BackHandler(enabled = (returnScrollIndex != -1 || chartHighlight != null || showDatePicker) && isResumed) { handleBack() }

    if (showDatePicker) {
        HatDatePickerModal(
            initialDate = uiState.startTime,
            oldestData = uiState.oldestDataTimestamp,
            bypassHistoryLimit = uiState.bypassHistoryLimit,
            onDismiss = { showDatePicker = false },
            onDateSelected = { date -> viewModel.jumpToDate(date) }
        )
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0,0,0,0)) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(bottom = innerPadding.calculateBottomPadding())
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { viewModel.setChartHighlight(null); returnScrollIndex = -1; returnScrollOffset = 0 }) }
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalFadingEdges(listState.canScrollBackward, listState.canScrollForward)
        ) {
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        HatDynamicHeader(title = "Apps Usage", subtitle = "Quick Overview")
                        TimeSelector(currentRange = uiState.timeRange, onRangeSelected = { viewModel.setTimeRange(it) })
                        Spacer(modifier = Modifier.height(24.dp))
                        HatChart(
                            chartScrollState = chartScrollState, animationSalt = uiState.animationSalt,
                            title = "Apps Usage", subTitle = "Total Tracked Time", totalTime = uiState.totalAppExecutionFormatted, dataPoints = uiState.appDetailChartData, palette = listOf(primaryColor), dateLabelRelative = uiState.dateLabelRelative, dateLabelExact = uiState.dateLabelExact, highlightedPoint = chartHighlight, hasNext = uiState.hasNextPeriod, hasPrev = uiState.hasPreviousPeriod, onPrevious = { viewModel.previousDay() }, onNext = { viewModel.nextDay() }, onDateClick = { showDatePicker = true }, onPointSelected = { event -> if (event == null) { viewModel.setChartHighlight(null); returnScrollIndex = -1 } else { viewModel.setChartHighlight(event) } })
                        
                        val showHint = chartHighlight != null && chartHighlight.point?.values?.all { it <= 0f } == true
                        AnimatedVisibility(visible = showHint, enter = fadeIn(tween(400)), exit = fadeOut(tween(400))) {
                            Column(modifier = Modifier.padding(top = 16.dp)) {
                                val isFuture = chartHighlight?.point?.isFuture == true
                                val quote = remember(chartHighlight) {
                                    HeuristicQuotes.getQuote(
                                        point = chartHighlight?.point,
                                        totalScreenTimeMillis = uiState.totalScreenTimeMillis,
                                        isDayView = uiState.timeRange == TimeRangeLabel.DAY,
                                        isDataReliable = uiState.isDataReliable,
                                        hasSeenHistoryLimitWarning = uiState.hasSeenHistoryLimitWarning,
                                        osRetentionDays = uiState.osRetentionDays,
                                        chartMode = ChartMode.APP_USAGE
                                    )
                                }
                                LaunchedEffect(chartHighlight) {
                                    if (!uiState.isDataReliable && !isFuture && !uiState.hasSeenHistoryLimitWarning) {
                                        viewModel.markHistoryLimitWarningSeen()
                                    }
                                }
                                HeuristicTipCard(quote = quote)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("APPS USAGE LIST", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
                
                if (appList.isEmpty()) {
                    item { Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) { EmptyStateCard(Icons.Default.Smartphone, "Zero Digital Usage", "No apps logged for this period.") } }
                } else {
                    val maxUsage = appList.maxOfOrNull { it.durationInMillis }?.toFloat() ?: 1f
                    itemsIndexed(appList, key = { _, item -> item.uniqueKey }) { _, item -> 
                        val isHighlighted = if (chartHighlight != null) {
                            if (chartHighlight.isFromChart) {
                                val pointTag = chartHighlight.point?.syncTag ?: ""
                                (pointTag in item.activeTimeTags) && (chartHighlight.stackIndex == -1 || chartHighlight.stackIndex == 0)
                            } else chartHighlight.sourceKey == item.uniqueKey
                        } else false
                    
                        Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                            RealActivityListItem(navController, item, color = primaryColor, progress = item.durationInMillis / maxUsage, isHighlighted = isHighlighted, onLongClick = {
                                val matchTag = when (uiState.timeRange) {
                                    TimeRangeLabel.DAY -> Calendar.getInstance().apply { timeInMillis = item.timestamp }.get(Calendar.HOUR_OF_DAY).toString()
                                    TimeRangeLabel.WEEK -> SimpleDateFormat("EEE", Locale.ENGLISH).format(item.timestamp)
                                    TimeRangeLabel.MONTH -> SimpleDateFormat("MMM d", Locale.ENGLISH).format(item.timestamp)
                                }
                                val matchedPoint = uiState.appDetailChartData.find { it.syncTag == matchTag }
                                if (matchedPoint != null) {
                                    returnScrollIndex = listState.firstVisibleItemIndex
                                    returnScrollOffset = listState.firstVisibleItemScrollOffset
                                    viewModel.setChartHighlight(HighlightEvent(matchedPoint, timestamp = System.currentTimeMillis(), sourceKey = item.uniqueKey, activeTags = item.activeTimeTags))
                                    scope.launch { listState.animateScrollToItem(0) }
                                }
                            }) 
                        }
                    }
                }
            }
        }
    }
}
