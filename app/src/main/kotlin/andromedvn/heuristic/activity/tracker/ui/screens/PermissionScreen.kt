@file:OptIn(ExperimentalFoundationApi::class)

package andromedvn.heuristic.activity.tracker.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.data.ActivityRepository
import andromedvn.heuristic.activity.tracker.data.OfflineStorage
import andromedvn.heuristic.activity.tracker.data.TimeRangeLabel
import andromedvn.heuristic.activity.tracker.ui.components.ChartPoint
import andromedvn.heuristic.activity.tracker.ui.components.HatChart
import andromedvn.heuristic.activity.tracker.ui.components.RealActivityListItem
import andromedvn.heuristic.activity.tracker.ui.components.TimeSelector
import andromedvn.heuristic.activity.tracker.utils.hasUsageStatsPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-launch onboarding, arena II winner "Faithful System" with arena II
 * "Living Preview" fidelity: A's three distinct heroes and two-rail layout,
 * but the heroes are built from the app's real composables, not illustrations.
 *
 *   0. "The records already exist" - a LIVE dashboard replica: the real
 *      TimeSelector (tap Day/Week/Month to switch sample data), the real
 *      HatChart, and real app rows with the device's own app icons.
 *      Captioned EXAMPLE, NOT YOUR DATA.
 *   1. "Gaps stay gaps"            - the real chart showing a day with an
 *                                      empty hour, and the Unlabeled chip.
 *   2. "One switch, yours to flip" - the Usage access switch card; the toggle
 *                                      reflects the live permission state.
 *
 * System back reviews the previous page; the whole flow replays from
 * Settings > How HAT Works (reviewMode exits with Done).
 */
