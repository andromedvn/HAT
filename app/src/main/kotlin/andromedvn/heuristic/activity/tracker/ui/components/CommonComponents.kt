package andromedvn.heuristic.activity.tracker.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.data.AppUsageItem
import andromedvn.heuristic.activity.tracker.data.TimeRangeLabel
import andromedvn.heuristic.activity.tracker.utils.AppIconLoader
import andromedvn.heuristic.activity.tracker.utils.TimeUtils
import kotlinx.coroutines.delay
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

fun Modifier.verticalFadingEdges(canScrollUp: Boolean, canScrollDown: Boolean, topEdgeHeight: Dp = 16.dp, bottomEdgeHeight: Dp = 32.dp): Modifier = this.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        if (canScrollUp) drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), 0f, topEdgeHeight.toPx()), blendMode = BlendMode.DstIn)
        if (canScrollDown) drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), size.height - bottomEdgeHeight.toPx(), size.height), blendMode = BlendMode.DstIn)
    }

@Composable
fun hatSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant, uncheckedTrackColor = Color.Transparent, uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
)

val ICON_REGISTRY = mapOf(
    "Sleep" to Icons.Default.Bed, "Work" to Icons.Default.Work, "Eat" to Icons.Default.Restaurant, "Commute" to Icons.Default.DirectionsCar, "Walk" to Icons.AutoMirrored.Filled.DirectionsWalk,
    "Run" to Icons.AutoMirrored.Filled.DirectionsRun, "Bike" to Icons.Default.PedalBike, "Gym" to Icons.Default.FitnessCenter, "Game" to Icons.Default.Gamepad,
    "Read" to Icons.AutoMirrored.Filled.MenuBook, "Movie" to Icons.Default.Movie, "Code" to Icons.Default.Code, "Music" to Icons.Default.MusicNote,
    "Shop" to Icons.Default.ShoppingCart, "Study" to Icons.Default.School, "Family" to Icons.Default.FamilyRestroom, "Pet" to Icons.Default.Pets,
    "Clean" to Icons.Default.CleaningServices, "Relax" to Icons.Default.SelfImprovement, "Coffee" to Icons.Default.Coffee, "Chat" to Icons.AutoMirrored.Filled.Chat,
    "Call" to Icons.Default.Call, "Other" to Icons.Default.MoreHoriz
)

data class PresetIcon(val iconName: String, val icon: ImageVector)
val AVAILABLE_ICONS = ICON_REGISTRY.map { PresetIcon(it.key, it.value) }
data class HighlightEvent(val point: ChartPoint? = null, val stackIndex: Int = -1, val timestamp: Long = System.currentTimeMillis(), val isLongPress: Boolean = false, val isFromChart: Boolean = true, val sourceKey: String = "", val activeTags: Set<String> = emptySet())

sealed class LedgerContext {
    data class StandardGap(val startMillis: Long, val endMillis: Long) : LedgerContext()
    data class IdleSession(val startMillis: Long, val endMillis: Long, val appName: String, val pkgName: String) : LedgerContext()
    data class BatchMerge(val count: Int, val totalDurationMillis: Long) : LedgerContext()
}

