package andromedvn.heuristic.activity.tracker.ui.screens

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import andromedvn.heuristic.activity.tracker.ui.components.ChartMode
import andromedvn.heuristic.activity.tracker.utils.HeuristicQuotes
import andromedvn.heuristic.activity.tracker.viewmodel.DashboardViewModel
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val tourRegistry = andromedvn.heuristic.activity.tracker.ui.components.AppTourRegistry
    // Tour targets (r12d): the gap-card step spotlights the first few cards
    // as one block ("tap any gap card"), and the pulse step pulses ONE card.
    val gapCardRects = remember { mutableMapOf<Int, androidx.compose.ui.geometry.Rect>() }
    fun registerGapCardBlock() {
        if (gapCardRects.isEmpty()) return
        val left = gapCardRects.values.minOf { it.left }
        val top = gapCardRects.values.minOf { it.top }
        val right = gapCardRects.values.maxOf { it.right }
        val bottom = gapCardRects.values.maxOf { it.bottom }
        tourRegistry.register("gapCardsBlock", androidx.compose.ui.geometry.Rect(left, top, right, bottom))
    }
    val uiState by dashboardViewModel.uiState.collectAsState()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showCascadeDialog by rememberSaveable { mutableStateOf(false) }
    // Which evidence the idle dialog is presenting (2026-10-21): true for an
    // untouched-app Idle Session (the maintainer's original feature), false
    // for Lock Screen Activity (app active behind a locked phone). Both ask
    // the same two questions; the card names its evidence.
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
    var dialogPresenceLine by remember { mutableStateOf<String?>(null) }

    // Presence evidence lines per gap card (2026-11-02): factual, sourced
    // from the persisted raw event stream. Gaps older than the field, or
    // with undecided evidence, show nothing.
    var presenceLines by remember { mutableStateOf<Map<Long, andromedvn.heuristic.activity.tracker.viewmodel.DashboardViewModel.PresenceCopy>>(emptyMap()) }
    androidx.compose.runtime.LaunchedEffect(uiState.majorGaps, uiState.screenOffItems) {
        val ranges = buildList {
            uiState.majorGaps.forEach { add(it.startMillis to it.endMillis) }
        }
        if (ranges.isNotEmpty()) presenceLines = dashboardViewModel.presenceVerdicts(ranges)

    }

    LaunchedEffect(uiState.timeRange, uiState.dateLabelRelative) {
        chartHighlight = null
        returnScrollIndex = -1
        returnScrollOffset = 0
        chartScrollState.scrollToItem(0)
    }

    val handleBack: () -> Unit = {
        if (showDatePicker) { showDatePicker = false }
        else if (showRestorationDialog) { showRestorationDialog = false }
        else if (showScreenOffDialog) { showScreenOffDialog = false; screenOffPkg = ""; screenOffAppName = ""; screenOffStart = -1L; screenOffEnd = -1L }
        else if (showCascadeDialog) { showCascadeDialog = false; cascadeStart = -1L; cascadeEnd = -1L; cascadeTrueStart = -1L; cascadeTrueEnd = -1L; cascadeTitle = ""; cascadeIcon = "" }
        else if (showDialog) { showDialog = false; singleGapStart = -1L; singleGapEnd = -1L }
        else if (returnScrollIndex != -1) { scope.launch { listState.scrollToItem(returnScrollIndex, returnScrollOffset); returnScrollIndex = -1; chartHighlight = null } } 
        else if (selectedGaps.isNotEmpty()) { selectedGaps.clear() } 
        else { navController.popBackStack() }
    }

    val isResumed = lifecycleState == Lifecycle.State.RESUMED
    BackHandler(enabled = isResumed && (returnScrollIndex != -1 || selectedGaps.isNotEmpty() || showDialog || showCascadeDialog || showRestorationDialog || showScreenOffDialog || showDatePicker)) { handleBack() }

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
            if (uiState.dismissedScreenOffSpans.isEmpty()) {
                EmptyStateCard(icon = Icons.Default.History, title = "No Dismissed Sessions", subtitle = "No dismissed sessions for this period.")
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PromptHighlightButton("Close") { showRestorationDialog = false }
                }
            } else {
                // Screen-off dismissals get the same door (2026-10-21):
                // until now "Count as usage" on a screen-off span was
                // permanent. Restoring one pulls the span back out of
                // usage and it returns to the Screen-Off Activity list.

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("These screen-off records were previously counted back as usage. Restoring one returns it to Screen-Off Activity.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                if (uiState.dismissedScreenOffSpans.size <= 1) showRestorationDialog = false
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
            Text("Runs $sStr to $eStr, about ${TimeUtils.formatDuration(cascadeTrueEnd - cascadeTrueStart)}. It crosses midnight, so HAT already split it.\n\nApply '$cascadeTitle' to the whole thing?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { 
                    dashboardViewModel.saveGap(cascadeStart, cascadeEnd, cascadeTitle, cascadeIcon)
                    showCascadeDialog = false; navController.popBackStack() 
                }) { Text("Just This Day", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(modifier = Modifier.width(16.dp))
                TextButton(onClick = { 
                    dashboardViewModel.saveGap(cascadeTrueStart, cascadeTrueEnd, cascadeTitle, cascadeIcon)
                    showCascadeDialog = false; navController.popBackStack() 
                }) { Text("Entire Block", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
    

    if (showScreenOffDialog) {
        HatOutlinedDialog(onDismissRequest = { showScreenOffDialog = false; screenOffPkg = ""; screenOffAppName = ""; screenOffStart = -1L; screenOffEnd = -1L }, title = "Screen-Off Activity") {
            val sStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(screenOffStart)
            val eStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(screenOffEnd)
            val durationStr = TimeUtils.formatDuration(screenOffEnd - screenOffStart)
            Text("$screenOffAppName ran from $sStr to $eStr ($durationStr) with the screen off. Name what it actually was, or count the time as usage instead.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            else -> LedgerContext.StandardGap(singleGapStart, singleGapEnd, lastGapContext?.precededBy, lastGapContext?.followedBy)
        }

        ActivityEditorDialog(
            initialTitle = "", initialIconName = "Walk", suggestions = uiState.recentSuggestions,
            ledgerContext = ledgerContext,
            presenceLine = dialogPresenceLine,
            onDismiss = {
                // Tour fallback (r12e): the label dialog dismissed while the
                // tour sits on its step returns to the pulse instead of
                // leaving the tour invisible.
                if (andromedvn.heuristic.activity.tracker.domain.AppTourState.current?.id == "labelDialog") {
                    andromedvn.heuristic.activity.tracker.domain.AppTourState.stepIndex = andromedvn.heuristic.activity.tracker.domain.DashboardTourSteps.steps.indexOfFirst { it.id == "gapCardPulse" }
                }
                showDialog = false; singleGapStart = -1L; singleGapEnd = -1L; screenOffPkg = ""; screenOffAppName = ""; screenOffStart = -1L; screenOffEnd = -1L; dialogPresenceLine = null
            },
            onSave = { outTitle, outIcon, outStart ->
                if (screenOffPkg.isNotEmpty()) {
                    dashboardViewModel.labelScreenOffSpan(screenOffPkg, outStart, screenOffEnd, outTitle, outIcon)
                    showDialog = false; screenOffPkg = ""; screenOffAppName = ""; screenOffStart = -1L; screenOffEnd = -1L; dialogPresenceLine = null; navController.popBackStack()
                } else if (singleGapStart != -1L) {
                    scope.launch {
                        dashboardViewModel.resolveContiguousGap(outStart, singleGapEnd) { tS, tE ->
                            // The cascade dialog exists for blocks HAT split at
                            // midnight. A wider same-day resolution is just the
                            // gap HAT recorded; save it plain (owner fix, 2026-11-02).
                            if (tS < todayStartMillis) {
                                cascadeStart = outStart; cascadeEnd = singleGapEnd; cascadeTrueStart = tS; cascadeTrueEnd = tE; cascadeTitle = outTitle; cascadeIcon = outIcon
                                showDialog = false; showCascadeDialog = true
                            } else {
                                dashboardViewModel.saveGap(outStart, singleGapEnd, outTitle, outIcon)
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

    // The tour's label-dialog step (r12d): the tour runs in its OWN dialog
    // window, stacked on top of the label dialog. Scrim + cutout + card, the
    // same anatomy as every other step; the label dialog sits in the
    // background, dimmed.
    if (showDialog && andromedvn.heuristic.activity.tracker.domain.AppTourState.current?.id == "labelDialog") {
        val tourDismiss = {
            showDialog = false; singleGapStart = -1L; singleGapEnd = -1L; screenOffPkg = ""; screenOffAppName = ""; screenOffStart = -1L; screenOffEnd = -1L; dialogPresenceLine = null
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            andromedvn.heuristic.activity.tracker.ui.components.TourStepOverlay(
                registry = andromedvn.heuristic.activity.tracker.ui.components.AppTourRegistry,
                currentStep = andromedvn.heuristic.activity.tracker.domain.AppTourState.current,
                globalIndex = andromedvn.heuristic.activity.tracker.domain.AppTourState.stepIndex,
                globalCount = andromedvn.heuristic.activity.tracker.domain.DashboardTourSteps.steps.size,
                onNext = {
                    tourDismiss()
                    navController.popBackStack()
                    andromedvn.heuristic.activity.tracker.domain.AppTourState.stepIndex++
                },
                onBack = {
                    tourDismiss()
                    andromedvn.heuristic.activity.tracker.domain.AppTourState.stepIndex--
                },
                onSkip = {
                    andromedvn.heuristic.activity.tracker.domain.AppTourState.end()
                    tourDismiss()
                }
            )
        }
    }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0,0,0,0)) { innerPadding ->
            Column(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()).fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).pointerInput(Unit) { detectTapGestures(onTap = { chartHighlight = null; returnScrollIndex = -1; returnScrollOffset = 0 }) }.verticalFadingEdges(listState.canScrollBackward, listState.canScrollForward)) {

    // One unaccounted timeline: gaps and screen-off spans in one
    // chronological list (2026-10-22, the owner's call). Hoisted out of the
    // list builder so the tour logic can read it.
    val unaccountedCards = (
        uiState.majorGaps.map { UnaccountedCard.Gap(it) } +
        uiState.screenOffItems.flatMap { item -> item.spans.map { UnaccountedCard.ScreenOff(item, it) } }
    ).sortedBy { it.startTime }

    // The tour's gap-card steps (r12): land on a real card. When today has no
    // cards, jump to yesterday so the pulse has something to pulse on; when a
    // card exists, scroll it into view (the tour card sits at the bottom).
    val tourStepId = if (andromedvn.heuristic.activity.tracker.domain.AppTourState.active) andromedvn.heuristic.activity.tracker.domain.AppTourState.current?.id else null
    // The pulse tap advances the tour through the overlay's gesture handler,
    // which consumes the up-event before the card's own onClick can fire, so
    // the label dialog never opens on that path. When the tour lands on the
    // dialog step with no dialog open, open it for the pulsed card here.
    LaunchedEffect(tourStepId) {
        if (tourStepId != "labelDialog" || showDialog || singleGapStart != -1L || selectedGaps.isNotEmpty()) return@LaunchedEffect
        val firstGap = unaccountedCards.firstOrNull { it is UnaccountedCard.Gap } as? UnaccountedCard.Gap ?: return@LaunchedEffect
        dashboardViewModel.loadSuggestionsForHour(Calendar.getInstance().apply { timeInMillis = firstGap.gap.startMillis }.get(Calendar.HOUR_OF_DAY))
        singleGapStart = firstGap.gap.startMillis
        singleGapEnd = firstGap.gap.endMillis
        lastGapContext = firstGap.gap
        dialogPresenceLine = presenceLines[firstGap.gap.startMillis]?.let { copy ->
            if (copy.evidence != null) copy.line + "\n" + copy.evidence else copy.line
        }
        showDialog = true
    }
    LaunchedEffect(tourStepId, unaccountedCards.size) {
        if (tourStepId != "gapCard" && tourStepId != "gapCardPulse") return@LaunchedEffect
        if (unaccountedCards.isEmpty()) {
            dashboardViewModel.jumpToDate(uiState.startTime - 86_400_000L)
        } else {
            // Always land the first card at the top of the viewport: the tour
            // card sits at the bottom, and an off-center card ended up hidden
            // behind it.
            val firstCardIndex = listState.layoutInfo.totalItemsCount - unaccountedCards.size
            if (firstCardIndex >= 0) listState.scrollToItem(firstCardIndex)
        }
    }
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp).tutorialTarget("gapsScreen", tourRegistry)) {
                            HatDynamicHeader(title = "Gaps", subtitle = "Manage") {
                                // (uiState.dismissedScreenOffSpans.isNotEmpty()): the gate
                                // checked only idle dismissals (functional sweep, 2026-10-23),
                                // so a user who dismissed only screen-off spans had no Restore
                                // door at all.
                                if (uiState.dismissedScreenOffSpans.isNotEmpty()) {
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
                                    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                                    val isBatteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                                    val quote = remember(currentHighlight) {
                                        HeuristicQuotes.getQuote(
                                            point = currentHighlight?.point,
                                            totalScreenTimeMillis = uiState.totalScreenTimeMillis,
                                            isDayView = uiState.timeRange == TimeRangeLabel.DAY,
                                            isDataReliable = uiState.isDataReliable,
                                            hasSeenHistoryLimitWarning = uiState.hasSeenHistoryLimitWarning,
                                            osRetentionDays = uiState.osRetentionDays,
                                            chartMode = ChartMode.GAPS,
                                            isBatteryExempt = isBatteryExempt)
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


                    if (unaccountedCards.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                Text("Unlabeled Gaps: ${unaccountedCards.size} (${TimeUtils.formatDuration(unaccountedCards.sumOf { it.durationMillis })} total)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
                                                        uiState.loadSucceeded -> EmptyStateCard(Icons.Default.CheckCircle, "Nothing needs labeling at the moment", "Surprise surprise, seems like everything is already labeled.")
                                                        else -> EmptyStateCard(Icons.Outlined.Warning, "HAT could not load your data", "HAT could not load your activity. This list is not complete. Reopen the app or tap refresh to try again.")
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
                                                    .then(if (index < 4) Modifier.onGloballyPositioned { coords ->
                                                        gapCardRects[index] = coords.boundsInRoot()
                                                        registerGapCardBlock()
                                                        if (index == 0) tourRegistry.register("firstGapCard", coords.boundsInRoot())
                                                    } else Modifier)
                                                    .combinedClickable(
                                                        onClick = {
                                                            if (selectedGaps.isNotEmpty()) {
                                                                if (isSelected) selectedGaps.remove(gap) else selectedGaps.add(gap)
                                                            } else {
                                                                dashboardViewModel.loadSuggestionsForHour(Calendar.getInstance().apply { timeInMillis = gap.startMillis }.get(Calendar.HOUR_OF_DAY))
                                                                singleGapStart = gap.startMillis
                                                                singleGapEnd = gap.endMillis
                                                                lastGapContext = gap
                                                                // Tour (r12): opening a label dialog from
                                                                // any gap-card step continues the tour
                                                                // inside the dialog instead of dropping it.
                                                                val tourNow = andromedvn.heuristic.activity.tracker.domain.AppTourState.current
                                                                if (andromedvn.heuristic.activity.tracker.domain.AppTourState.active && tourNow?.id == "gapCard") {
                                                                    andromedvn.heuristic.activity.tracker.domain.AppTourState.stepIndex = andromedvn.heuristic.activity.tracker.domain.DashboardTourSteps.steps.indexOfFirst { it.id == "labelDialog" }
                                                                }
                                                                dialogPresenceLine = presenceLines[gap.startMillis]?.let { copy ->
                                                                    if (copy.evidence != null) copy.line + "\n" + copy.evidence else copy.line
                                                                }
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
                                                    if (gap.hasHiddenAppFootprint) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text("Contains hidden app", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        }
                                                    }
                                                }
                                                Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha=0.15f)).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp)) { Text(gap.durationFormatted, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false) }
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

    data class ScreenOff(val item: ScreenOffItem, val span: Pair<Long, Long>) : UnaccountedCard {
        override val startTime: Long get() = span.first
        override val durationMillis: Long get() = span.second - span.first
        override val key: String get() = "screenoff_${span.first}_${span.second}"
    }
}
