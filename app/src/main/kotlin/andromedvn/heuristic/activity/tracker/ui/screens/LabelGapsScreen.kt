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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.data.UsageGap
import andromedvn.heuristic.activity.tracker.data.TimeRangeLabel
import andromedvn.heuristic.activity.tracker.ui.components.ChartMode
import andromedvn.heuristic.activity.tracker.utils.HeuristicQuotes
import andromedvn.heuristic.activity.tracker.viewmodel.DashboardViewModel
import andromedvn.heuristic.activity.tracker.ui.components.*
import andromedvn.heuristic.activity.tracker.utils.TimeUtils
import kotlinx.coroutines.launch
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LabelGapsScreen(navController: NavController, dashboardViewModel: DashboardViewModel) {
    val uiState by dashboardViewModel.uiState.collectAsState()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showMinorGaps by rememberSaveable { mutableStateOf(false) }
    var showCascadeDialog by rememberSaveable { mutableStateOf(false) }
    var showIdleActionDialog by rememberSaveable { mutableStateOf(false) }
    var showRestorationDialog by rememberSaveable { mutableStateOf(false) }
    
    var cascadeStart by rememberSaveable { mutableLongStateOf(-1L) }
    var cascadeEnd by rememberSaveable { mutableLongStateOf(-1L) }
    var cascadeTrueStart by rememberSaveable { mutableLongStateOf(-1L) }
    var cascadeTrueEnd by rememberSaveable { mutableLongStateOf(-1L) }
    var cascadeTitle by rememberSaveable { mutableStateOf("") }
    var cascadeIcon by rememberSaveable { mutableStateOf("") }
    
    val selectedGaps = remember { mutableStateListOf<UsageGap>() }
    var singleGapStart by rememberSaveable { mutableLongStateOf(-1L) }
    var singleGapEnd by rememberSaveable { mutableLongStateOf(-1L) }
    var ghostPkg by rememberSaveable { mutableStateOf("") }
    var ghostAppName by rememberSaveable { mutableStateOf("") }
    
    val listState = rememberLazyListState()
    val chartScrollState = rememberLazyListState()
    var chartHighlight by remember { mutableStateOf<HighlightEvent?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    var returnScrollIndex by rememberSaveable { mutableIntStateOf(-1) }
    var returnScrollOffset by rememberSaveable { mutableIntStateOf(0) }

    val todayStartMillis = getStartOfDay(Calendar.getInstance().apply { timeInMillis = uiState.startTime })

    LaunchedEffect(uiState.timeRange, uiState.dateLabelRelative) {
        chartHighlight = null
        returnScrollIndex = -1
        returnScrollOffset = 0
        chartScrollState.scrollToItem(0)
    }

    val handleBack: () -> Unit = {
        if (showDatePicker) { showDatePicker = false }
        else if (showRestorationDialog) { showRestorationDialog = false }
        else if (showIdleActionDialog) { showIdleActionDialog = false; ghostPkg = ""; ghostAppName = ""; singleGapStart = -1L; singleGapEnd = -1L }
        else if (showCascadeDialog) { showCascadeDialog = false; cascadeStart = -1L; cascadeEnd = -1L; cascadeTrueStart = -1L; cascadeTrueEnd = -1L; cascadeTitle = ""; cascadeIcon = "" }
        else if (showDialog) { showDialog = false; singleGapStart = -1L; singleGapEnd = -1L; ghostPkg = ""; ghostAppName = "" }
        else if (returnScrollIndex != -1) { scope.launch { listState.scrollToItem(returnScrollIndex, returnScrollOffset); returnScrollIndex = -1; chartHighlight = null } } 
        else if (selectedGaps.isNotEmpty()) { selectedGaps.clear() } 
        else { navController.popBackStack() }
    }

    val isResumed = lifecycleState == Lifecycle.State.RESUMED
    BackHandler(enabled = isResumed && (returnScrollIndex != -1 || selectedGaps.isNotEmpty() || showDialog || showCascadeDialog || showIdleActionDialog || showRestorationDialog || showDatePicker)) { handleBack() }

    if (showDatePicker) {
        HatDatePickerModal(
            initialDate = uiState.startTime,
            oldestData = uiState.oldestDataTimestamp,
            bypassHistoryLimit = uiState.bypassHistoryLimit,
            onDismiss = { showDatePicker = false },
            onDateSelected = { date -> dashboardViewModel.jumpToDate(date) }
        )
    }
    
    if (showRestorationDialog) {
        HatOutlinedDialog(onDismissRequest = { showRestorationDialog = false }, title = "Dismissed Sessions") {
            if (uiState.dismissedIdleSessions.isEmpty()) {
                EmptyStateCard(icon = Icons.Default.History, title = "No Dismissed Sessions", subtitle = "No dismissed idle sessions for this period.")
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PromptHighlightButton("Close") { showRestorationDialog = false }
                }
            } else {
                Text("These idle sessions were previously verified as active app usage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(uiState.dismissedIdleSessions) { ghost ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ghost.appName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                                Text("${sdf.format(ghost.startMillis)} - ${sdf.format(ghost.endMillis)} (${TimeUtils.formatDuration(ghost.endMillis - ghost.startMillis)})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { 
                                dashboardViewModel.restoreDismissedGhost(ghost.packageName, ghost.startMillis, ghost.endMillis)
                                if (uiState.dismissedIdleSessions.size <= 1) showRestorationDialog = false
                            }) { Text("Restore", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PromptHighlightButton("Close") { showRestorationDialog = false }
                }
            }
        }
    }

    if (showCascadeDialog) {
        HatOutlinedDialog(onDismissRequest = { showCascadeDialog = false }, title = "Continuous Gap") {
            val sStr = "${SimpleDateFormat("h:mm a", Locale.getDefault()).format(cascadeTrueStart)}${getDayOffsetString(cascadeTrueStart, todayStartMillis)}"
            val eStr = "${SimpleDateFormat("h:mm a", Locale.getDefault()).format(cascadeTrueEnd)}${getDayOffsetString(cascadeTrueEnd, todayStartMillis)}"
            Text("Time Range: $sStr to $eStr\nContinuous span: ${TimeUtils.formatDuration(cascadeTrueEnd - cascadeTrueStart)}\nNote: This gap crosses midnight — HAT splits it at the day boundary.\n\nApply '$cascadeTitle' to the entire block?", color = MaterialTheme.colorScheme.onSurfaceVariant) 
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { 
                    if (ghostPkg.isNotEmpty()) dashboardViewModel.convertGhostTime(ghostPkg, cascadeStart, cascadeEnd, cascadeTitle, cascadeIcon)
                    else dashboardViewModel.saveGap(cascadeStart, cascadeEnd, cascadeTitle, cascadeIcon)
                    showCascadeDialog = false; navController.popBackStack() 
                }) { Text("Just This Day", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(modifier = Modifier.width(16.dp))
                TextButton(onClick = { 
                    if (ghostPkg.isNotEmpty()) dashboardViewModel.convertGhostTime(ghostPkg, cascadeTrueStart, cascadeTrueEnd, cascadeTitle, cascadeIcon)
                    else dashboardViewModel.saveGap(cascadeTrueStart, cascadeTrueEnd, cascadeTitle, cascadeIcon)
                    showCascadeDialog = false; navController.popBackStack() 
                }) { Text("Entire Block", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
    
    if (showIdleActionDialog) {
        HatOutlinedDialog(onDismissRequest = { showIdleActionDialog = false; ghostPkg = ""; ghostAppName = ""; singleGapStart = -1L; singleGapEnd = -1L }, title = "Idle Session") {
            Text("$ghostAppName was on screen for a long stretch with no real interaction — possibly left open, playing audio, or idle.\n\nDid you actually use it? Dismiss if yes. Label if you were doing something else.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { dashboardViewModel.dismissGhost(ghostPkg, singleGapStart, singleGapEnd); showIdleActionDialog = false; ghostPkg = ""; ghostAppName = ""; singleGapStart = -1L; singleGapEnd = -1L }) { Text("Dismiss", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(modifier = Modifier.width(16.dp))
                TextButton(onClick = { showIdleActionDialog = false; showDialog = true }) { Text("Label", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }

    if (showDialog) {
        val ledgerContext = when {
            selectedGaps.isNotEmpty() -> LedgerContext.BatchMerge(selectedGaps.size, selectedGaps.sumOf { it.durationInMillis })
            ghostPkg.isNotEmpty() -> LedgerContext.IdleSession(singleGapStart, singleGapEnd, ghostAppName, ghostPkg)
            else -> LedgerContext.StandardGap(singleGapStart, singleGapEnd)
        }
        
        ActivityEditorDialog(
            initialTitle = if(ghostPkg.isNotEmpty()) "Sleep" else "", initialIconName = if(ghostPkg.isNotEmpty()) "Sleep" else "Walk", suggestions = uiState.recentSuggestions, 
            ledgerContext = ledgerContext,
            onDismiss = { showDialog = false; singleGapStart = -1L; singleGapEnd = -1L; ghostPkg = ""; ghostAppName = "" }, 
            onSave = { outTitle, outIcon -> 
                if (singleGapStart != -1L) {
                    scope.launch {
                        dashboardViewModel.resolveContiguousGap(singleGapStart, singleGapEnd) { tS, tE ->
                            if (tS < singleGapStart || tE > singleGapEnd) {
                                cascadeStart = singleGapStart; cascadeEnd = singleGapEnd; cascadeTrueStart = tS; cascadeTrueEnd = tE; cascadeTitle = outTitle; cascadeIcon = outIcon
                                showDialog = false; showCascadeDialog = true
                            } else {
                                if (ghostPkg.isNotEmpty()) dashboardViewModel.convertGhostTime(ghostPkg, singleGapStart, singleGapEnd, outTitle, outIcon)
                                else dashboardViewModel.saveGap(singleGapStart, singleGapEnd, outTitle, outIcon)
                                showDialog = false; navController.popBackStack()
                            }
                        }
                    }
                } else if (selectedGaps.isNotEmpty()) {
                    dashboardViewModel.saveMultipleGaps(selectedGaps.toList(), outTitle, outIcon)
                    selectedGaps.clear()
                    showDialog = false; navController.popBackStack()
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0,0,0,0)) { innerPadding ->
            Column(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()).fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).pointerInput(Unit) { detectTapGestures(onTap = { chartHighlight = null; returnScrollIndex = -1; returnScrollOffset = 0 }) }.verticalFadingEdges(listState.canScrollBackward, listState.canScrollForward)) {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            HatDynamicHeader(title = "Gaps", subtitle = "Manage") {
                                if (uiState.dismissedIdleSessions.isNotEmpty()) {
                                    IconButton(onClick = { showRestorationDialog = true }) {
                                        Icon(Icons.Default.Restore, contentDescription = "Restore Dismissed", tint = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                            Box(modifier = Modifier.fillMaxWidth().height(52.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(50)).padding(4.dp)) {
                                Box(modifier = Modifier.fillMaxHeight().aspectRatio(1f).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha=0.15f)).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Tracked Offline Gaps", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            val totalGapMillis = uiState.majorGaps.sumOf { it.durationInMillis } + uiState.minorGaps.sumOf { it.durationInMillis }
                            val displayTotal = if (uiState.timeRange == TimeRangeLabel.DAY) TimeUtils.formatDuration(totalGapMillis) else "---"
                            HatChart(
                                chartScrollState = chartScrollState, animationSalt = uiState.animationSalt,
                                title = "Gap History", totalTime = displayTotal, dataPoints = uiState.gapChartData, dateLabelRelative = uiState.dateLabelRelative, dateLabelExact = uiState.dateLabelExact, hasNext = uiState.hasNextPeriod, hasPrev = uiState.hasPreviousPeriod, highlightedPoint = chartHighlight, onPrevious = { dashboardViewModel.previousDay() }, onNext = { dashboardViewModel.nextDay() }, onDateClick = { showDatePicker = true }, onPointSelected = { chartHighlight = it }
                            )
                            
                            val currentHighlight = chartHighlight
                            val showHint = currentHighlight != null && currentHighlight.point?.values?.all { it <= 0f } == true
                            AnimatedVisibility(visible = showHint, enter = fadeIn(tween(400)), exit = fadeOut(tween(400))) {
                                Column(modifier = Modifier.padding(top = 16.dp)) {
                                    val isFuture = currentHighlight?.point?.isFuture == true
                                    val quote = remember(currentHighlight) {
                                        HeuristicQuotes.getQuote(
                                            point = currentHighlight?.point,
                                            totalScreenTimeMillis = uiState.totalScreenTimeMillis,
                                            isDayView = uiState.timeRange == TimeRangeLabel.DAY,
                                            isDataReliable = uiState.isDataReliable,
                                            hasSeenHistoryLimitWarning = uiState.hasSeenHistoryLimitWarning,
                                            osRetentionDays = uiState.osRetentionDays,
                                            chartMode = ChartMode.GAPS
                                        )
                                    }
                                    LaunchedEffect(currentHighlight) {
                                        if (!uiState.isDataReliable && !isFuture && !uiState.hasSeenHistoryLimitWarning) {
                                            dashboardViewModel.markHistoryLimitWarningSeen()
                                        }
                                    }
                                    HeuristicTipCard(quote = quote)
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                    
                    if (uiState.idleSessions.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                Text("SUSPICIOUS IDLE SESSIONS (${uiState.idleSessions.size})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                        itemsIndexed(uiState.idleSessions, key = { _, idle -> "idle_${idle.gap.startMillis}" }) { index, idle ->
                            val isLast = index == uiState.idleSessions.lastIndex
                            val pinOffset = 16.dp + with(LocalDensity.current) { 8.sp.toDp() }
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(IntrinsicSize.Min)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(32.dp).fillMaxHeight()) {
                                    if (index > 0) Box(modifier = Modifier.width(2.dp).height(pinOffset).background(MaterialTheme.colorScheme.primary.copy(alpha=0.5f))) else Spacer(modifier = Modifier.height(pinOffset))
                                    Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background).border(4.dp, MaterialTheme.colorScheme.primary, CircleShape))
                                    if (!isLast) Box(modifier = Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.primary.copy(alpha=0.5f))) else Spacer(modifier = Modifier.weight(1f))
                                }
                                Card(
                                    modifier = Modifier.weight(1f).padding(bottom = 12.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                                        .clickable { 
                                            dashboardViewModel.loadSuggestionsForHour(Calendar.getInstance().apply { timeInMillis = idle.gap.startMillis }.get(Calendar.HOUR_OF_DAY))
                                            ghostPkg = idle.packageName
                                            ghostAppName = idle.appName
                                            singleGapStart = idle.gap.startMillis
                                            singleGapEnd = idle.gap.endMillis
                                            showIdleActionDialog = true
                                        }, 
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha=0.05f))
                                ) {
                                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) { 
                                            Text(idle.gap.timeRangeFormatted, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Idle Session Detected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha=0.15f)).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp)) { Text(idle.gap.durationFormatted, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false) }
                                    }
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }

                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text("MAJOR GAPS (${uiState.majorGaps.size}) — ${TimeUtils.formatDuration(uiState.majorGaps.sumOf{it.durationInMillis})}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    if (uiState.majorGaps.isEmpty() && uiState.minorGaps.isEmpty() && uiState.idleSessions.isEmpty()) {
                        item { Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) { EmptyStateCard(Icons.Default.CheckCircle, "Timeline Complete", "Your timeline is fully tracked! No significant gaps found today.") } }
                    } else {
                        itemsIndexed(uiState.majorGaps, key = { _, gap -> "${gap.startMillis}_${gap.endMillis}" }) { index, gap ->
                            val isLast = index == uiState.majorGaps.lastIndex && uiState.minorGaps.isEmpty()
                            val pinOffset = 16.dp + with(LocalDensity.current) { 8.sp.toDp() }
                            val isSelected = selectedGaps.contains(gap)
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(IntrinsicSize.Min)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(32.dp).fillMaxHeight()) {
                                    if (index > 0 || uiState.idleSessions.isNotEmpty()) Box(modifier = Modifier.width(2.dp).height(pinOffset).background(MaterialTheme.colorScheme.primary.copy(alpha=0.5f))) else Spacer(modifier = Modifier.height(pinOffset))
                                    Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background).border(4.dp, MaterialTheme.colorScheme.primary, CircleShape))
                                    if (!isLast) Box(modifier = Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.primary.copy(alpha=0.5f))) else Spacer(modifier = Modifier.weight(1f))
                                }
                                Card(
                                    modifier = Modifier.weight(1f).padding(bottom = 12.dp).border(if(isSelected) 2.dp else 1.dp, if(isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                                        .combinedClickable(
                                            onClick = { 
                                                if (selectedGaps.isNotEmpty()) {
                                                    if (isSelected) selectedGaps.remove(gap) else selectedGaps.add(gap)
                                                } else {
                                                    dashboardViewModel.loadSuggestionsForHour(Calendar.getInstance().apply { timeInMillis = gap.startMillis }.get(Calendar.HOUR_OF_DAY))
                                                    singleGapStart = gap.startMillis
                                                    singleGapEnd = gap.endMillis
                                                    showDialog = true
                                                }
                                            }, 
                                            onLongClick = { 
                                                if (selectedGaps.isEmpty()) selectedGaps.add(gap)
                                            }
                                        ), 
                                    colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha=0.15f) else Color.Transparent)
                                ) {
                                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) { 
                                            Text(gap.timeRangeFormatted, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Unlabeled Gap", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                if (gap.hasHiddenAppFootprint) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                    Text("Contains hidden app", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                        Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha=0.15f)).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp)) { Text(gap.durationFormatted, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false) }
                                    }
                                }
                            }
                        }
                        
                        if (uiState.minorGaps.isNotEmpty()) {
                            item {
                                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth().clickable { showMinorGaps = !showMinorGaps }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("MINOR GAPS (${uiState.minorGaps.size}) — ${TimeUtils.formatDuration(uiState.minorGaps.sumOf{it.durationInMillis})}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        Icon(if (showMinorGaps) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            
                            if (showMinorGaps) {
                                itemsIndexed(uiState.minorGaps, key = { _, gap -> "minor_${gap.startMillis}_${gap.endMillis}" }) { index, gap ->
                                    val isLast = index == uiState.minorGaps.lastIndex
                                    val pinOffset = 16.dp + with(LocalDensity.current) { 8.sp.toDp() }
                                    val isSelected = selectedGaps.contains(gap)
                                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(IntrinsicSize.Min)) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(32.dp).fillMaxHeight()) {
                                            Box(modifier = Modifier.width(2.dp).height(pinOffset).background(MaterialTheme.colorScheme.primary.copy(alpha=0.5f)))
                                            Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background).border(4.dp, MaterialTheme.colorScheme.primary, CircleShape))
                                            if (!isLast) Box(modifier = Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.primary.copy(alpha=0.5f))) else Spacer(modifier = Modifier.weight(1f))
                                        }
                                        Card(
                                            modifier = Modifier.weight(1f).padding(bottom = 12.dp).border(if(isSelected) 2.dp else 1.dp, if(isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha=0.3f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                                                .combinedClickable(
                                                    onClick = { 
                                                        if (selectedGaps.isNotEmpty()) {
                                                            if (isSelected) selectedGaps.remove(gap) else selectedGaps.add(gap)
                                                        } else {
                                                            dashboardViewModel.loadSuggestionsForHour(Calendar.getInstance().apply { timeInMillis = gap.startMillis }.get(Calendar.HOUR_OF_DAY))
                                                            singleGapStart = gap.startMillis
                                                            singleGapEnd = gap.endMillis
                                                            showDialog = true
                                                        }
                                                    }, 
                                                    onLongClick = { 
                                                        if (selectedGaps.isEmpty()) selectedGaps.add(gap)
                                                    }
                                                ), 
                                            colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha=0.15f) else Color.Transparent)
                                        ) {
                                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) { 
                                                    Text(gap.timeRangeFormatted, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text("Unlabeled Gap", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        if (gap.hasHiddenAppFootprint) {
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                                                            Spacer(modifier = Modifier.width(2.dp))
                                                            Text("Contains hidden app", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        }
                                                    }
                                                }
                                                Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha=0.15f)).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.3f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp)) { Text(gap.durationFormatted, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false) }
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
        
        AnimatedVisibility(visible = selectedGaps.isNotEmpty(), enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400, easing = FastOutSlowInEasing)) + fadeIn(tween(400)), exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400, easing = FastOutSlowInEasing)) + fadeOut(tween(400)), modifier = Modifier.align(Alignment.BottomCenter)) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp).windowInsetsPadding(WindowInsets.navigationBars), 
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), 
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background), 
                shape = RoundedCornerShape(24.dp), 
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f, fill=false).padding(end = 16.dp)) { 
                        Text("${selectedGaps.size} Gaps Selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(TimeUtils.formatDuration(selectedGaps.sumOf { it.durationInMillis }), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                    }
                    Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        PromptHighlightButton("Merge & Label") { 
                            if (selectedGaps.isNotEmpty()) { 
                                dashboardViewModel.loadSuggestionsForHour(Calendar.getInstance().apply { timeInMillis = selectedGaps.first().startMillis }.get(Calendar.HOUR_OF_DAY))
                                showDialog = true 
                            } 
                        }
                    }
                }
            }
        }
    }
}