@Composable
fun AsyncAppIcon(packageName: String, modifier: Modifier = Modifier, color: Color) {
    val context = LocalContext.current
    var imageBitmap by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) { if (!packageName.startsWith("com.mock.app")) imageBitmap = AppIconLoader.loadIcon(context, packageName) }
    if (imageBitmap != null) Image(bitmap = imageBitmap!!, contentDescription = null, modifier = modifier)
    else Icon(Icons.Default.Android, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun HatLoadingSpinner(color: Color) {
    val transition = rememberInfiniteTransition(label = "spin")
    val rotation by transition.animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "rot")
    val alphaPulse by transition.animateFloat(initialValue = 0.3f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "alpha")

    Canvas(modifier = Modifier.size(40.dp).graphicsLayer { rotationZ = rotation }) {
        drawCircle(color = color.copy(alpha = 0.1f), style = Stroke(width = 3.dp.toPx()))
        drawArc(color = color.copy(alpha = alphaPulse), startAngle = -90f, sweepAngle = 120f, useCenter = false, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun LineProgress(progress: Float, color: Color) {
    val safeProgress = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
    val animProgress by animateFloatAsState(targetValue = safeProgress, animationSpec = tween(600, easing = FastOutSlowInEasing), label = "prog")

    Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
        val rad = size.height / 2f
        val fullRadius = CornerRadius(rad, rad)
        drawRoundRect(color = color.copy(alpha = 0.1f), size = size, cornerRadius = fullRadius)
        if (animProgress > 0f) {
            val targetW = size.width * animProgress
            val w = targetW.coerceAtLeast(1.dp.toPx()) 
            drawRoundRect(color = color.copy(alpha = 0.2f), size = Size(w, size.height), cornerRadius = fullRadius)
            drawRoundRect(color = color, size = Size(w, size.height), cornerRadius = fullRadius, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

@Composable
fun DialogHighlightButton(text: String, color: Color, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Card(modifier = modifier.height(48.dp).border(1.dp, color, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)), shape = RoundedCornerShape(16.dp)) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun PromptHighlightButton(text: String, onClick: () -> Unit) {
    Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
    }
}

@Composable
fun PromptOutlineButton(text: String, onClick: () -> Unit) {
    Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Transparent).border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(50)).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}

@Composable
fun HatOutlinedDialog(onDismissRequest: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismissRequest) {
        Card(modifier = Modifier.fillMaxWidth().border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                content()
            }
        }
    }
}

@Composable
fun HatDatePickerModal(initialDate: Long, oldestData: Long, bypassHistoryLimit: Boolean, onDismiss: () -> Unit, onDateSelected: (Long) -> Unit) {
    var selectedDate by remember { mutableStateOf(initialDate) }
    var displayMonthCal by remember { mutableStateOf(Calendar.getInstance().apply { timeInMillis = initialDate; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }) }
    val todayCal = Calendar.getInstance()
    val oldestCal = Calendar.getInstance().apply { timeInMillis = oldestData }
    val canGoBack = bypassHistoryLimit || displayMonthCal.get(Calendar.YEAR) > oldestCal.get(Calendar.YEAR) || (displayMonthCal.get(Calendar.YEAR) == oldestCal.get(Calendar.YEAR) && displayMonthCal.get(Calendar.MONTH) > oldestCal.get(Calendar.MONTH))
    val canGoForward = displayMonthCal.get(Calendar.YEAR) < todayCal.get(Calendar.YEAR) || (displayMonthCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) && displayMonthCal.get(Calendar.MONTH) < todayCal.get(Calendar.MONTH))
    fun addMonth(amount: Int) { val newCal = displayMonthCal.clone() as Calendar; newCal.add(Calendar.MONTH, amount); displayMonthCal = newCal }
    fun isSameDay(a: Long, b: Long): Boolean { val calA = Calendar.getInstance().apply { timeInMillis = a }; val calB = Calendar.getInstance().apply { timeInMillis = b }; return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) && calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR) }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(16.dp)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background), shape = RoundedCornerShape(16.dp)) {
            Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Select Date", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(displayMonthCal.time), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                        Row {
                            IconButton(onClick = { addMonth(-1) }, enabled = canGoBack) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous", tint = if (canGoBack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.38f)) }
                            IconButton(onClick = { addMonth(1) }, enabled = canGoForward) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next", tint = if (canGoForward) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.38f)) }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { listOf("S", "M", "T", "W", "T", "F", "S").forEach { day -> Text(day, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium) } }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val startDayOffset = displayMonthCal.get(Calendar.DAY_OF_WEEK) - 1
                    val daysInMonth = displayMonthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val totalCells = startDayOffset + daysInMonth
                    val rows = Math.ceil(totalCells / 7.0).toInt()
                    var currentDay = 1
                    
                    Column(modifier = Modifier.fillMaxWidth()) {
                        for (r in 0 until rows) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                for (c in 0 until 7) {
                                    val cellIndex = r * 7 + c
                                    if (cellIndex < startDayOffset || currentDay > daysInMonth) Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                                    else {
                                        val dayCal = displayMonthCal.clone() as Calendar; dayCal.set(Calendar.DAY_OF_MONTH, currentDay)
                                        val dayMillis = dayCal.timeInMillis
                                        val isSelected = isSameDay(dayMillis, selectedDate); val isToday = isSameDay(dayMillis, System.currentTimeMillis())
                                        val dayStart = getStartOfDay(dayCal); val oldestStart = getStartOfDay(oldestCal); val todayStart = getStartOfDay(todayCal)
                                        val isBeforeOldest = !bypassHistoryLimit && dayStart < oldestStart; val isFuture = dayStart > todayStart
                                        val isEnabled = !isFuture && !isBeforeOldest
                                        val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else if (isToday) MaterialTheme.colorScheme.primary.copy(alpha=0.5f) else Color.Transparent
                                        val borderWidth = if (isSelected) 2.dp else if (isToday) 1.dp else 0.dp
                                        val bgColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha=0.15f) else Color.Transparent
                                        val textColor = if (!isEnabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.38f) else if (isSelected || isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        val textWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Medium

                                        Box(modifier = Modifier.weight(1f).aspectRatio(1f).clickable(enabled = isEnabled) { selectedDate = dayMillis }, contentAlignment = Alignment.Center) {
                                            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(bgColor).border(borderWidth, borderColor, CircleShape), contentAlignment = Alignment.Center) {
                                                Text(currentDay.toString(), color = textColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = textWeight))
                                            }
                                        }
                                        currentDay++
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)) }
                        Spacer(modifier = Modifier.width(16.dp))
                        TextButton(onClick = { onDateSelected(selectedDate); onDismiss() }) { Text("Jump", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)) }
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityEditorDialog(initialTitle: String, initialIconName: String, suggestions: List<String>, ledgerContext: LedgerContext? = null, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(initialTitle) }
    var selectedIconName by remember { mutableStateOf(initialIconName) }

    HatOutlinedDialog(onDismissRequest = onDismiss, title = "Label Activity") {
        if (ledgerContext != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                when (ledgerContext) {
                    is LedgerContext.StandardGap -> {
                        val timeStr = "${SimpleDateFormat("h:mm a", Locale.getDefault()).format(ledgerContext.startMillis)} to ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(ledgerContext.endMillis)}"
                        val durStr = TimeUtils.formatDuration(ledgerContext.endMillis - ledgerContext.startMillis)
                        Text("Time Range: $timeStr ($durStr)", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is LedgerContext.IdleSession -> {
                        val timeStr = "${SimpleDateFormat("h:mm a", Locale.getDefault()).format(ledgerContext.startMillis)} to ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(ledgerContext.endMillis)}"
                        val durStr = TimeUtils.formatDuration(ledgerContext.endMillis - ledgerContext.startMillis)
                        Text("Time Range: $timeStr ($durStr)", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("App in focus: ${ledgerContext.appName} (${ledgerContext.pkgName})", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Detection: Continuous screen-on", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is LedgerContext.BatchMerge -> {
                        Text("Action: Merging ${ledgerContext.count} distinct gaps", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Total tracked time: ${TimeUtils.formatDuration(ledgerContext.totalDurationMillis)}", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Note: App usage between these gaps will remain unaffected.", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Activity Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)))
        Spacer(modifier = Modifier.height(16.dp))
        if (suggestions.isNotEmpty()) {
            Text("Suggested for this hour", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions) { suggestion -> SuggestionChip(onClick = { title = suggestion; val match = AVAILABLE_ICONS.find { it.iconName.contains(suggestion, ignoreCase = true) || suggestion.contains(it.iconName, ignoreCase = true) }; if (match != null) selectedIconName = match.iconName }, label = { Text(suggestion) }, border = SuggestionChipDefaults.suggestionChipBorder(true, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))) }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text("Icon", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(AVAILABLE_ICONS) { preset ->
                val isSelected = selectedIconName == preset.iconName
                Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(30)).background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha=0.15f) else Color.Transparent).border(if(isSelected) 2.dp else 1.dp, if(isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(30)).clickable { selectedIconName = preset.iconName }, contentAlignment = Alignment.Center) { Icon(preset.icon, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        DialogHighlightButton("Save", MaterialTheme.colorScheme.primary) { if (title.isNotBlank()) onSave(title, selectedIconName) }
    }
}

@Composable
fun HatDynamicHeader(title: String, subtitle: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 0.dp, bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column { Text(subtitle.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), color = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface) }
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
fun TimeSelector(currentRange: TimeRangeLabel, onRangeSelected: (TimeRangeLabel) -> Unit) {
    val options = listOf(TimeRangeLabel.DAY, TimeRangeLabel.WEEK, TimeRangeLabel.MONTH)
    val selectedIndex = options.indexOf(currentRange)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(52.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(4.dp)) {
        val itemWidth = maxWidth / 3
        val indicatorOffset by animateDpAsState(targetValue = itemWidth * selectedIndex, animationSpec = tween(300, easing = FastOutSlowInEasing), label = "indicator")
        Box(modifier = Modifier.offset(x = indicatorOffset).width(itemWidth).fillMaxHeight().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, range ->
                val isSelected = index == selectedIndex
                val text = when (range) { TimeRangeLabel.DAY -> "Day"; TimeRangeLabel.WEEK -> "Week"; TimeRangeLabel.MONTH -> "Month" }
                val textColor by animateColorAsState(targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), animationSpec = tween(300, easing = FastOutSlowInEasing), label = "text")
                Box(modifier = Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onRangeSelected(range) }), contentAlignment = Alignment.Center) { Text(text = text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium), color = textColor) }
            }
        }
    }
}

