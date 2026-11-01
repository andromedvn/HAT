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
import androidx.compose.material.icons.outlined.Warning
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.data.UsageGap
import andromedvn.heuristic.activity.tracker.data.ScreenOffItem
import andromedvn.heuristic.activity.tracker.data.TimeRangeLabel
import andromedvn.heuristic.activity.tracker.domain.AppTourState
import andromedvn.heuristic.activity.tracker.domain.DashboardTourSteps
import andromedvn.heuristic.activity.tracker.ui.components.ChartMode
import andromedvn.heuristic.activity.tracker.utils.HeuristicQuotes
import andromedvn.heuristic.activity.tracker.viewmodel.DashboardViewModel
import andromedvn.heuristic.activity.tracker.viewmodel.IdleSession
import andromedvn.heuristic.activity.tracker.ui.components.*
import andromedvn.heuristic.activity.tracker.utils.TimeUtils
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LabelGapsScreen(navController: NavController, dashboardViewModel: DashboardViewModel) {
    val tourRegistry = androidx.compose.runtime.remember { TourRegistry() }
    val uiState by dashboardViewModel.uiState.collectAsState()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showCascadeDialog by rememberSaveable { mutableStateOf(false) }
    var showIdleActionDialog by rememberSaveable { mutableStateOf(false) }
    // Which evidence the idle dialog is presenting (2026-10-21): true for an
    // untouched-app Idle Session (the maintainer's original feature), false
    // for Lock Screen Activity (app active behind a locked phone). Both ask
    // the same two questions; the card names its evidence.
    var idleDialogIsUnattended by rememberSaveable { mutableStateOf(false) }
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

    // Screen-Off Activity resolution state. Same grammar as the idle sessions:
    // evidence attached, verdict left to the human, two exits, no third option.
    var showScreenOffDialog by rememberSaveable { mutableStateOf(false) }
    var screenOffPkg by rememberSaveable { mutableStateOf("") }
    var screenOffAppName by rememberSaveable { mutableStateOf("") }
    var screenOffStart by rememberSaveable { mutableLongStateOf(-1L) }
    var screenOffEnd by rememberSaveable { mutableLongStateOf(-1L) }

    // The gap the user tapped to label, so the dialog can show what was
    // before and after it. The gap cards already display this context; the
    // dialog should say the same thing (user request, 2026-10-20).
    var lastGapContext by remember { mutableStateOf<UsageGap?>(null) }
    
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
        else if (showScreenOffDialog) { showScreenOffDialog = false; screenOffPkg = ""; screenOffAppName = ""; screenOffStart = -1L; screenOffEnd = -1L }
        else if (showCascadeDialog) { showCascadeDialog = false; cascadeStart = -1L; cascadeEnd = -1L; cascadeTrueStart = -1L; cascadeTrueEnd = -1L; cascadeTitle = ""; cascadeIcon = "" }
        else if (showDialog) { showDialog = false; singleGapStart = -1L; singleGapEnd = -1L; ghostPkg = ""; ghostAppName = "" }
        else if (returnScrollIndex != -1) { scope.launch { listState.scrollToItem(returnScrollIndex, returnScrollOffset); returnScrollIndex = -1; chartHighlight = null } } 
        else if (selectedGaps.isNotEmpty()) { selectedGaps.clear() } 
        else { navController.popBackStack() }
    }

    val isResumed = lifecycleState == Lifecycle.State.RESUMED
    BackHandler(enabled = isResumed && (returnScrollIndex != -1 || selectedGaps.isNotEmpty() || showDialog || showCascadeDialog || showIdleActionDialog || showRestorationDialog || showScreenOffDialog || showDatePicker)) { handleBack() }

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
            if (uiState.dismissedIdleSessions.isEmpty() && uiState.dismissedScreenOffSpans.isEmpty()) {
                EmptyStateCard(icon = Icons.Default.History, title = "No Dismissed Sessions", subtitle = "No dismissed idle sessions or screen-off spans for this period.")
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PromptHighlightButton("Close") { showRestorationDialog = false }
                }
            } else {
                if (uiState.dismissedIdleSessions.isNotEmpty()) {
                    Text("These spans were previously labeled by you as active app usage. Restore them if you changed your mind.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(uiState.dismissedIdleSessions, key = { "idle_${it.packageName}_${it.startMillis}_${it.endMillis}" }) { ghost ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ghost.appName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                                Text("${sdf.format(ghost.startMillis)} - ${sdf.format(ghost.endMillis)} (${TimeUtils.formatDuration(ghost.endMillis - ghost.startMillis)})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = {
                                dashboardViewModel.restoreDismissedGhost(ghost.packageName, ghost.startMillis, ghost.endMillis)
                                if (uiState.dismissedIdleSessions.size <= 1 && uiState.dismissedScreenOffSpans.isEmpty()) showRestorationDialog = false
                            }) { Text("Restore", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    // Screen-off dismissals get the same door (2026-10-21):
                    // until now "Count as usage" on a screen-off span was
                    // permanent, while idle dismissals were always undoable.
                    // Restoring one pulls the span back out of usage and it
                    // returns to the Screen-Off Activity list.
                    if (uiState.dismissedScreenOffSpans.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("These screen-off spans were previously counted back as usage. Restoring one returns it to Screen-Off Activity.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(uiState.dismissedScreenOffSpans, key = { "so_${it.packageName}_${it.startMillis}_${it.endMillis}" }) { span ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(span.appName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                                    Text("${sdf.format(span.startMillis)} - ${sdf.format(span.endMillis)} (${TimeUtils.formatDuration(span.endMillis - span.startMillis)})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = {
                                    dashboardViewModel.restoreDismissedScreenOffSpan(span.packageName, span.startMillis, span.endMillis)
                                    if (uiState.dismissedScreenOffSpans.size <= 1 && uiState.dismissedIdleSessions.isEmpty()) showRestorationDialog = false
                                }) { Text("Restore", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) }
                            }
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
            Text("Runs $sStr to $eStr, about ${TimeUtils.formatDuration(cascadeTrueEnd - cascadeTrueStart)}. It crosses midnight, so HAT already split it.\n\nApply '$cascadeTitle' to the whole thing?", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        HatOutlinedDialog(
            onDismissRequest = { showIdleActionDialog = false; ghostPkg = ""; ghostAppName = ""; singleGapStart = -1L; singleGapEnd = -1L },
            title = if (idleDialogIsUnattended) "Idle Session" else "Lock Screen Activity"
        ) {
            val durationStr = TimeUtils.formatDuration(singleGapEnd - singleGapStart)
            if (idleDialogIsUnattended) {
                val lastTouch = SimpleDateFormat("h:mm a", Locale.getDefault()).format(singleGapStart)
                Text("$ghostAppName was open for $durationStr. You hadn't touched it since $lastTouch. Were you still using it?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("$ghostAppName was active behind the lock screen for $durationStr. The phone never unlocked. Was anyone using it?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(24.dp))
            // Stacked full-width buttons (2026-10-22): side-by-side text
            // buttons wrapped on 720px screens ("No, name what it was"
            // became three lines tall next to a one-line sibling and the
            // pair misaligned). Full width can never wrap and the two
            // answers read top to bottom like a sentence.
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = { dashboardViewModel.dismissGhost(ghostPkg, singleGapStart, singleGapEnd); showIdleActionDialog = false; ghostPkg = ""; ghostAppName = ""; singleGapStart = -1L; singleGapEnd = -1L }) { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Yes, I was using it", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = { showIdleActionDialog = false; showDialog = true }) { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No, name what it was", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) } }
            }
        }
    }

    if (showScreenOffDialog) {
        HatOutlinedDialog(onDismissRequest = { showScreenOffDialog = false; screenOffPkg = ""; screenOffAppName = ""; screenOffStart = -1L; screenOffEnd = -1L }, title = "Screen-Off Activity") {
            val sStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(screenOffStart)
            val eStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(screenOffEnd)
            val durationStr = TimeUtils.formatDuration(screenOffEnd - screenOffStart)
            Text("$screenOffAppName ran from $sStr to $eStr ($durationStr) while the screen was off. That's not screen time. What was it?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))
            // Same stacking as the idle dialog: full width never wraps.
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = { dashboardViewModel.dismissScreenOffSpan(screenOffPkg, screenOffStart, screenOffEnd); showScreenOffDialog = false; screenOffPkg = ""; screenOffAppName = ""; screenOffStart = -1L; screenOffEnd = -1L }) { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("It was really in use", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = { showScreenOffDialog = false; showDialog = true }) { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Name what it was", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) } }
            }
        }
    }

    if (showDialog) {
        val ledgerContext = when {
            selectedGaps.isNotEmpty() -> LedgerContext.BatchMerge(selectedGaps.size, selectedGaps.sumOf { it.durationInMillis })
            screenOffPkg.isNotEmpty() -> LedgerContext.ScreenOff(screenOffStart, screenOffEnd, screenOffAppName, screenOffPkg)
            ghostPkg.isNotEmpty() -> LedgerContext.IdleSession(singleGapStart, singleGapEnd, ghostAppName, ghostPkg, idleDialogIsUnattended)
            else -> LedgerContext.StandardGap(singleGapStart, singleGapEnd, lastGapContext?.precededBy, lastGapContext?.followedBy)
        }

        ActivityEditorDialog(
            initialTitle = if(ghostPkg.isNotEmpty() || screenOffPkg.isNotEmpty()) "Sleep" else "", initialIconName = if(ghostPkg.isNotEmpty() || screenOffPkg.isNotEmpty()) "Sleep" else "Walk", suggestions = uiState.recentSuggestions,
            ledgerContext = ledgerContext,
            onDismiss = { showDialog = false; singleGapStart = -1L; singleGapEnd = -1L; ghostPkg = ""; ghostAppName = ""; screenOffPkg = ""; screenOffAppName = ""; screenOffStart = -1L; screenOffEnd = -1L },
            onSave = { outTitle, outIcon, outStart ->
                if (screenOffPkg.isNotEmpty()) {
                    dashboardViewModel.labelScreenOffSpan(screenOffPkg, outStart, screenOffEnd, outTitle, outIcon)
                    showDialog = false; screenOffPkg = ""; screenOffAppName = ""; screenOffStart = -1L; screenOffEnd = -1L; navController.popBackStack()
                } else if (singleGapStart != -1L) {
                    scope.launch {
                        dashboardViewModel.resolveContiguousGap(outStart, singleGapEnd) { tS, tE ->
                            if (tS < outStart || tE > singleGapEnd) {
                                cascadeStart = outStart; cascadeEnd = singleGapEnd; cascadeTrueStart = tS; cascadeTrueEnd = tE; cascadeTitle = outTitle; cascadeIcon = outIcon
                                showDialog = false; showCascadeDialog = true
                            } else {
                                if (ghostPkg.isNotEmpty()) {
                                    // Suppress the ORIGINAL span before converting
                                    // (2026-10-23): the conversion writes its label with
                                    // the (possibly adjusted) start, and without this row
                                    // the span resurfaced as a card on the next load.
                                    dashboardViewModel.acknowledgeIdleSpan(ghostPkg, singleGapStart, singleGapEnd)
                                    dashboardViewModel.convertGhostTime(ghostPkg, outStart, singleGapEnd, outTitle, outIcon)
                                }
                                else dashboardViewModel.saveGap(outStart, singleGapEnd, outTitle, outIcon)
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
                        Column(modifier = Modifier.padding(horizontal = 20.dp).tutorialTarget("gapsScreen", tourRegistry)) {
                            HatDynamicHeader(title = "Gaps", subtitle = "Manage") {
                                // (uiState.dismissedScreenOffSpans.isNotEmpty()): the gate
                                // checked only idle dismissals (functional sweep, 2026-10-23),
                                // so a user who dismissed only screen-off spans had no Restore
                                // door at all.
                                if (uiState.dismissedIdleSessions.isNotEmpty() || uiState.dismissedScreenOffSpans.isNotEmpty()) {
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
                            val totalGapMillis = uiState.majorGaps.sumOf { it.durationInMillis }
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
                    
                    // One unaccounted timeline (2026-10-22): the three
                    // evidence sections below used to render separately;
                    // they are deleted. Gaps, possible idle sessions,
                    // lock-screen spans, and screen-off spans all merge
                    // into the single chronological list further down.

                    // One unaccounted timeline (2026-10-22, the owner's call):
                    // a gap, a possible idle session, a lock-screen span, and
                    // a screen-off span are all the same thing, time the day
                    // has not accounted for. Section membership carried no
                    // information (any of them can be five minutes or five
                    // hours); the evidence belongs on the card, not in the
                    // header. One chronological list, each card states what
                    // HAT knows, each card keeps its own resolution flow.
                    val unaccountedCards = (
                        uiState.majorGaps.map { UnaccountedCard.Gap(it) } +
                        uiState.idleSessions.map { UnaccountedCard.Idle(it) } +
                        uiState.lockScreenSessions.map { UnaccountedCard.LockScreen(it) } +
                        uiState.screenOffItems.flatMap { item -> item.spans.map { UnaccountedCard.ScreenOff(item, it) } }
                    ).sortedBy { it.startTime }

                    if (unaccountedCards.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                Text("UNACCOUNTED TIME (${unaccountedCards.size}) · ${TimeUtils.formatDuration(unaccountedCards.sumOf { it.durationMillis })}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }

                    if (unaccountedCards.isEmpty()) {
                                            // HONESTY FIX (2026-10-01): this used to say "Your timeline is fully
                                            // tracked!" whenever the gap lists were empty. An empty list is also
                                            // what a FAILED query leaves behind, so a failure was being reported
                                            // to the user as a clean bill of health. Only claim a complete
                                            // timeline when the load actually succeeded.
                                            item {
                                                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                                                    when {
                                                        // A forced reload just emptied the lists; the load is
                                                        // still running, so claiming failure (or completeness)
                                                        // here would flash a lie for a second (regression
                                                        // audit, 2026-10-21). Say nothing until it lands.
                                                        uiState.isLoading -> { }
                                                        uiState.loadSucceeded -> EmptyStateCard(Icons.Default.CheckCircle, "Timeline Complete", "Your timeline is fully tracked! No significant gaps found today.")
                                                        else -> EmptyStateCard(Icons.Outlined.Warning, "Couldn't Read Your Data", "HAT could not load your activity this time, so this is NOT a complete picture of your day. Reopen the app or tap refresh to try again.")
                                                    }
                                                }
                                            }
                                        } else {
                        itemsIndexed(unaccountedCards, key = { _, card -> card.key }) { index, card ->
                            val isLast = index == unaccountedCards.lastIndex
                            val pinOffset = 16.dp + with(LocalDensity.current) { 8.sp.toDp() }
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(IntrinsicSize.Min)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(32.dp).fillMaxHeight()) {
                                    if (index > 0) Box(modifier = Modifier.width(2.dp).height(pinOffset).background(MaterialTheme.colorScheme.primary.copy(alpha=0.5f))) else Spacer(modifier = Modifier.height(pinOffset))
                                    Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background).border(4.dp, MaterialTheme.colorScheme.primary, CircleShape))
                                    if (!isLast) Box(modifier = Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.primary.copy(alpha=0.5f))) else Spacer(modifier = Modifier.weight(1f))
                                }
                                when (card) {
                                    is UnaccountedCard.Gap -> {
                                        val gap = card.gap
                                        val isSelected = selectedGaps.contains(gap)
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
                                                                lastGapContext = gap
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
                                                        Text(gap.contextExplainer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                                    is UnaccountedCard.Idle -> {
                                        Card(
                                            modifier = Modifier.weight(1f).padding(bottom = 12.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                                                .clickable {
                                                    dashboardViewModel.loadSuggestionsForHour(Calendar.getInstance().apply { timeInMillis = card.session.gap.startMillis }.get(Calendar.HOUR_OF_DAY))
                                                    ghostPkg = card.session.packageName
                                                    ghostAppName = card.session.appName
                                                    singleGapStart = card.session.gap.startMillis
                                                    singleGapEnd = card.session.gap.endMillis
                                                    idleDialogIsUnattended = true
                                                    showIdleActionDialog = true
                                                },
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha=0.05f))
                                        ) {
                                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                    Text(card.session.gap.timeRangeFormatted, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text("Possible idle session: ${card.session.appName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
                                                Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha=0.15f)).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp)) { Text(card.session.gap.durationFormatted, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false) }
                                            }
                                        }
                                    }
                                    is UnaccountedCard.LockScreen -> {
                                        Card(
                                            modifier = Modifier.weight(1f).padding(bottom = 12.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                                                .clickable {
                                                    dashboardViewModel.loadSuggestionsForHour(Calendar.getInstance().apply { timeInMillis = card.session.gap.startMillis }.get(Calendar.HOUR_OF_DAY))
                                                    ghostPkg = card.session.packageName
                                                    ghostAppName = card.session.appName
                                                    singleGapStart = card.session.gap.startMillis
                                                    singleGapEnd = card.session.gap.endMillis
                                                    idleDialogIsUnattended = false
                                                    showIdleActionDialog = true
                                                },
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha=0.05f))
                                        ) {
                                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                    Text(card.session.gap.timeRangeFormatted, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text("Behind the lock screen: ${card.session.appName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
                                                Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha=0.15f)).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp)) { Text(card.session.gap.durationFormatted, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false) }
                                            }
                                        }
                                    }
                                    is UnaccountedCard.ScreenOff -> {
                                        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                                        Card(
                                            modifier = Modifier.weight(1f).padding(bottom = 12.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                                                .clickable {
                                                    dashboardViewModel.loadSuggestionsForHour(Calendar.getInstance().apply { timeInMillis = card.span.first }.get(Calendar.HOUR_OF_DAY))
                                                    screenOffPkg = card.item.packageName
                                                    screenOffAppName = card.item.title
                                                    screenOffStart = card.span.first
                                                    screenOffEnd = card.span.second
                                                    showScreenOffDialog = true
                                                },
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha=0.05f))
                                        ) {
                                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                    Text("${sdf.format(Date(card.span.first))} - ${sdf.format(Date(card.span.second))}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text("Ran with the screen off: ${card.item.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
                                                Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha=0.15f)).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp)) { Text(TimeUtils.formatDuration(card.span.second - card.span.first), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false) }
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

            // The app tour (2026-11-01): this screen's steps, hosted on the
            // real surface. The registry target is the header column above.
            val gapsStep = AppTourState.current?.takeIf { it.screen == DashboardTourSteps.SCREEN_GAPS }
            TourStepOverlay(
                registry = tourRegistry,
                currentStep = gapsStep,
                globalIndex = AppTourState.stepIndex,
                globalCount = DashboardTourSteps.steps.size,
                onNext = {
                    val next = DashboardTourSteps.steps.getOrNull(AppTourState.stepIndex + 1)
                    if (next != null && next.screen != DashboardTourSteps.SCREEN_GAPS) {
                        navController.navigate(next.route)
                    }
                    if (next == null) {
                        AppTourState.end()
                        dashboardViewModel.markTourSeen()
                    }
                    AppTourState.stepIndex++
                },
                onBack = { if (AppTourState.stepIndex > 0) AppTourState.stepIndex-- },
                onSkip = {
                    AppTourState.end()
                    dashboardViewModel.markTourSeen()
                }
            )
        }
    }
}

/**
 * One card in the single unaccounted timeline (2026-10-22, the owner's
 * call): a plain gap, a possible idle session, a lock-screen span, or a
 * screen-off span. Section membership carried no information; the evidence
 * is the only difference between them, so it lives on the card and the
 * timeline stays one chronological list. Tap routes stay per-kind: a gap
 * opens the label editor, the evidence cards open their own questions.
 * Selection (long-press batch labeling) stays a gap-only affordance.
 */
private sealed interface UnaccountedCard {
    val startTime: Long
    val durationMillis: Long
    val key: String

    data class Gap(val gap: UsageGap) : UnaccountedCard {
        override val startTime: Long get() = gap.startMillis
        override val durationMillis: Long get() = gap.durationInMillis
        override val key: String get() = "gap_${gap.startMillis}_${gap.endMillis}"
    }

    data class Idle(val session: IdleSession) : UnaccountedCard {
        override val startTime: Long get() = session.gap.startMillis
        override val durationMillis: Long get() = session.gap.durationInMillis
        override val key: String get() = "idle_${session.gap.startMillis}_${session.gap.endMillis}"
    }

    data class LockScreen(val session: IdleSession) : UnaccountedCard {
        override val startTime: Long get() = session.gap.startMillis
        override val durationMillis: Long get() = session.gap.durationInMillis
        override val key: String get() = "locked_${session.gap.startMillis}_${session.gap.endMillis}"
    }

    data class ScreenOff(val item: ScreenOffItem, val span: Pair<Long, Long>) : UnaccountedCard {
        override val startTime: Long get() = span.first
        override val durationMillis: Long get() = span.second - span.first
        override val key: String get() = "screenoff_${span.first}_${span.second}"
    }
}