@Composable
fun PermissionsScreen(
    navController: NavController,
    storage: OfflineStorage,
    repository: ActivityRepository,
    reviewMode: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    val pageCount = 3
    val pagerState = rememberPagerState(pageCount = { pageCount })
    var usageGranted by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    var setupCompleted by remember { mutableStateOf(false) }

    val completeSetup: () -> Unit = {
        if (!setupCompleted) {
            setupCompleted = true
            scope.launch {
                val settings = storage.settings.first()
                storage.saveSettings(settings.copy(hasSeenOnboarding = true))
            }
            if (reviewMode) navController.popBackStack()
            else navController.navigate("dashboard") { popUpTo("permissions") { inclusive = true } }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val report = context.contentResolver.openInputStream(uri)?.use { repository.restoreSecureVault(it, context.cacheDir) }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (report != null && report.success) {
                            Toast.makeText(context, "Vault Restored. ${report.offlineMerged} label(s) merged, ${report.offlineSkipped} skipped as overlapping existing ones. Restarting safely...", Toast.LENGTH_LONG).show()
                            delay(1000)
                            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                            val componentName = intent?.component
                            val restartIntent = Intent.makeRestartActivityTask(componentName)
                            context.startActivity(restartIntent)
                            Runtime.getRuntime().exit(0)
                        } else {
                            Toast.makeText(context, "Restore failed: the file is damaged, was edited after export, or is not a HAT vault.", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) { withContext(kotlinx.coroutines.Dispatchers.Main) { Toast.makeText(context, "Failed to read file", Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    // The permission check refreshes on return from system settings; when the
    // grant lands, screen 2's switch flips and the battery card appears.
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageGranted = hasUsageStatsPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // System back reviews the previous page instead of leaving the app.
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    fun goNext() = scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { _ ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { position ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 24.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // Top rail: progress dots left, Skip right (never on the final screen).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(pageCount) { index ->
                            val on = pagerState.currentPage >= index
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (on) primary else primary.copy(alpha = 0.25f))
                            )
                        }
                    }
                    if (position < 2 && !reviewMode) {
                        Text(
                            "Skip",
                            fontSize = 16.sp,
                            color = onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.clickableNoRipple { completeSetup() }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                when (position) {
                    0 -> {
                        Caption("EXAMPLE, NOT YOUR DATA")
                        Spacer(modifier = Modifier.height(8.dp))
                        LiveDashboardHero(navController, primary)
                        Spacer(modifier = Modifier.height(32.dp))
                        Title("The records already exist")
                        Spacer(modifier = Modifier.height(16.dp))
                        Point("Screen time comes from records Android already keeps.", primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Body("Android writes down which app is in front, minute by minute. Those records stay on the phone. HAT reads them and draws simple bars. No account. No sync. Nothing leaves your device.", onSurface)
                    }
                    1 -> {
                        Caption("A DAY WITH A HOLE")
                        Spacer(modifier = Modifier.height(8.dp))
                        GapsHero(primary, onSurface)
                        Spacer(modifier = Modifier.height(32.dp))
                        Title("Gaps stay gaps")
                        Spacer(modifier = Modifier.height(16.dp))
                        Point("Missing time is never guessed or invented.", primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Body("Phones get switched off. Records have holes. When something is missing, HAT leaves it empty and marks it Unlabeled. You see exactly what was recorded, nothing more.", onSurface)
                    }
                    2 -> {
                        Caption("ONE ANDROID SETTING")
                        Spacer(modifier = Modifier.height(8.dp))
                        SwitchHero(usageGranted, primary, onSurface)
                        Spacer(modifier = Modifier.height(32.dp))
                        Title("One switch, yours to flip")
                        Spacer(modifier = Modifier.height(16.dp))
                        Point("You flip the one switch Android requires.", primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Body("Reading the records needs one Android permission: Usage access. Only you can turn it on. HAT reads nothing until it is on.", onSurface)
                        if (usageGranted) {
                            Spacer(modifier = Modifier.height(16.dp))
                            StateCard(
                                label = if (usageGranted) "ALLOWED" else "",
                                body = "One note: Android may pause HAT to save battery. If it does, that day shows a gap. HAT will not invent the missing time.",
                                onSurface = onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Bottom rail: exit label left, outlined pill right.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (position > 0) {
                        Text(
                            "Back",
                            fontSize = 16.sp,
                            color = onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.clickableNoRipple { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }
                        )
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    when (position) {
                        0, 1 -> OnboardingPill("Next") { goNext() }
                        else -> {
                            if (usageGranted) {
                                OnboardingPill("Done") { completeSetup() }
                            } else {
                                OnboardingPill("Continue") {
                                    try { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply { data = android.net.Uri.parse("package:${context.packageName}") }) } catch (e: Exception) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                                }
                            }
                        }
                    }
                }

                if (position == 0 && !reviewMode) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row {
                        Text("Have a backup?  ", fontSize = 13.sp, color = onSurface.copy(alpha = 0.6f))
                        Text(
                            "Restore vault",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = primary,
                            modifier = Modifier.clickableNoRipple { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                        )
                    }
                }
                if (position == 2) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Read these screens anytime: Settings > How HAT Works.",
                        fontSize = 13.sp,
                        color = onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/** Mono caps caption above heroes. */
@Composable
private fun Caption(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    )
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun Point(text: String, primary: Color) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = primary, lineHeight = 22.sp)
}

@Composable
private fun Body(text: String, onSurface: Color) {
    Text(text, fontSize = 16.sp, lineHeight = 24.sp, color = onSurface.copy(alpha = 0.85f))
}

/** HAT's outlined pill action: 1dp border, 15% tint, content-sized, never solid. */
@Composable
private fun OnboardingPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
            .clickableNoRipple(onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

/** Sample data for the live replica, one dataset per TimeRangeLabel. */
private fun samplePoints(range: TimeRangeLabel): List<ChartPoint> = when (range) {
    TimeRangeLabel.DAY -> listOf(
        ChartPoint("9am", listOf(1.2f), "1h 10m", listOf("Social"), "Oct 30"),
        ChartPoint("10am", listOf(2.5f), "2h 30m", listOf("Videos"), "Oct 30"),
        ChartPoint("11am", listOf(0.7f), "0h 45m", listOf("Tools"), "Oct 30"),
        ChartPoint("12pm", listOf(1.9f), "1h 55m", listOf("Social"), "Oct 30"),
        ChartPoint("1pm", listOf(1.1f), "1h 05m", listOf("Games"), "Oct 30", isCurrent = true)
    )
    TimeRangeLabel.WEEK -> listOf(
        ChartPoint("Mon", listOf(3.4f), "3h 25m", listOf("Social"), "Oct 27"),
        ChartPoint("Tue", listOf(5.1f), "5h 05m", listOf("Videos"), "Oct 28"),
        ChartPoint("Wed", listOf(2.2f), "2h 15m", listOf("Tools"), "Oct 29"),
        ChartPoint("Thu", listOf(4.6f), "4h 35m", listOf("Social"), "Oct 30"),
        ChartPoint("Fri", listOf(6.0f), "6h 00m", listOf("Videos"), "Oct 31", isCurrent = true),
        ChartPoint("Sat", listOf(0f), "0m", emptyList(), "Nov 1", isFuture = true),
        ChartPoint("Sun", listOf(0f), "0m", emptyList(), "Nov 2", isFuture = true)
    )
    TimeRangeLabel.MONTH -> listOf(
        ChartPoint("W1", listOf(31.5f), "31h 30m", listOf("All"), "Oct 1"),
        ChartPoint("W2", listOf(38.2f), "38h 10m", listOf("All"), "Oct 8"),
        ChartPoint("W3", listOf(29.7f), "29h 40m", listOf("All"), "Oct 15"),
        ChartPoint("W4", listOf(35.1f), "35h 05m", listOf("All"), "Oct 22", isCurrent = true)
    )
}

private fun sampleTotal(range: TimeRangeLabel): String = when (range) {
    TimeRangeLabel.DAY -> "6h 45m"
    TimeRangeLabel.WEEK -> "21h 20m"
    TimeRangeLabel.MONTH -> "134h 25m"
}

private fun sampleDateLabel(range: TimeRangeLabel): Pair<String, String> = when (range) {
    TimeRangeLabel.DAY -> "Today" to "Oct 30, 2025"
    TimeRangeLabel.WEEK -> "This week" to "Oct 27 - Nov 2"
    TimeRangeLabel.MONTH -> "October" to "Oct 1 - Oct 31"
}

/** Screen 0 hero: a live dashboard replica - real TimeSelector, real HatChart, real app rows. */
@Composable
private fun LiveDashboardHero(navController: NavController, primary: Color) {
    var selectedRange by remember { mutableStateOf(TimeRangeLabel.WEEK) }
    val labels = sampleDateLabel(selectedRange)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        TimeSelector(currentRange = selectedRange, onRangeSelected = { selectedRange = it })
        Spacer(modifier = Modifier.height(8.dp))
        HatChart(
            modifier = Modifier.heightIn(max = 300.dp),
            title = "Activity Overview",
            subTitle = "Apps Usage + Offline Time",
            totalTime = sampleTotal(selectedRange),
            dataPoints = samplePoints(selectedRange),
            palette = listOf(primary, primary),
            dateLabelRelative = labels.first,
            dateLabelExact = labels.second,
            hasNext = false,
            hasPrev = false,
            onPointSelected = null,
            onPrevious = null,
            onNext = null,
            onDateClick = null
        )
        Spacer(modifier = Modifier.height(4.dp))
        val rows = listOf(
            Triple("com.whatsapp", "WhatsApp", "14h 12m"),
            Triple("com.instagram.android", "Instagram", "9h 48m"),
            Triple("com.android.chrome", "Chrome", "4h 05m")
        )
        rows.forEach { (pkg, name, time) ->
            val fake = andromedvn.heuristic.activity.tracker.data.AppUsageItem(
                packageName = pkg, title = name, durationInMillis = 1L, timestamp = 0L
            )
            RealActivityListItem(navController = navController, app = fake, color = primary, progress = 0.8f)
        }
    }
}

/** Screen 1 hero: the real chart with an empty hour, and the Unlabeled chip at real size. */
@Composable
private fun GapsHero(primary: Color, onSurface: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, onSurface.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        HatChart(
            modifier = Modifier.heightIn(max = 280.dp),
            title = "Activity Overview",
            subTitle = "Apps Usage + Offline Time",
            totalTime = "5h 30m",
            dataPoints = listOf(
                ChartPoint("9am", listOf(1.2f), "1h 10m", listOf("Social"), "Oct 30"),
                ChartPoint("10am", listOf(2.5f), "2h 30m", listOf("Videos"), "Oct 30"),
                ChartPoint("11am", listOf(0f), "0m", emptyList(), "Oct 30"),
                ChartPoint("12pm", listOf(0.8f), "0h 45m", listOf("Tools"), "Oct 30"),
                ChartPoint("1pm", listOf(1.1f), "1h 05m", listOf("Games"), "Oct 30")
            ),
            palette = listOf(primary, primary),
            dateLabelRelative = "Today",
            dateLabelExact = "Oct 30, 2025",
            hasNext = false,
            hasPrev = false,
            onPointSelected = null,
            onPrevious = null,
            onNext = null,
            onDateClick = null
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            UnlabeledChip(primary, onSurface)
            Spacer(modifier = Modifier.width(10.dp))
            Text("11am was never recorded", fontSize = 12.sp, color = onSurface.copy(alpha = 0.55f))
        }
    }
}

/** The Unlabeled chip as HAT renders state chips: 1dp border, 15% tint, hollow ring. */
@Composable
private fun UnlabeledChip(primary: Color, onSurface: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(primary.copy(alpha = 0.15f))
            .border(1.dp, primary.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).border(1.5.dp, primary, CircleShape))
        Text("Unlabeled", fontSize = 14.sp, color = onSurface)
    }
}

/** Screen 2 hero: the Usage access switch card; the toggle mirrors the live grant state. */
@Composable
private fun SwitchHero(usageGranted: Boolean, primary: Color, onSurface: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(2.dp, primary, RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(primary.copy(alpha = 0.15f))
                .border(2.dp, primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("H", fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = primary)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Usage access", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
            Text("Android setting", fontSize = 12.sp, color = onSurface.copy(alpha = 0.55f))
        }
        // The live state: 44x24 outlined toggle, knob left (off) or right (on).
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 24.dp)
                .clip(RoundedCornerShape(50))
                .border(2.dp, if (usageGranted) primary else primary.copy(alpha = 0.6f), RoundedCornerShape(50))
                .background(primary.copy(alpha = if (usageGranted) 0.15f else 0.05f))
        ) {
            Box(
                modifier = Modifier
                    .align(if (usageGranted) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(3.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = if (usageGranted) 0.9f else 0.35f))
                    .border(2.dp, primary, CircleShape)
            )
        }
    }
}

/** Granted-state note as a bordered card. */
@Composable
private fun StateCard(label: String, body: String, onSurface: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, onSurface.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        if (label.isNotEmpty()) {
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp, color = onSurface.copy(alpha = 0.55f))
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(body, fontSize = 15.sp, lineHeight = 22.sp, color = onSurface.copy(alpha = 0.85f))
    }
}

/** Click without the ripple overhead for quiet text exits. */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}