@Composable
fun SummaryStatCard(modifier: Modifier = Modifier, title: String, value: String, badgeText: String?, onClick: () -> Unit) {
    Card(modifier = modifier.defaultMinSize(minHeight = 110.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                 Text(text = title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), minLines = 2)
                 if (badgeText != null) { Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha=0.15f)).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp)) { Text(badgeText, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) } }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun EmptyStateCard(icon: ImageVector, title: String, subtitle: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha=0.15f)).border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) }
        Spacer(modifier = Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RealActivityListItem(navController: NavController, app: AppUsageItem, color: Color, progress: Float, onLongClick: () -> Unit = {}, isHighlighted: Boolean = false) {
    val mod = Modifier.fillMaxWidth().padding(bottom = 12.dp).border(if(isHighlighted) 2.dp else 1.dp, color.copy(alpha = if(isHighlighted) 1f else 0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = { navController.navigate("details/app/${Uri.encode(app.packageName)}") }, onLongClick = onLongClick)
    Card(modifier = mod, colors = CardDefaults.cardColors(containerColor = if (isHighlighted) color.copy(alpha=0.1f) else Color.Transparent)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.15f)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { AsyncAppIcon(packageName = app.packageName, modifier = Modifier.size(24.dp), color = color) }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(app.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(app.timeFormatted, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LineProgress(progress, color)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MockOfflineListItem(name: String, duration: String, iconName: String, color: Color, progress: Float, isSelectedForAction: Boolean = false, onClick: () -> Unit = {}, onLongClick: () -> Unit = {}, isHighlighted: Boolean = false) {
    val icon = ICON_REGISTRY[iconName] ?: Icons.AutoMirrored.Filled.DirectionsWalk
    val showHighlight = isHighlighted || isSelectedForAction
    val mod = Modifier.fillMaxWidth().padding(bottom = 12.dp).border(if(showHighlight) 2.dp else 1.dp, color.copy(alpha = if(showHighlight) 1f else 0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)
    Card(modifier = mod, colors = CardDefaults.cardColors(containerColor = if(showHighlight) color.copy(alpha=0.15f) else Color.Transparent)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.15f)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp)) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Offline Activity", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Text(duration, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(10.dp))
                LineProgress(progress, color)
            }
        }
    }
}

@Composable
fun HiddenAppListItem(app: AppUsageItem, color: Color, onUnhide: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.15f)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { AsyncAppIcon(packageName = app.packageName, modifier = Modifier.size(24.dp), color = color) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(app.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onUnhide, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Visibility, contentDescription = "Unhide", tint = color) }
        }
    }
}

@Composable
fun PermissionRequestCard(context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
            .clickable { try { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") }) } catch(e: Exception) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text("Usage Access Needed", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Tap to grant access", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun HeuristicTipCard(quote: String) {
    Card(modifier = Modifier.fillMaxWidth().wrapContentHeight(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background), shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)) {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Heuristic Tip", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(quote, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

fun getDayOffsetString(targetMillis: Long, currentDayStartMillis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = targetMillis }
    val diffDays = ((currentDayStartMillis - getStartOfDay(cal)) / 86400000L).toInt()
    return when {
        diffDays == 1 -> " (Yesterday)"
        diffDays > 1 -> " ($diffDays days ago)"
        else -> ""
    }
}

fun getStartOfDay(cal: Calendar): Long { 
    val clone = cal.clone() as Calendar; clone.set(Calendar.HOUR_OF_DAY, 0); clone.set(Calendar.MINUTE, 0); clone.set(Calendar.SECOND, 0); clone.set(Calendar.MILLISECOND, 0)
    return clone.timeInMillis 
}
