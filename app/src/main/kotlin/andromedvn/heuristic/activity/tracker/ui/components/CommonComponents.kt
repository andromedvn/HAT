package andromedvn.heuristic.activity.tracker.ui.components

import andromedvn.heuristic.activity.tracker.domain.NavRouteCodec
import andromedvn.heuristic.activity.tracker.utils.SPONSOR_URL
import andromedvn.heuristic.activity.tracker.utils.openExternalWebLink
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import andromedvn.heuristic.activity.tracker.ui.theme.argbToStoredLong
import andromedvn.heuristic.activity.tracker.ui.theme.formatHexColor
import andromedvn.heuristic.activity.tracker.ui.theme.parseHexColor
import andromedvn.heuristic.activity.tracker.utils.AppIconLoader
import andromedvn.heuristic.activity.tracker.utils.TimeUtils
import kotlinx.coroutines.delay
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
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
    data class StandardGap(val startMillis: Long, val endMillis: Long, val precededBy: String? = null, val followedBy: String? = null) : LedgerContext()
    data class BatchMerge(val count: Int, val totalDurationMillis: Long) : LedgerContext()
    /** A Screen-Off Activity span: the app was resumed while the screen was provably dark. */
    data class ScreenOff(val startMillis: Long, val endMillis: Long, val appName: String, val pkgName: String) : LedgerContext()
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
fun HatDatePickerModal(initialDate: Long, oldestData: Long, bypassHistoryLimit: Boolean, confirmButtonText: String = "Jump", onDismiss: () -> Unit, onDateSelected: (Long) -> Unit) {
    var selectedDate by remember { mutableStateOf(initialDate) }
    var displayMonthCal by remember { mutableStateOf(Calendar.getInstance().apply { timeInMillis = initialDate; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }) }
    val todayCal = Calendar.getInstance()
    val oldestCal = Calendar.getInstance().apply { timeInMillis = oldestData }
    val canGoBack = bypassHistoryLimit || displayMonthCal.get(Calendar.YEAR) > oldestCal.get(Calendar.YEAR) || (displayMonthCal.get(Calendar.YEAR) == oldestCal.get(Calendar.YEAR) && displayMonthCal.get(Calendar.MONTH) > oldestCal.get(Calendar.MONTH))
    val canGoForward = displayMonthCal.get(Calendar.YEAR) < todayCal.get(Calendar.YEAR) || (displayMonthCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) && displayMonthCal.get(Calendar.MONTH) < todayCal.get(Calendar.MONTH))
    fun addMonth(amount: Int) { val newCal = displayMonthCal.clone() as Calendar; newCal.add(Calendar.MONTH, amount); displayMonthCal = newCal }
    fun isSameDay(a: Long, b: Long): Boolean { val calA = Calendar.getInstance().apply { timeInMillis = a }; val calB = Calendar.getInstance().apply { timeInMillis = b }; return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) && calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR) }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background), shape = RoundedCornerShape(24.dp)) {
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
                        TextButton(onClick = { onDateSelected(selectedDate); onDismiss() }) { Text(confirmButtonText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)) }
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityEditorDialog(initialTitle: String, initialIconName: String, suggestions: List<String>, ledgerContext: LedgerContext? = null, presenceLine: String? = null, onDismiss: () -> Unit, onSave: (String, String, Long) -> Unit, tourNext: (() -> Unit)? = null) {
    var title by remember { mutableStateOf(initialTitle) }
    var selectedIconName by remember { mutableStateOf(initialIconName) }
    // The label's start, taken from the device's evidence. HAT saves the
    // gap it recorded; no manual nudging (owner call, 2026-11-02).
    var adjustedStart by remember(ledgerContext) {
        mutableStateOf(
            when (val ctx = ledgerContext) {
                is LedgerContext.StandardGap -> ctx.startMillis
                is LedgerContext.ScreenOff -> ctx.startMillis
                else -> -1L
            }
        )
    }

    HatOutlinedDialog(onDismissRequest = onDismiss, title = "Label Activity") {
        if (ledgerContext != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                when (ledgerContext) {
                    is LedgerContext.StandardGap -> {
                        presenceLine?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        val timeStr = "${SimpleDateFormat("h:mm a", Locale.getDefault()).format(ledgerContext.startMillis)} to ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(ledgerContext.endMillis)}"
                        val durStr = TimeUtils.formatDuration(ledgerContext.endMillis - ledgerContext.startMillis)
                        // The context line: what was before and after this gap.
                        // The user asked for this back (2026-10-20). The gap
                        // explainer on the card tells you where the time sits
                        // in your day, and the dialog should say the same thing.
                        if (ledgerContext.precededBy != null || ledgerContext.followedBy != null) {
                            val contextStr = when {
                                ledgerContext.precededBy != null && ledgerContext.precededBy == ledgerContext.followedBy -> "Nothing recorded here. ${ledgerContext.precededBy} was open on both sides."
                                ledgerContext.precededBy != null && ledgerContext.followedBy != null -> "Nothing recorded between ${ledgerContext.precededBy} and ${ledgerContext.followedBy}"
                                ledgerContext.precededBy != null -> "Nothing recorded after ${ledgerContext.precededBy}"
                                else -> "Nothing recorded before ${ledgerContext.followedBy}"
                            }
                            Text(contextStr, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Time Range: $timeStr ($durStr)", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is LedgerContext.ScreenOff -> {
                        val timeStr = "${SimpleDateFormat("h:mm a", Locale.getDefault()).format(ledgerContext.startMillis)} to ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(ledgerContext.endMillis)}"
                        val durStr = TimeUtils.formatDuration(ledgerContext.endMillis - ledgerContext.startMillis)
                        Text("Time Range: $timeStr ($durStr)", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("App in focus: ${ledgerContext.appName} (${ledgerContext.pkgName})", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("The screen was off the whole time", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is LedgerContext.BatchMerge -> {
                        Text("Merging ${ledgerContext.count} gaps into one label", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Total: ${TimeUtils.formatDuration(ledgerContext.totalDurationMillis)}. App usage between them stays untouched.", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        DialogHighlightButton("Save", MaterialTheme.colorScheme.primary) { if (title.isNotBlank()) onSave(title, selectedIconName, adjustedStart) }
        // The tour's label-dialog step (r12): the dialog is its own window, so
        // the tour banner lives here, inside it. Next dismisses the dialog and
        // hands back to the main overlay for the settings-gear step.
        if (tourNext != null && andromedvn.heuristic.activity.tracker.domain.AppTourState.current?.id == "labelDialog") {
            Spacer(modifier = Modifier.height(16.dp))
            // Styled like the main tour card: same radius, border, kicker.
            Column(modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)).padding(16.dp)) {
                Text("TOUR ${andromedvn.heuristic.activity.tracker.domain.AppTourState.stepIndex + 1}/${andromedvn.heuristic.activity.tracker.domain.DashboardTourSteps.steps.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(6.dp))
                Text(andromedvn.heuristic.activity.tracker.domain.AppTourState.current?.copy ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { andromedvn.heuristic.activity.tracker.domain.AppTourState.end(); onDismiss() }) { Text("Skip tour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(onClick = { tourNext() }, shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))) { Text("Next", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
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
fun TimeSelector(currentRange: TimeRangeLabel, modifier: Modifier = Modifier, onRangeSelected: (TimeRangeLabel) -> Unit) {
    val options = listOf(TimeRangeLabel.DAY, TimeRangeLabel.WEEK, TimeRangeLabel.MONTH)
    val selectedIndex = options.indexOf(currentRange)
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(52.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(50)).padding(4.dp).selectableGroup()) {
        val itemWidth = maxWidth / 3
        val indicatorOffset by animateDpAsState(targetValue = itemWidth * selectedIndex, animationSpec = tween(300, easing = FastOutSlowInEasing), label = "indicator")
        Box(modifier = Modifier.offset(x = indicatorOffset).width(itemWidth).fillMaxHeight().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, range ->
                val isSelected = index == selectedIndex
                val text = when (range) { TimeRangeLabel.DAY -> "Day"; TimeRangeLabel.WEEK -> "Week"; TimeRangeLabel.MONTH -> "Month" }
                val textColor by animateColorAsState(targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), animationSpec = tween(300, easing = FastOutSlowInEasing), label = "text")
                // selectable() rather than clickable(): a screen reader otherwise
                // announces "Day", "Week" and "Month" as three identical options
                // with no indication of which is active.
                Box(modifier = Modifier.weight(1f).fillMaxHeight().selectable(selected = isSelected, onClick = { onRangeSelected(range) }), contentAlignment = Alignment.Center) { Text(text = text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium), color = textColor) }
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
    val mod = Modifier.fillMaxWidth().padding(bottom = 12.dp).border(if(isHighlighted) 2.dp else 1.dp, color.copy(alpha = if(isHighlighted) 1f else 0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = { navController.navigate(NavRouteCodec.appDetailsRoute(app.packageName)) }, onLongClick = onLongClick)
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
fun MockOfflineListItem(name: String, duration: String, iconName: String, color: Color, progress: Float, isSelectedForAction: Boolean = false, onClick: () -> Unit = {}, onLongClick: () -> Unit = {}, isHighlighted: Boolean = false, storedRowCount: Int = 1) {
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
                        // HONESTY (user decision 2026-10-01): if this entry is backed by more
                        // than one stored row, say so right here. HAT shows duplicates instead
                        // of deleting them, because silently removing the user's data is
                        // exactly what this app must never do.
                        if (storedRowCount > 1) {
                            Text(
                                "$storedRowCount copies saved - tap and hold, then delete, to remove",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
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

// -----------------------------------------------------------------------------
// THEME COMPONENTS
// Ported from HAT Pro (CommonComponents.kt) so the two codebases stay
// diffable. Only deviation: parsing goes through the pure, unit-tested
// parseHexColor/formatHexColor in ui/theme/ColorParsing.kt rather than
// re-implementing the parse inline with android.graphics.Color.parseColor.
// -----------------------------------------------------------------------------

@Composable
fun SettingsGroupParentCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.02f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

@Composable
fun HexColorInput(currentColor: Color, onColorChange: (Color) -> Unit) {
    var textState by remember { mutableStateOf(formatHexColor(argbToStoredLong(currentColor.toArgb()))) }
    var errorState by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Only overwrite the text while the user is not typing, so an in-progress
    // entry is never yanked out from under them by a recomposition.
    LaunchedEffect(currentColor) { if (!isFocused) textState = formatHexColor(argbToStoredLong(currentColor.toArgb())) }
    LaunchedEffect(textState) {
        delay(300)
        val parsed = parseHexColor(textState)
        if (parsed != null) { onColorChange(Color(parsed.toInt())); errorState = false }
        else errorState = textState.isNotBlank()
    }

    Card(modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(16.dp)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("CUSTOM HEX CODE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = textState,
                onValueChange = { input -> var newText = input; if (!newText.startsWith("#")) newText = "#" + newText.replace("#", ""); if (newText.length <= 9) textState = newText.uppercase() },
                label = { Text("e.g. #FF8000") },
                isError = errorState,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            )
            // The support ask lives right here, every time. Not a dialog, not
            // once-per-install: a quiet line under the hex input, always
            // visible while the user is customizing their theme. Making your
            // app yours is the moment you're most likely to appreciate the
            // work that went into it, so that's where the reminder sits.
            Spacer(modifier = Modifier.height(12.dp))
            val context = LocalContext.current
            Text(
                "HAT is free, built by one person. Your support makes more of this possible.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().clickable { context.openExternalWebLink(SPONSOR_URL) }
            )
        }
    }
}

@Composable
fun ThemePreviewBox(derivedColor: Color) {
    // The Pro preview with the user's changes (2026-10-20, trimmed 2026-10-29):
    // - Three elements only: chart bars, calendar circle, D/W/M pill. The
    //   dot+line card is gone (owner's call) - what remains is what the
    //   dashboard actually shows.
    // - Circle shows 22, pill has three segments (D/W/M) matching the
    //   Day/Week/Month switcher.
    // - All elements share a 36dp bounding height for optical alignment.
    Box(
        modifier = Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(16.dp)).background(derivedColor.copy(alpha = 0.05f)).border(1.dp, derivedColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // 1. Chart bars: three, 36dp tall, bottom-aligned, descending
                //    heights so the group reads as one bar chart at a glance.
                Box(modifier = Modifier.height(36.dp), contentAlignment = Alignment.BottomCenter) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                        Canvas(modifier = Modifier.width(12.dp).height(36.dp)) {
                            val strokeW = 1.5.dp.toPx(); val inset = strokeW / 2f; val barSize = Size(size.width - strokeW, size.height - strokeW); val rad = CornerRadius(size.width / 2 - inset)
                            drawRoundRect(derivedColor.copy(alpha = 0.2f), Offset(inset, inset), barSize, rad); drawRoundRect(derivedColor, Offset(inset, inset), barSize, rad, style = Stroke(strokeW))
                        }
                        // Bar 2 is the middle plain bar from v1.1.1.
                        Canvas(modifier = Modifier.width(12.dp).height(24.dp)) {
                            val strokeW = 1.5.dp.toPx(); val inset = strokeW / 2f; val barSize = Size(size.width - strokeW, size.height - strokeW); val rad = CornerRadius(size.width / 2 - inset)
                            drawRoundRect(derivedColor.copy(alpha = 0.2f), Offset(inset, inset), barSize, rad); drawRoundRect(derivedColor, Offset(inset, inset), barSize, rad, style = Stroke(strokeW))
                        }

                        // Bar 3 sits between the other two: taller than the
                        // middle bar, never as tall as the first (owner, 2026-11-02).
                        Canvas(modifier = Modifier.width(12.dp).height(30.dp)) {
                            val strokeW = 1.5.dp.toPx(); val inset = strokeW / 2f; val barSize = Size(size.width - strokeW, size.height - strokeW); val rad = CornerRadius(size.width / 2 - inset)
                            drawRoundRect(derivedColor.copy(alpha = 0.2f), Offset(inset, inset), barSize, rad); drawRoundRect(derivedColor, Offset(inset, inset), barSize, rad, style = Stroke(strokeW))
                        }
                    }
                }
                // 2. Calendar circle: 36dp, shows 22.
                Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(derivedColor.copy(alpha = 0.15f)).border(2.dp, derivedColor, CircleShape), contentAlignment = Alignment.Center) {
                    Text("22", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = derivedColor)
                }
                // 3. The Day/Week/Month range switcher, in miniature. Same grammar
                // as the real TimeSelector on the dashboard (user request,
                // 2026-10-21): pill outline at half alpha, selected segment
                // filled and fully bordered, full words not letters, and
                // unselected labels at the same 60% alpha the app uses.
                Box(modifier = Modifier.width(102.dp).height(36.dp).border(1.dp, derivedColor.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(3.dp)) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).background(derivedColor.copy(alpha = 0.15f)).border(2.dp, derivedColor, RoundedCornerShape(50)), contentAlignment = Alignment.Center) { Text("Day", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp), color = derivedColor) }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Text("Week", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 9.sp), color = derivedColor.copy(alpha = 0.6f)) }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Text("Month", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 9.sp), color = derivedColor.copy(alpha = 0.6f)) }
                    }
                }
            }
        }
    }
}
