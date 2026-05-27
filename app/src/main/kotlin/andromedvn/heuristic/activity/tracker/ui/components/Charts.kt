package andromedvn.heuristic.activity.tracker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import andromedvn.heuristic.activity.tracker.utils.TimeUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

private val BarWidth = 16.dp 
private val ChartHeight = 180.dp 

enum class ChartMode { DASHBOARD, APP_USAGE, OFFLINE, DETAIL, GAPS }

data class ChartPoint(val label: String, val values: List<Float>, val displayValue: String, val segmentValues: List<String>, val fullDateLabel: String = label, val syncTag: String = "", val isCurrent: Boolean = false, val isFuture: Boolean = false, val isNextFuture: Boolean = false)

@Composable
fun HatChart(
    modifier: Modifier = Modifier, 
    chartScrollState: LazyListState = rememberLazyListState(), 
    animationSalt: String = "",
    title: String, 
    subTitle: String? = null, 
    totalTime: String? = null, 
    dataPoints: List<ChartPoint>, 
    palette: List<Color> = emptyList(), 
    dateLabelRelative: String = "", 
    dateLabelExact: String = "",
    highlightedPoint: HighlightEvent? = null, 
    hasNext: Boolean = true, 
    hasPrev: Boolean = true,
    onPointSelected: ((HighlightEvent?) -> Unit)? = null, 
    onPrevious: (() -> Unit)? = null, 
    onNext: (() -> Unit)? = null,
    onDateClick: (() -> Unit)? = null
) {
    val fallbackPrimary = MaterialTheme.colorScheme.primary
    val activePalette = if (palette.isEmpty()) listOf(fallbackPrimary) else palette
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val backgroundColor = MaterialTheme.colorScheme.background 
    
    var selectedPoint by remember { mutableStateOf<ChartPoint?>(null) }
    var selectedStackIndex by remember { mutableIntStateOf(-1) } 

    val rawMaxDataSum = remember(dataPoints) { dataPoints.maxOfOrNull { it.values.fold(0f) { acc, fl -> acc + fl } } ?: 1f }
    val isHourly = dataPoints.size == 24
    val isWeek = dataPoints.size == 7
    val maxDataSum = max(rawMaxDataSum, 1.0f)
    val maxMins = (maxDataSum * if (isHourly) 60 else 24 * 60).toInt()
    
    val dynamicLabels = remember(maxMins) {
        val top = max(if(isHourly) 60 else 24 * 60, maxMins); val mid = top / 2; val formatLabel = { mins: Int -> TimeUtils.formatDuration(mins * 60 * 1000L) }
        listOf(formatLabel(top), formatLabel(mid), "0m")
    }

    LaunchedEffect(highlightedPoint) {
        if (highlightedPoint != null) {
            val targetTag = if (highlightedPoint.isFromChart) highlightedPoint.point?.syncTag else highlightedPoint.activeTags.firstOrNull()
            val realIdx = dataPoints.indexOfFirst { it.syncTag == targetTag }
            if (realIdx >= 0) {
                selectedPoint = dataPoints[realIdx]
                selectedStackIndex = highlightedPoint.stackIndex
                snapshotFlow { chartScrollState.layoutInfo }.filter { it.totalItemsCount > 0 }.take(1).collect { layoutInfo ->
                    if (!layoutInfo.visibleItemsInfo.any { it.index == realIdx }) chartScrollState.animateScrollToItem(maxOf(0, realIdx - 2))
                }
            }
        } else { selectedPoint = null; selectedStackIndex = -1 }
    }

    LaunchedEffect(dataPoints) { if (highlightedPoint == null) { selectedPoint = null; selectedStackIndex = -1 } }

    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, fallbackPrimary.copy(alpha = 0.5f)), shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onTap = { selectedPoint = null; selectedStackIndex = -1; onPointSelected?.invoke(null) }) }
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    val currentTitle = if (selectedPoint != null) selectedPoint!!.fullDateLabel else title
                    val currentSub = when {
                        selectedPoint != null && selectedStackIndex == -1 && (selectedPoint?.values?.count { it > 0f } ?: 0) > 1 -> "Combined Segment"
                        selectedPoint != null -> if (isHourly) "Hourly Segment" else "Daily Segment"
                        else -> subTitle
                    }
                    Text(currentTitle, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                    if (currentSub != null) Text(currentSub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (totalTime != null) {
                    val labelText = when {
                        selectedPoint != null && selectedStackIndex == -1 -> "Segment Total"
                        selectedPoint != null && selectedStackIndex == 0 -> "Apps Usage"
                        selectedPoint != null && selectedStackIndex == 1 -> "Offline Time"
                        else -> "Total Tracked Time"
                    }
                    val displayTime = when {
                        selectedPoint != null && selectedStackIndex >= 0 -> selectedPoint!!.segmentValues.getOrElse(selectedStackIndex) { "0m" }
                        selectedPoint != null -> selectedPoint!!.displayValue
                        else -> totalTime
                    }
                    
                    val isZero = displayTime == "0m" || displayTime == "0h 0m" || displayTime == ""
                    val timeAlpha by animateFloatAsState(targetValue = if (isZero) 0f else 1f, animationSpec = tween(400), label = "timeAlpha")
                    
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.alpha(timeAlpha)) {
                        Text(labelText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(displayTime, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = fallbackPrimary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(modifier = Modifier.fillMaxWidth().height(ChartHeight + 24.dp), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.height(ChartHeight).padding(end = 12.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                    Text(dynamicLabels[0], style = MaterialTheme.typography.labelSmall, color = labelColor, fontSize = 10.sp)
                    Text(dynamicLabels[1], style = MaterialTheme.typography.labelSmall, color = labelColor, fontSize = 10.sp)
                    Text(dynamicLabels[2], style = MaterialTheme.typography.labelSmall, color = labelColor, fontSize = 10.sp)
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    GridLinesOverlay(labelColor)
                    
                    // V.9: Using index as key forces Compose to REUSE the node on scope swaps (Day -> Week).
                    // This creates the seamless "Liquid Morph" effect without dropping to 0f.
                    LazyRow(state = chartScrollState, modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(0.dp), contentPadding = PaddingValues(end = 40.dp)) {
                        itemsIndexed(dataPoints, key = { index, _ -> index }) { index, point ->
                            val isSelected = if (highlightedPoint == null) false else if (highlightedPoint.isFromChart) highlightedPoint.point?.syncTag == point.syncTag else highlightedPoint.activeTags.contains(point.syncTag)
                            val isDimmed = highlightedPoint != null && !isSelected
                            val isEmpty = point.values.all { it <= 0f }
                            
                            val showLabel = when {
                                isSelected || point.isCurrent -> true
                                isHourly -> index % 6 == 0 || index == 23
                                isWeek -> true
                                else -> index == 0 || index % 5 == 4 || index == dataPoints.lastIndex
                            }
                            val textAlpha = if (isWeek && isEmpty && !isSelected && !point.isCurrent) 0.2f else if (showLabel) 1f else 0f
                            
                            BarItem(
                                point = point, palette = activePalette, isSelected = isSelected, isDimmed = isDimmed, maxDataSum = maxDataSum, selectedStackIndex = if (isSelected) selectedStackIndex else -1,
                                textAlpha = textAlpha, 
                                animationSalt = animationSalt,
                                onBarClick = { clickedStackIndex, isLongPress ->
                                    if (isLongPress) {
                                        selectedPoint = point; selectedStackIndex = clickedStackIndex
                                        onPointSelected?.invoke(HighlightEvent(point, clickedStackIndex, System.currentTimeMillis(), isLongPress = true, isFromChart = true))
                                    } else {
                                        val activeCount = point.values.count { it > 0f }
                                        if (selectedPoint?.syncTag != point.syncTag) {
                                            val newIdx = if (activeCount > 1) -1 else point.values.indexOfFirst { it > 0f }.takeIf { it >= 0 } ?: -1
                                            selectedPoint = point; selectedStackIndex = newIdx
                                            onPointSelected?.invoke(HighlightEvent(point, newIdx, System.currentTimeMillis(), isLongPress = false, isFromChart = true))
                                        } else {
                                            if (activeCount > 1) {
                                                if (selectedStackIndex == -1) { selectedStackIndex = clickedStackIndex } 
                                                else if (selectedStackIndex == clickedStackIndex) { selectedStackIndex = -1 } 
                                                else { selectedStackIndex = clickedStackIndex }
                                            }
                                            onPointSelected?.invoke(HighlightEvent(point, selectedStackIndex, System.currentTimeMillis(), isLongPress = false, isFromChart = true))
                                        }
                                    }
                                }
                            )
                        }
                    }
                    Box(modifier = Modifier.align(Alignment.CenterEnd).width(32.dp).fillMaxHeight().background(Brush.horizontalGradient(colors = listOf(Color.Transparent, backgroundColor.copy(alpha=0.8f), backgroundColor), startX = 0f, endX = Float.POSITIVE_INFINITY)))
                }
            }
            
            if (onPrevious != null && onNext != null) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Spacer(modifier = Modifier.height(4.dp))
                        DateNavigator(
                            relativeLabel = if (dateLabelRelative.isEmpty()) "Today" else dateLabelRelative, 
                            exactDateLabel = dateLabelExact, 
                            onPrevious = onPrevious, 
                            onNext = onNext, 
                            hasNext = hasNext, 
                            hasPrev = hasPrev,
                            onDateClick = onDateClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GridLinesOverlay(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val linePaint = color.copy(alpha = 0.1f)
        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        drawLine(linePaint, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
        drawLine(linePaint, Offset(0f, (size.height - 24.dp.toPx()) / 2), Offset(size.width, (size.height - 24.dp.toPx()) / 2), pathEffect = pathEffect, strokeWidth = 1.dp.toPx())
        drawLine(linePaint, Offset(0f, size.height - 24.dp.toPx()), Offset(size.width, size.height - 24.dp.toPx()), strokeWidth = 1.dp.toPx())
    }
}

@Composable
fun BarItem(point: ChartPoint, palette: List<Color>, maxDataSum: Float, isSelected: Boolean, isDimmed: Boolean, selectedStackIndex: Int, textAlpha: Float, animationSalt: String, onBarClick: (Int, Boolean) -> Unit) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = primaryColor.copy(alpha = 0.1f)
    
    val targetGap = if (isSelected) 4.dp else 0.dp
    val gapSize by animateDpAsState(targetValue = targetGap, animationSpec = tween(400), label = "gap")
    val alpha by animateFloatAsState(targetValue = if (isDimmed) 0.3f else 1f, animationSpec = tween(300), label = "alpha")
    val splitProgress by animateFloatAsState(targetValue = if (isSelected) 1f else 0f, animationSpec = tween(400), label = "splitProg")
    val animTextAlpha by animateFloatAsState(targetValue = textAlpha, animationSpec = tween(300), label = "txtA")
    
    val scope = rememberCoroutineScope()
    var flashAlpha by remember { mutableFloatStateOf(0f) }
    val animatedFlash by animateFloatAsState(targetValue = flashAlpha, animationSpec = tween(300), label = "flash")

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(initialValue = 0.4f, targetValue = 1.0f, animationSpec = infiniteRepeatable(animation = tween(1500, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "pulseAlpha")
    val labelColor = if (point.isCurrent) primaryColor.copy(alpha = pulseAlpha) else if (isSelected) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant
    val labelWeight = if (point.isCurrent) FontWeight.ExtraBold else if (isSelected) FontWeight.Bold else FontWeight.Normal

    // V.9 Pure Physics: Animatable + rememberSaveable guarantees memory permanence across nav-graphs.
    val savedValues = rememberSaveable(point.values.size, saver = listSaver(
        save = { it.map { anim -> anim.value } },
        restore = { it.map { value -> Animatable(value) }.toMutableList() }
    )) {
        point.values.map { Animatable(0f) }.toMutableList()
    }

    var executedSalt by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(point.values, animationSalt) {
        if (executedSalt != animationSalt) {
            // Explicit refresh detected. Snap to 0 to trigger growth discovery.
            savedValues.forEach { it.snapTo(0f) }
            executedSalt = animationSalt
        }
        
        // Fluidly animate to target values (creates morph effect if values changed)
        point.values.forEachIndexed { index, target ->
            val safeTarget = if (target.isNaN()) 0f else target.coerceIn(0f, 1f)
            launch {
                savedValues[index].animateTo(safeTarget, tween(600, easing = FastOutSlowInEasing))
            }
        }
    }

    val currentAnimValues = savedValues.map { it.value }
    val activeIndices = point.values.indices.filter { point.values[it] > 0f || currentAnimValues[it] > 0.001f }
    
    val currentActiveIndices by rememberUpdatedState(activeIndices)
    val currentOnBarClick by rememberUpdatedState(onBarClick)
    val density = LocalDensity.current
    
    val calculateClickedIndex: (Float) -> Int = { y ->
        var clickedIndex = -1
        with(density) {
            val barH = ChartHeight.toPx()
            if (currentActiveIndices.size == 1) {
                clickedIndex = currentActiveIndices.first()
            } else {
                var currentGapPx = gapSize.toPx()
                var currentMinPx = 2.dp.toPx()
                val totalItems = currentActiveIndices.size
                if (totalItems > 1) {
                    val requiredMinSpace = (totalItems.toFloat() * currentMinPx) + ((totalItems - 1).toFloat() * currentGapPx)
                    if (requiredMinSpace > barH) { currentGapPx = 0f; currentMinPx = barH / totalItems.toFloat() }
                }
                val relativeScale = if (maxDataSum > 0f) 1f / maxDataSum else 1f
                val rawHeights = currentActiveIndices.associateWith { i -> max(currentAnimValues[i] * relativeScale * barH, currentMinPx) }
                val totalRawHeights = rawHeights.values.fold(0f) { acc, v -> acc + v }
                val totalGaps = (totalItems - 1).coerceAtLeast(0).toFloat() * currentGapPx
                val scale = if (totalRawHeights + totalGaps > barH) (barH - totalGaps).coerceAtLeast(0f) / totalRawHeights else 1f

                var currentY = barH; var closestIdx = -1; var minDistance = Float.MAX_VALUE; var overallTop = barH
                for (i in currentActiveIndices) {
                    val h = rawHeights[i]!! * scale
                    val topY = currentY - h
                    val centerY = currentY - (h / 2f)
                    val distance = kotlin.math.abs(y - centerY)
                    if (distance < minDistance) { minDistance = distance; closestIdx = i }
                    overallTop = topY
                    currentY = topY - currentGapPx
                }
                if (y >= overallTop - 24.dp.toPx() && y <= barH + 24.dp.toPx()) { clickedIndex = closestIdx }
            }
        }
        clickedIndex
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(BarWidth + 24.dp).background(Color.Transparent).alpha(alpha).pointerInput(point.syncTag, currentActiveIndices) {
            detectTapGestures(
                onTap = { offset ->
                    if (point.isFuture && !point.isNextFuture) return@detectTapGestures
                    if (currentActiveIndices.isEmpty()) { scope.launch { flashAlpha = 0.4f; delay(150); flashAlpha = 0f }; currentOnBarClick(-1, false) } 
                    else { val idx = calculateClickedIndex(offset.y); currentOnBarClick(if (idx != -1) idx else -1, false) }
                },
                onLongPress = { offset ->
                    if (point.isFuture && !point.isNextFuture) return@detectTapGestures
                    if (currentActiveIndices.isNotEmpty()) { val idx = calculateClickedIndex(offset.y); if (idx != -1) currentOnBarClick(idx, true) }
                }
            )
        }
    ) {
        Canvas(modifier = Modifier.width(BarWidth).height(ChartHeight)) {
            val barW = size.width; val barH = size.height; val trackRad = min(barW / 2f, barH / 2f)
            val strokeWidthPx = 1.5.dp.toPx(); val inset = strokeWidthPx / 2f; val stroke = Stroke(width = strokeWidthPx)
            val safeOuterRad = CornerRadius((trackRad - inset).coerceAtLeast(0f), (trackRad - inset).coerceAtLeast(0f))

            drawRoundRect(color = trackColor, topLeft = Offset(inset, inset), size = Size((barW - inset * 2).coerceAtLeast(1f), (barH - inset * 2).coerceAtLeast(1f)), cornerRadius = safeOuterRad)
            if (animatedFlash > 0f) drawRoundRect(color = primaryColor.copy(alpha = animatedFlash), topLeft = Offset(inset, inset), size = Size((barW - inset * 2).coerceAtLeast(1f), (barH - inset * 2).coerceAtLeast(1f)), cornerRadius = safeOuterRad)
            
            if (activeIndices.isNotEmpty()) {
                var currentGapPx = gapSize.toPx()
                var currentMinPx = 2.dp.toPx()
                val totalItems = activeIndices.size
                if (totalItems > 1) {
                    val requiredMinSpace = (totalItems.toFloat() * currentMinPx) + ((totalItems - 1).toFloat() * currentGapPx)
                    if (requiredMinSpace > barH) { currentGapPx = 0f; currentMinPx = barH / totalItems.toFloat() }
                }
                val relativeScale = if (maxDataSum > 0f) 1f / maxDataSum else 1f
                val rawHeights = activeIndices.associateWith { i -> max(currentAnimValues[i] * relativeScale * barH, currentMinPx) }
                val totalRawHeights = rawHeights.values.fold(0f) { acc, v -> acc + v }
                val totalGaps = (totalItems - 1).coerceAtLeast(0).toFloat() * currentGapPx
                val scale = if (totalRawHeights + totalGaps > barH) (barH - totalGaps).coerceAtLeast(0f) / totalRawHeights else 1f

                var currentY = barH; val segmentPaths = mutableListOf<Path>()

                for (i in activeIndices) {
                    val h = rawHeights[i]!! * scale
                    val calculatedTopY = currentY - h
                    val topY = if (splitProgress == 0f) round(calculatedTopY) else calculatedTopY
                    val isBottom = i == activeIndices.first()
                    val isTop = i == activeIndices.last()
                    val actualRad = min(barW / 2f, h / 2f)
                    val innerActualRad = splitProgress * actualRad
                    val bottomRad = if (isBottom) CornerRadius((actualRad - inset).coerceAtLeast(0f), (actualRad - inset).coerceAtLeast(0f)) else CornerRadius((innerActualRad - inset).coerceAtLeast(0f), (innerActualRad - inset).coerceAtLeast(0f))
                    val topRad = if (isTop) CornerRadius((actualRad - inset).coerceAtLeast(0f), (actualRad - inset).coerceAtLeast(0f)) else CornerRadius((innerActualRad - inset).coerceAtLeast(0f), (innerActualRad - inset).coerceAtLeast(0f))
                    val currentTopInset = if (isTop) inset else (inset * splitProgress)
                    val currentBottomInset = if (isBottom) inset else (inset * splitProgress)
                    
                    val segmentPath = Path().apply { addRoundRect(RoundRect(left = inset, top = (topY + currentTopInset).coerceAtLeast(0f), right = (barW - inset).coerceAtLeast(1f), bottom = (currentY - currentBottomInset).coerceAtLeast(1f), topLeftCornerRadius = topRad, topRightCornerRadius = topRad, bottomRightCornerRadius = bottomRad, bottomLeftCornerRadius = bottomRad)) }
                    segmentPaths.add(segmentPath)
                    currentY = topY - currentGapPx
                }

                val isUniformColor = activeIndices.map { palette.getOrElse(it) { primaryColor } }.distinct().size == 1
                
                if (splitProgress < 1f && activeIndices.size > 1) {
                    val totalH = totalRawHeights * scale; val topY = barH - totalH; val totalActualRad = min(barW / 2f, totalH / 2f)
                    val fullRadius = CornerRadius((totalActualRad - inset).coerceAtLeast(0f), (totalActualRad - inset).coerceAtLeast(0f))
                    val unifiedPath = Path().apply { addRoundRect(RoundRect(left = inset, top = minOf(topY + inset, barH - inset - 0.5f).coerceAtLeast(0f), right = (barW - inset).coerceAtLeast(1f), bottom = (barH - inset).coerceAtLeast(1f), topLeftCornerRadius = fullRadius, topRightCornerRadius = fullRadius, bottomRightCornerRadius = fullRadius, bottomLeftCornerRadius = fullRadius)) }
                    if (isUniformColor) { val unifiedFillAlpha = 0.2f * (1f - splitProgress); if (unifiedFillAlpha > 0f) drawPath(unifiedPath, color = primaryColor.copy(alpha = unifiedFillAlpha)) }
                    drawPath(unifiedPath, color = primaryColor.copy(alpha = (1f - splitProgress)), style = stroke)
                }

                for ((idx, i) in activeIndices.withIndex()) {
                    val color = palette.getOrElse(i) { primaryColor }
                    val targetAlpha = if (isSelected && selectedStackIndex != -1 && selectedStackIndex != i) 0.2f else 1f
                    val segmentFillAlpha = if (activeIndices.size == 1) (0.2f * targetAlpha) else if (isUniformColor) (0.2f * splitProgress * targetAlpha) else (0.2f * targetAlpha)
                    val strokeAlpha = if (activeIndices.size == 1) targetAlpha else (splitProgress * targetAlpha)
                    if (segmentFillAlpha > 0f) drawPath(segmentPaths[idx], color = color.copy(alpha = segmentFillAlpha))
                    if (strokeAlpha > 0f) drawPath(segmentPaths[idx], color = color.copy(alpha = strokeAlpha), style = stroke)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = point.label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = labelWeight, textAlign = TextAlign.Center), color = labelColor, maxLines = 1, softWrap = false, modifier = Modifier.fillMaxWidth().alpha(animTextAlpha))
    }
}

@Composable
fun DateNavigator(relativeLabel: String, exactDateLabel: String, onPrevious: () -> Unit, onNext: () -> Unit, hasNext: Boolean = true, hasPrev: Boolean = true, onDateClick: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp), enabled = hasPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Prev", tint = if (hasPrev) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha=0.3f), modifier = Modifier.size(24.dp)) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = onDateClick != null) { onDateClick?.invoke() }.padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(relativeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Box(modifier = Modifier.height(16.dp), contentAlignment = Alignment.Center) { if (exactDateLabel.isNotEmpty()) Text(exactDateLabel, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
        IconButton(onClick = onNext, modifier = Modifier.size(48.dp), enabled = hasNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next", tint = if (hasNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), modifier = Modifier.size(24.dp)) }
    }
}
