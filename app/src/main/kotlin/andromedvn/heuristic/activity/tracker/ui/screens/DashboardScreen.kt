package andromedvn.heuristic.activity.tracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.data.AppUsageItem
import andromedvn.heuristic.activity.tracker.data.OfflineActivityItem
import andromedvn.heuristic.activity.tracker.data.TimeRangeLabel
import andromedvn.heuristic.activity.tracker.viewmodel.DashboardViewModel
import andromedvn.heuristic.activity.tracker.ui.components.*
import andromedvn.heuristic.activity.tracker.utils.HeuristicQuotes
import andromedvn.heuristic.activity.tracker.utils.hasUsageStatsPermission
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(navController: NavController, viewModel: DashboardViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val chartHighlight = uiState.chartHighlight
    var hasPermission by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val chartScrollState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var headerTapCount by remember { mutableIntStateOf(0) }
    var lastHeaderTap by remember { mutableLongStateOf(0L) }

    LaunchedEffect(uiState.timeRange, uiState.dateLabelRelative) { 
        viewModel.clearScrollState()
        chartScrollState.scrollToItem(0) 
    }

    val isResumed = lifecycleState == Lifecycle.State.RESUMED
    val hasSelection = chartHighlight != null
    
    BackHandler(enabled = (uiState.returnScrollOffset != -1 || hasSelection || showDatePicker) && isResumed) {
        if (showDatePicker) { showDatePicker = false }
        else if (hasSelection) { viewModel.setChartHighlight(null) } 
        else { scope.launch { listState.animateScrollToItem(0, uiState.returnScrollOffset); viewModel.clearScrollState(); viewModel.setChartHighlight(null) } }
    }

    if (showDatePicker) {
        HatDatePickerModal(
            initialDate = uiState.startTime,
            oldestData = uiState.oldestDataTimestamp,
            bypassHistoryLimit = uiState.bypassHistoryLimit,
            onDismiss = { showDatePicker = false },
            onDateSelected = { date -> viewModel.jumpToDate(date) }
        )
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 32.dp),
                modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()).windowInsetsPadding(WindowInsets.statusBars).pointerInput(Unit) { detectTapGestures(onTap = { viewModel.setChartHighlight(null); viewModel.clearScrollState() }) }.verticalFadingEdges(listState.canScrollBackward, listState.canScrollForward)
            ) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Box(modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            val now = System.currentTimeMillis()
                            if (now - lastHeaderTap > 500) headerTapCount = 1 else headerTapCount++
                            lastHeaderTap = now
                            if (headerTapCount == 5) {
                                headerTapCount = 0
                                navController.navigate("diagnostics")
                            }
                        }) {
                            HatDynamicHeader(title = "HAT", subtitle = "Dashboard") {
                                IconButton(onClick = { viewModel.loadDashboardData(force = true, isBackgroundTick = false) }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurface) }
                                IconButton(onClick = { navController.navigate("settings") }) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface) }
                            }
                        }
                        
                        TimeSelector(currentRange = uiState.timeRange, onRangeSelected = { viewModel.setTimeRange(it) })
                        Spacer(modifier = Modifier.height(24.dp))

                        Column(modifier = Modifier.fillMaxWidth().animateContentSize(tween(400, easing = FastOutSlowInEasing))) {
                            HatChart(
                                chartScrollState = chartScrollState, animationSalt = uiState.animationSalt,
                                title = "Activity Overview", subTitle = "Apps Usage + Offline Time", totalTime = if (uiState.totalCombinedTimeFormatted.isNotEmpty()) uiState.totalCombinedTimeFormatted else "0m", 
                                dataPoints = uiState.chartData, palette = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary), dateLabelRelative = uiState.dateLabelRelative, dateLabelExact = uiState.dateLabelExact,
                                highlightedPoint = chartHighlight, hasNext = uiState.hasNextPeriod, hasPrev = uiState.hasPreviousPeriod, onPrevious = { viewModel.previousDay() }, onNext = { viewModel.nextDay() },
                                onDateClick = { showDatePicker = true },
                                onPointSelected = { event ->
                                    viewModel.setChartHighlight(event)
                                    if (event?.isFromChart == true && event.isLongPress && event.stackIndex != -1) {
                                        val matchTag = event.point?.syncTag ?: ""
                                        val expectedClass = if (event.stackIndex == 0) AppUsageItem::class.java else OfflineActivityItem::class.java
                                        val matchIndex = uiState.activityList.indexOfFirst {
                                            expectedClass.isInstance(it) && it.activeTimeTags.contains(matchTag)
                                        }
                                        if (matchIndex != -1) {
                                            scope.launch { listState.animateScrollToItem(matchIndex + 1) }
                                        }
                                    }
                                }
                            )
                            val showHint = chartHighlight != null && chartHighlight.stackIndex == -1 && chartHighlight.point?.values?.all { it <= 0f } == true
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
                                            chartMode = ChartMode.DASHBOARD
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
                            AnimatedVisibility(visible = uiState.isStalled, enter = fadeIn(tween(400)), exit = fadeOut(tween(400))) {
                                Column(modifier = Modifier.padding(top = 16.dp)) {
                                    Card(modifier = Modifier.fillMaxWidth().wrapContentHeight(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background), shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)) {
                                        Box(modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(modifier = Modifier.width(8.dp)); Text("System OS Unresponsive", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                                                    Spacer(modifier = Modifier.height(8.dp)); Text("Android's Usage API is failing to return data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                                }
                                                Text("Force Refresh", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.background, modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).clickable { viewModel.loadDashboardData(force = true, isBackgroundTick = false) }.padding(horizontal = 16.dp, vertical = 12.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).alpha(if (uiState.lastUpdatedTimeStr.isEmpty()) 0f else 1f), contentAlignment = Alignment.Center) {
                            Text(text = uiState.lastUpdatedTimeStr, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                        
                        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            SummaryStatCard(modifier = Modifier.weight(1f).fillMaxHeight(), title = "Total Apps Usage", value = uiState.totalScreenTimeFormatted, badgeText = null, onClick = { navController.navigate("app_usage_stats") })
                            SummaryStatCard(modifier = Modifier.weight(1f).fillMaxHeight(), title = "Total Offline Time", value = uiState.totalOfflineTimeFormatted, badgeText = null, onClick = { navController.navigate("offline_stats") })
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        val isDayView = uiState.timeRange == TimeRangeLabel.DAY
                        OutlinedButton(
                            onClick = { if (isDayView) navController.navigate("label_gaps") else viewModel.setTimeRange(TimeRangeLabel.DAY) }, 
                            modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(50), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (isDayView) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent), border = BorderStroke(if (isDayView) 2.dp else 1.dp, if (isDayView) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        ) { Text(if (isDayView) "View Unlabeled Gaps" else "Switch to Day View for Gaps", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isDayView) FontWeight.Bold else FontWeight.Medium), color = MaterialTheme.colorScheme.primary) }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                if (!hasPermission) {
                    item { Box(modifier = Modifier.padding(horizontal = 20.dp)) { PermissionRequestCard(context) } }
                } else if (uiState.activityList.isEmpty() && !uiState.isLoading) {
                    item { Box(modifier = Modifier.padding(horizontal = 20.dp)) { EmptyStateCard(Icons.Default.Bedtime, "No Activity", "No sessions or offline logs found for this period.") } }
                } else {
                    val maxUsage = uiState.activityList.maxOfOrNull { it.durationInMillis }?.toFloat() ?: 1f
                    items(uiState.activityList, key = { it.uniqueKey }) { item ->
                        val progress = item.durationInMillis / maxUsage.coerceAtLeast(1f)
                        val stackIndex = if (item is AppUsageItem) 0 else 1
                        val isHighlighted = if (chartHighlight != null) { if (chartHighlight.isFromChart) { val pointTag = chartHighlight.point?.syncTag ?: ""; (pointTag in item.activeTimeTags) && (chartHighlight.stackIndex == -1 || chartHighlight.stackIndex == stackIndex) } else chartHighlight.sourceKey == item.uniqueKey } else false

                        val onLongPress = { sIdx: Int ->
                            val targetTag = item.activeTimeTags.firstOrNull() ?: ""
                            val matchedPoint = uiState.chartData.find { it.syncTag == targetTag }
                            if (matchedPoint != null) {
                                viewModel.saveScrollState(0, listState.firstVisibleItemScrollOffset)
                                viewModel.setChartHighlight(HighlightEvent(matchedPoint, stackIndex = sIdx, timestamp = System.currentTimeMillis(), isLongPress = true, isFromChart = false, sourceKey = item.uniqueKey, activeTags = item.activeTimeTags))
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                        }

                        Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                            when (item) {
                                is AppUsageItem -> RealActivityListItem(navController = navController, app = item, color = MaterialTheme.colorScheme.primary, progress = progress, isHighlighted = isHighlighted, onLongClick = { onLongPress(0) })
                                is OfflineActivityItem -> MockOfflineListItem(name = item.title, duration = item.timeFormatted, iconName = item.iconName, color = MaterialTheme.colorScheme.primary, progress = progress, isHighlighted = isHighlighted, onClick = { navController.navigate("details/offline/${item.id}") }, onLongClick = { onLongPress(1) })
                            }
                        }
                    }
                }
            }
            
            AnimatedVisibility(visible = uiState.isLoading, enter = fadeIn(tween(200)), exit = fadeOut(tween(400)), modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)).pointerInput(Unit) { detectTapGestures {  } }, contentAlignment = Alignment.Center) { HatLoadingSpinner(MaterialTheme.colorScheme.primary) }
            }
        }
    }
}
