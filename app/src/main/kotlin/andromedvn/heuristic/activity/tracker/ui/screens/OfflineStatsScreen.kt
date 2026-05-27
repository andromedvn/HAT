package andromedvn.heuristic.activity.tracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.data.*
import andromedvn.heuristic.activity.tracker.ui.components.*
import andromedvn.heuristic.activity.tracker.utils.HeuristicQuotes
import andromedvn.heuristic.activity.tracker.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun OfflineStatsScreen(navController: NavController, viewModel: DashboardViewModel) {
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

    val selectedTitles = remember { mutableStateListOf<String>() }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.timeRange, uiState.dateLabelRelative) {
        viewModel.setChartHighlight(null)
        returnScrollIndex = -1
        returnScrollOffset = 0
        selectedTitles.clear()
        chartScrollState.scrollToItem(0)
    }
    
    val offlineList = remember(uiState.activityList) {
        uiState.activityList.filterIsInstance<OfflineActivityItem>().sortedByDescending { it.durationInMillis }
    }
    
    LaunchedEffect(offlineList) {
        if (chartHighlight != null && offlineList.isNotEmpty() && returnScrollIndex == -1) {
            val matchTag = chartHighlight.point?.syncTag ?: ""
            val index = offlineList.indexOfFirst { it.activeTimeTags.contains(matchTag) }
            if (index != -1) listState.animateScrollToItem(index + 1)
        }
    }

    val handleBack: () -> Unit = {
        if (showDatePicker) { showDatePicker = false }
        else if (selectedTitles.isNotEmpty()) { selectedTitles.clear() } 
        else if (returnScrollIndex != -1) { scope.launch { listState.scrollToItem(returnScrollIndex, returnScrollOffset); returnScrollIndex = -1; viewModel.setChartHighlight(null) } } 
        else { navController.popBackStack() }
    }

    val isResumed = lifecycleState == Lifecycle.State.RESUMED
    BackHandler(enabled = (returnScrollIndex != -1 || selectedTitles.isNotEmpty() || showDatePicker) && isResumed) { handleBack() }

    if (showDeleteDialog) {
        HatOutlinedDialog(onDismissRequest = { showDeleteDialog = false }, title = "Delete Selected?") {
            Text("Permanently remove ${selectedTitles.size} activity logs from this period?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(modifier = Modifier.width(16.dp))
                TextButton(onClick = { 
                    selectedTitles.forEach { title -> viewModel.deleteOfflineActivity(title, uiState.startTime, uiState.endTime) }
                    selectedTitles.clear(); showDeleteDialog = false 
                }) { Text("Delete", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) }
            }
        }
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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0,0,0,0)) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(bottom = innerPadding.calculateBottomPadding())
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { viewModel.setChartHighlight(null); returnScrollIndex = -1; returnScrollOffset = 0 }) }
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .verticalFadingEdges(listState.canScrollBackward, listState.canScrollForward)
            ) {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            if (selectedTitles.isNotEmpty()) {
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { selectedTitles.clear() }) { Icon(Icons.Default.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.onSurface) }
                                        Text("${selectedTitles.size} Selected", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                                    }
                                    TextButton(onClick = { 
                                        val allOffline = uiState.activityList.filterIsInstance<OfflineActivityItem>().map { it.title }
                                        if (selectedTitles.size == allOffline.size) selectedTitles.clear() else { selectedTitles.clear(); selectedTitles.addAll(allOffline) }
                                    }) { Text(if (selectedTitles.size == uiState.activityList.filterIsInstance<OfflineActivityItem>().size) "Deselect All" else "Select All", color = MaterialTheme.colorScheme.primary) }
                                }
                            } else { HatDynamicHeader(title = "Offline Time", subtitle = "Summarized Activities") }
                        
                            TimeSelector(uiState.timeRange) { viewModel.setTimeRange(it) }
                            Spacer(modifier = Modifier.height(24.dp))
                            HatChart(
                                chartScrollState = chartScrollState, animationSalt = uiState.animationSalt,
                                title = "Offline Activity", subTitle = "Tracked time", totalTime = uiState.totalOfflineTimeFormatted, dataPoints = uiState.offlineDetailChartData, palette = listOf(primaryColor), dateLabelRelative = uiState.dateLabelRelative, dateLabelExact = uiState.dateLabelExact, highlightedPoint = chartHighlight, hasNext = uiState.hasNextPeriod, hasPrev = uiState.hasPreviousPeriod, onPrevious = { viewModel.previousDay() }, onNext = { viewModel.nextDay() }, onDateClick = { showDatePicker = true }, onPointSelected = { event -> if (event == null) { viewModel.setChartHighlight(null); returnScrollIndex = -1 } else { viewModel.setChartHighlight(event) } }
                            )
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
                                            chartMode = ChartMode.OFFLINE
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
                            Text("LOGGED ACTIVITIES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    
                    if (offlineList.isEmpty()) {
                        item { Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) { EmptyStateCard(Icons.AutoMirrored.Filled.DirectionsWalk, "Zero Offline Logs", "No offline activities logged for this period.") } }
                    } else {
                        val maxUsage = offlineList.maxOfOrNull { it.durationInMillis }?.toFloat() ?: 1f
                        itemsIndexed(offlineList, key = { _, item -> item.uniqueKey }) { _, item -> 
                            val isSelected = selectedTitles.contains(item.title)
                            val isHighlighted = if (chartHighlight != null) {
                                if (chartHighlight.isFromChart) {
                                    val pointTag = chartHighlight.point?.syncTag ?: ""
                                    (pointTag in item.activeTimeTags) && (chartHighlight.stackIndex == -1 || chartHighlight.stackIndex == 1)
                                } else chartHighlight.sourceKey == item.uniqueKey
                            } else false
                            
                            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                                MockOfflineListItem(name = item.title, duration = item.timeFormatted, iconName = item.iconName, color = primaryColor, progress = item.durationInMillis / maxUsage.coerceAtLeast(1f), isSelectedForAction = isSelected, isHighlighted = isHighlighted, onClick = { if (selectedTitles.isNotEmpty()) { if (isSelected) selectedTitles.remove(item.title) else selectedTitles.add(item.title) } else { navController.navigate("details/offline/${item.title}") } }, onLongClick = { if (selectedTitles.isEmpty()) selectedTitles.add(item.title) }) 
                            }
                        }
                    }
                }
            }
        }
        
        AnimatedVisibility(
            visible = selectedTitles.isNotEmpty(), 
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400, easing = FastOutSlowInEasing)) + fadeIn(tween(400)), 
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400, easing = FastOutSlowInEasing)) + fadeOut(tween(400)), 
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp).windowInsetsPadding(WindowInsets.navigationBars), 
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), 
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background), 
                shape = RoundedCornerShape(24.dp), 
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f, fill=false).padding(end = 16.dp)) {
                        Text("${selectedTitles.size} Selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (selectedTitles.size == 1) {
                            TextButton(onClick = { navController.navigate("details/offline/${selectedTitles.first()}") }) {
                                Text("Edit Label", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { showDeleteDialog = true }) {
                                Text("Delete", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            PromptHighlightButton("Delete Selected") { showDeleteDialog = true }
                        }
                    }
                }
            }
        }
    }
}
