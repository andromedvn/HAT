package andromedvn.heuristic.activity.tracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.data.*
import andromedvn.heuristic.activity.tracker.ui.components.*
import andromedvn.heuristic.activity.tracker.utils.TimeUtils
import andromedvn.heuristic.activity.tracker.viewmodel.*
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ActivityDetailsScreen(navController: NavController, type: String, id: String, dashboardViewModel: DashboardViewModel, repository: ActivityRepository) {
    val context = LocalContext.current
    val detailsViewModel: ActivityDetailsViewModel = viewModel(factory = ActivityDetailsViewModelFactory(repository, context.applicationContext))
    val dashState by dashboardViewModel.uiState.collectAsState()
    val uiState by detailsViewModel.uiState.collectAsState()

    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    var showHideDialog by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val chartHighlight = dashState.chartHighlight
    val listState = rememberLazyListState()
    val chartScrollState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    var returnScrollIndex by rememberSaveable { mutableIntStateOf(-1) }
    var returnScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    
    // Which evidence the idle dialog is presenting (2026-10-23): true for an
    // untouched-app Idle Session, false for Lock Screen Activity. The details
    // screen previously knew only lock ghosts, which made every new idle span
    // invisible to long-press and the maintainer's conversion dead-ended
    // (functional sweep Finding 1).
    // The SESSION the long-pressed span lives in (2026-10-23). The session
    // row shows the app's whole foreground stretch while the dialog asks
    // about the untouched tail; naming both ended the "2h 16m card says
    // 42m 19s" contradiction the maintainer hit on CloudStream.
    var sessionStart by rememberSaveable { mutableLongStateOf(-1L) }
    var sessionEnd by rememberSaveable { mutableLongStateOf(-1L) }
    
    val selectedSessions = remember { mutableStateListOf<SessionItem>() }
    var showSessionDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(dashState.timeRange, dashState.dateLabelRelative) {
        dashboardViewModel.setChartHighlight(null)
        returnScrollIndex = -1
        returnScrollOffset = 0
        selectedSessions.clear()
        chartScrollState.scrollToItem(0)
    }

    val handleBack: () -> Unit = {
        if (showDatePicker) { showDatePicker = false }
        else if (showSessionDialog) { showSessionDialog = false }
        else if (selectedSessions.isNotEmpty()) { selectedSessions.clear() }
        else if (chartHighlight != null) { dashboardViewModel.setChartHighlight(null) }
        else if (returnScrollIndex != -1) { scope.launch { listState.scrollToItem(returnScrollIndex, returnScrollOffset); returnScrollIndex = -1; dashboardViewModel.setChartHighlight(null) } } 
        else { navController.popBackStack() }
    }

    val isResumed = lifecycleState == Lifecycle.State.RESUMED
    BackHandler(enabled = (returnScrollIndex != -1 || showSessionDialog || selectedSessions.isNotEmpty() || chartHighlight != null || showDatePicker) && !showEditDialog && !showDeleteDialog && !showHideDialog && isResumed) { handleBack() }

    LaunchedEffect(type, id, dashState.startTime, dashState.endTime, dashState.timeRange) { 
        detailsViewModel.loadDetails(type, id, dashState.startTime, dashState.endTime, dashState.timeRange) 
    }

    if (showDatePicker) {
        HatDatePickerModal(
            initialDate = dashState.startTime,
            oldestData = dashState.oldestDataTimestamp,
            bypassHistoryLimit = dashState.bypassHistoryLimit,
            onDismiss = { showDatePicker = false },
            onDateSelected = { date -> dashboardViewModel.jumpToDate(date) }
        )
    }

    if (showDeleteDialog) {
        HatOutlinedDialog(onDismissRequest = { showDeleteDialog = false }, title = "Delete?") {
            Text("Permanently remove this activity?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(modifier = Modifier.width(16.dp))
                TextButton(onClick = { detailsViewModel.deleteActivity(id, dashState.startTime, dashState.endTime) { showDeleteDialog = false; dashboardViewModel.loadDashboardData(force = true); navController.popBackStack() } }) { Text("Delete", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
    
    if (showEditDialog && uiState.activity is OfflineActivityItem) {
        val offItem = uiState.activity as OfflineActivityItem
        ActivityEditorDialog(initialTitle = offItem.title, initialIconName = offItem.iconName, suggestions = emptyList(), ledgerContext = null, onDismiss = { showEditDialog = false }, onSave = { newTitle, newIcon, _ -> detailsViewModel.updateActivity(offItem.title, newTitle, newIcon, dashState.startTime, dashState.endTime) { showEditDialog = false; dashboardViewModel.loadDashboardData(force = true); navController.popBackStack() } })
    }
    
    if (showHideDialog) {
        HatOutlinedDialog(onDismissRequest = { showHideDialog = false }, title = "Hide App?") {
            Text("This app will be hidden from your dashboard. You can restore it in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showHideDialog = false }) { Text("Cancel", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(modifier = Modifier.width(16.dp))
                TextButton(onClick = { detailsViewModel.hideApp(id) { showHideDialog = false; dashboardViewModel.loadDashboardData(force = true); navController.popBackStack() } }) { Text("Hide App", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }



    // Batch conversion dialog (2026-10-23): the Convert button has been a
    // silent dead end since v1.0.0 (Finding 2), setting a flag nothing
    // rendered while the convert function sat uncalled. It exists now.
    if (showSessionDialog && selectedSessions.isNotEmpty()) {
        val pkgForBatch = (uiState.activity as? AppUsageItem)?.packageName
        if (pkgForBatch != null) {
            ActivityEditorDialog(
                initialTitle = "", initialIconName = "Walk",
                suggestions = dashState.recentSuggestions,
                ledgerContext = LedgerContext.BatchMerge(selectedSessions.size, selectedSessions.sumOf { it.displayedDuration }),
                onDismiss = { showSessionDialog = false },
                onSave = { outTitle, outIcon, _ ->
                    dashboardViewModel.saveMultipleGaps(selectedSessions.map { UsageGap(it.startTime, it.endTime) }, outTitle, outIcon)
                    selectedSessions.clear()
                    showSessionDialog = false
                    navController.popBackStack()
                }
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0,0,0,0)) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()).windowInsetsPadding(WindowInsets.statusBars).pointerInput(Unit) { detectTapGestures(onTap = { dashboardViewModel.setChartHighlight(null); returnScrollIndex = -1; returnScrollOffset = 0 }) }.verticalFadingEdges(listState.canScrollBackward, listState.canScrollForward)) {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            if (type == "app") HatDynamicHeader(title = "App Sessions", subtitle = "Detailed Breakdown") { IconButton(onClick = { showHideDialog = true }) { Icon(Icons.Default.VisibilityOff, contentDescription = "Hide", tint = MaterialTheme.colorScheme.primary) } }
                            else HatDynamicHeader(title = "Breakdown", subtitle = "Detailed Offline Activity") { IconButton(onClick = { showEditDialog = true }) { Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary) }; IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.primary) } }
                        }
                    }

                    item {
                        val itemTitle = uiState.activity?.title ?: id
                        val itemPack = (uiState.activity as? AppUsageItem)?.packageName ?: id
                        val iconName = (uiState.activity as? OfflineActivityItem)?.iconName ?: ""
                        val chartDataPoints = uiState.chartData
                        val timeFmt = uiState.activity?.timeFormatted ?: "0m"
                        
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Box(modifier = Modifier.fillMaxWidth().height(56.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(4.dp)) {
                                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.fillMaxHeight().aspectRatio(1f).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                                        if (type == "app") AsyncAppIcon(packageName = itemPack, modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary)
                                        else Icon(ICON_REGISTRY[iconName] ?: Icons.Default.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                        Text(itemTitle, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (type == "app") Text(itemPack, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        else Text("Offline Activity", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), maxLines = 1)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(32.dp))

                            HatChart(
                                chartScrollState = chartScrollState, animationSalt = uiState.animationSalt,
                                title = "History", totalTime = timeFmt, dataPoints = chartDataPoints, highlightedPoint = chartHighlight, hasNext = dashState.hasNextPeriod, hasPrev = dashState.hasPreviousPeriod, dateLabelRelative = dashState.dateLabelRelative, dateLabelExact = dashState.dateLabelExact, onPrevious = { dashboardViewModel.previousDay() }, onNext = { dashboardViewModel.nextDay() }, onDateClick = { showDatePicker = true },
                                onPointSelected = { event ->
                                    val pt = event?.point
                                    if (event != null && pt != null) {
                                        if (returnScrollIndex == -1) { returnScrollIndex = listState.firstVisibleItemIndex; returnScrollOffset = listState.firstVisibleItemScrollOffset }
                                        dashboardViewModel.setChartHighlight(event)
                                        // Match on the locale-independent bucket key. The old
                                        // comparison formatted the session start with
                                        // getTimeRangeStr (Locale.getDefault) and substring-matched
                                        // it, so on a non-English device the strings differed and
                                        // no row was ever found.
                                        val matchIndex = uiState.sessionList.indexOfFirst { chartSyncKey(it.startTime, dashState.timeRange) == pt.syncTag }
                                        // Two items precede the sessions: the dynamic header and the
                                        // block holding the chart plus the SESSIONS caption. +1 landed
                                        // on that SESSIONS caption item, one row above the target.
                                        // Exactly two items precede the sessions: the dynamic
                                        // header and the block carrying the icon row, the chart
                                        // and the SESSIONS caption. So +2 is the first session row.
                                        //
                                        // There is deliberately no term for ghostIntervals.size.
                                        // A ghost is not a row of its own - it only annotates an
                                        // existing session (isGhost, below) - so adding its count
                                        // here overshot the list by however many ghosts there were.
                                        if (matchIndex != -1) scope.launch { listState.animateScrollToItem(matchIndex + 2) }
                                    } else { dashboardViewModel.setChartHighlight(null); returnScrollIndex = -1 }
                                }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            val sessSize = uiState.sessionList.size
                            Text("SESSIONS ($sessSize clustered)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    
                    val renderList = uiState.sessionList
                    val appActivity = uiState.activity as? AppUsageItem

                    if (renderList.isEmpty() && (uiState.activity == null || uiState.activity!!.durationInMillis <= 0L)) {
                        item { Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) { EmptyStateCard(icon = if (type == "app") Icons.Default.Smartphone else Icons.AutoMirrored.Filled.DirectionsWalk, title = "No Activity", subtitle = "No sessions or offline logs for this on the selected date.") } }
                    } else if (!uiState.isLoading) {
                        if (renderList.isEmpty()) {
                            item { Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) { EmptyStateCard(Icons.Default.Splitscreen, "No Session Data", "No sessions recorded for this activity") } }
                        } else {
                            itemsIndexed(renderList, key = { _, session -> "${session.startTime}_${session.endTime}" }) { index, session ->
                                val isLast = index == renderList.lastIndex
                                val pinOffset = 16.dp + with(LocalDensity.current) { 8.sp.toDp() }
                                val isSessionSelected = selectedSessions.contains(session)

                                fun handleLongPress(s: SessionItem) {
                                    if (type == "app" && selectedSessions.isEmpty()) {
                                        selectedSessions.add(s)
                                    }
                                }

                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(IntrinsicSize.Min)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(32.dp).fillMaxHeight()) {
                                        if (index > 0) Box(modifier = Modifier.width(2.dp).height(pinOffset).background(MaterialTheme.colorScheme.primary.copy(alpha=0.5f))) else Spacer(modifier = Modifier.height(pinOffset))
                                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background).border(4.dp, MaterialTheme.colorScheme.primary, CircleShape))
                                        if (!isLast) Box(modifier = Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.primary.copy(alpha=0.5f))) else Spacer(modifier = Modifier.weight(1f))
                                    }
                                    Card(
                                        modifier = Modifier.weight(1f).padding(bottom = 12.dp)
                                            .border(if(isSessionSelected) 2.dp else 1.dp, MaterialTheme.colorScheme.primary.copy(alpha=if(isSessionSelected) 1f else 0.5f), RoundedCornerShape(16.dp))
                                            .clip(RoundedCornerShape(16.dp))
                                            .combinedClickable(
                                                onClick = { if (selectedSessions.isNotEmpty()) { if (isSessionSelected) selectedSessions.remove(session) else selectedSessions.add(session) } },
                                                onLongClick = { handleLongPress(session) }
                                            ), 
                                        colors = CardDefaults.cardColors(containerColor = if(isSessionSelected) MaterialTheme.colorScheme.primary.copy(alpha=0.05f) else Color.Transparent)
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column {
                                                Text(session.getTimeRangeStr(dashState.timeRange), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                                                
                                            }
                                            Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                                Text(session.activeDurationStr, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = selectedSessions.isNotEmpty(),
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }, animationSpec = tween(400, easing = FastOutSlowInEasing)) + fadeIn(tween(400)),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400, easing = FastOutSlowInEasing)) + fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp).windowInsetsPadding(WindowInsets.navigationBars),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))) {
                    Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f, fill=false).padding(end = 16.dp)) { 
                            Text("${selectedSessions.size} Selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Must be displayedDuration (active sum), not spanDuration
                            // (wall span), or the header total exceeds the sum of the
                            // session rows below it whenever a cluster has an internal gap.
                            Text(TimeUtils.formatDuration(selectedSessions.sumOf { it.displayedDuration }), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            PromptHighlightButton("Convert") { 
                                dashboardViewModel.loadSuggestionsForHour(Calendar.getInstance().apply { timeInMillis = selectedSessions.first().startTime }.get(Calendar.HOUR_OF_DAY))
                                showSessionDialog = true 
                            }
                        }
                    }
                }
            }
        }
    }
}
