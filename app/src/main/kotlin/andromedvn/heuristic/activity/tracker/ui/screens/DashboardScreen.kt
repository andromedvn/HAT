package andromedvn.heuristic.activity.tracker.ui.screens

import andromedvn.heuristic.activity.tracker.domain.NavRouteCodec
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.data.AppUsageItem
import andromedvn.heuristic.activity.tracker.data.OfflineActivityItem
import andromedvn.heuristic.activity.tracker.data.ScreenOffItem
import andromedvn.heuristic.activity.tracker.data.TimeRangeLabel
import andromedvn.heuristic.activity.tracker.viewmodel.DashboardViewModel
import andromedvn.heuristic.activity.tracker.ui.components.*
import andromedvn.heuristic.activity.tracker.utils.HeuristicQuotes
import andromedvn.heuristic.activity.tracker.utils.TimeUtils
import andromedvn.heuristic.activity.tracker.utils.hasUsageStatsPermission
import androidx.compose.ui.text.style.TextOverflow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import andromedvn.heuristic.activity.tracker.ui.components.TourRegistry
import andromedvn.heuristic.activity.tracker.ui.components.tutorialTarget
import andromedvn.heuristic.activity.tracker.domain.AppTourState
import andromedvn.heuristic.activity.tracker.domain.DashboardTourSteps
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(navController: NavController, viewModel: DashboardViewModel, startTour: Boolean = false) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val chartHighlight = uiState.chartHighlight
    var hasPermission by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    // The app tour (2026-11-01): one global state, every screen hosts its own
    // steps. tourLaunched guards the auto-start: the startTour argument lives
    // in the back stack entry's saved state, so returning from Label Gaps
    // recomposes with startTour still true - without the guard the whole tour
    // restarted on return (owner report, 2026-11-01).
    val tourRegistry = andromedvn.heuristic.activity.tracker.ui.components.AppTourRegistry
    var tourLaunched by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(startTour) {
        if (startTour && !tourLaunched) {
            tourLaunched = true
            AppTourState.start()
        }
    }
    // Bring the current step's target into view before the spotlight lands on
    // it: the stat cards, app rows and the Label Gaps button all sit below the
    // fold on a fresh screen (owner report, 2026-11-01). Declared after
    // listState below, so the scroll call has its receiver.
    val tourAutoScrollPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    fun endTour() {
        AppTourState.end()
        viewModel.markTourSeen()
    }
    // Re-check on every ON_RESUME: the user grants Usage Access in system
    // settings and comes back, and the card must disappear the moment the
    // grant is real. A stale flag made the card look permanently broken -
    // tap, grant, return, same card - a same-day uninstall trigger.
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPermission = hasUsageStatsPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val chartScrollState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll the tour: bring each step's target to ~38% of the screen
    // height, animated (the owner called the jump "robotic"), so the spotlight
    // never lands on something below the fold.
    val tourStepHere = if (AppTourState.active && AppTourState.current?.screen == DashboardTourSteps.SCREEN_DASHBOARD) AppTourState.stepIndex else -1
    androidx.compose.runtime.LaunchedEffect(tourStepHere) {
        if (tourStepHere < 0) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        val id = DashboardTourSteps.steps.getOrNull(tourStepHere)?.targetKey ?: return@LaunchedEffect
        val rect = tourRegistry.bounds[id] ?: return@LaunchedEffect
        val anchor = tourAutoScrollPx * 0.38f
        val delta = rect.center.y - anchor
        if (kotlin.math.abs(delta) > 80f) {
            // The cutout snaps to the moving target while this runs (no
            // tween-chase jelly), then the glide behavior resumes.
            AppTourState.snapCutout = true
            listState.animateScrollBy(delta, tween(450, easing = FastOutSlowInEasing))
            kotlinx.coroutines.delay(80)
            AppTourState.snapCutout = false
        }
    }

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
                                IconButton(
                                    onClick = { navController.navigate("settings") },
                                    modifier = Modifier.tutorialTarget("settingsGear", tourRegistry)
                                ) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface) }
                            }
                        }
                        
                        TimeSelector(currentRange = uiState.timeRange, onRangeSelected = { viewModel.setTimeRange(it) }, modifier = Modifier.tutorialTarget("timeSelector", tourRegistry))
                        Spacer(modifier = Modifier.height(24.dp))

                        Column(modifier = Modifier.fillMaxWidth().animateContentSize(tween(400, easing = FastOutSlowInEasing)).tutorialTarget("chart", tourRegistry)) {
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
                            AnimatedVisibility(
                                visible = showHint,
                                enter = fadeIn(tween(300)) + expandVertically(tween(300, easing = FastOutSlowInEasing)),
                                exit = fadeOut(tween(300)) + shrinkVertically(tween(300, easing = FastOutSlowInEasing))
                            ) {
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
                                                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(modifier = Modifier.width(8.dp)); Text("Still Reading Your History", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                                                    Spacer(modifier = Modifier.height(8.dp)); Text("This range is large, so the first read can take a moment. Give it a second, or force a refresh.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
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
                        
                        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max).tutorialTarget("statCards", tourRegistry), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            SummaryStatCard(modifier = Modifier.weight(1f).fillMaxHeight().tutorialTarget("statCardApps", tourRegistry), title = "Total Apps Usage", value = uiState.totalScreenTimeFormatted, badgeText = null, onClick = { navController.navigate("app_usage_stats") })
                            SummaryStatCard(modifier = Modifier.weight(1f).fillMaxHeight(), title = "Total Offline Time", value = uiState.totalOfflineTimeFormatted, badgeText = null, onClick = { navController.navigate("offline_stats") })
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        val isDayView = uiState.timeRange == TimeRangeLabel.DAY
                        OutlinedButton(
                            onClick = { if (isDayView) navController.navigate("label_gaps") else viewModel.setTimeRange(TimeRangeLabel.DAY) },
                            modifier = Modifier.fillMaxWidth().height(48.dp).tutorialTarget("labelGaps", tourRegistry), shape = RoundedCornerShape(50), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (isDayView) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent), border = BorderStroke(if (isDayView) 2.dp else 1.dp, if (isDayView) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
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
                    itemsIndexed(uiState.activityList, key = { _, it -> it.uniqueKey }) { itemIndex, item ->
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

                        Box(modifier = Modifier.padding(horizontal = 20.dp).let { if (itemIndex == 0) it.tutorialTarget("appRows", tourRegistry) else it }) {
                            when (item) {
                                is AppUsageItem -> RealActivityListItem(navController = navController, app = item, color = MaterialTheme.colorScheme.primary, progress = progress, isHighlighted = isHighlighted, onLongClick = { onLongPress(0) })
                                is OfflineActivityItem -> MockOfflineListItem(name = item.title, duration = item.timeFormatted, iconName = item.iconName, color = MaterialTheme.colorScheme.primary, progress = progress, isHighlighted = isHighlighted, onClick = { navController.navigate(NavRouteCodec.offlineDetailsRoute(item.id)) }, onLongClick = { onLongPress(1) })
                                // Unreachable today: the unified list carries only apps and
                                // offline items, and screen-off spans surface in the Gaps
                                // screen's unaccounted timeline (2026-10-22), not here. The
                                // branch exists so the sealed interface stays exhaustive if
                                // that ever changes (functional sweep Finding 19).
                                is ScreenOffItem -> MockOfflineListItem(name = item.title, duration = item.timeFormatted, iconName = "Sleep", color = MaterialTheme.colorScheme.primary, progress = progress, isHighlighted = isHighlighted, onClick = { }, onLongClick = { onLongPress(1) })
                            }
                        }
                    }
                }
            }
            
            AnimatedVisibility(visible = uiState.isLoading, enter = fadeIn(tween(200)), exit = fadeOut(tween(400)), modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)).pointerInput(Unit) { detectTapGestures {  } }, contentAlignment = Alignment.Center) { HatLoadingSpinner(MaterialTheme.colorScheme.primary) }
            }
        } // Scaffold content Box
    } // Scaffold
} // DashboardScreen
