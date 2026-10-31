package andromedvn.heuristic.activity.tracker.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.R
import andromedvn.heuristic.activity.tracker.data.OfflineStorage
import andromedvn.heuristic.activity.tracker.data.ActivityRepository
import andromedvn.heuristic.activity.tracker.ui.components.HatDynamicHeader
import andromedvn.heuristic.activity.tracker.ui.components.PromptHighlightButton
import andromedvn.heuristic.activity.tracker.ui.components.PromptOutlineButton
import andromedvn.heuristic.activity.tracker.ui.components.hatSwitchColors
import andromedvn.heuristic.activity.tracker.utils.hasUsageStatsPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-launch onboarding (2026-11-01, native rebuild): two screens built from
 * the app's own components so they read as HAT, not as a web page. Every
 * screen opens with the same HatDynamicHeader as the dashboard; actions are
 * the real PromptHighlightButton / PromptOutlineButton pills; the permission
 * switch is a real Material3 Switch in hatSwitchColors(); the icon card uses
 * the SummaryStatCard border anatomy.
 *
 *   0. Welcome - the v1.1.1 launcher icon (the adaptive icon's own layers),
 *      one honest paragraph, Restore vault footnote.
 *   1. One permission, read only - the live Usage access switch. After the
 *      grant the pill becomes "See your dashboard", which launches the
 *      spotlight tour (startTour=true).
 *
 * System back reviews the previous page; the flow replays from
 * Settings > How HAT Works (reviewMode exits with Done, no tour launch).
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

    val pageCount = 2
    val pagerState = rememberPagerState(pageCount = { pageCount })
    var usageGranted by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    var setupCompleted by remember { mutableStateOf(false) }

    // markTour=true only from the granted path: Skip and Not now land on the
    // dashboard without the tour (the permission card waits there instead).
    val completeSetup: (Boolean) -> Unit = { markTour ->
        if (!setupCompleted) {
            setupCompleted = true
            scope.launch {
                val settings = storage.settings.first()
                storage.saveSettings(settings.copy(hasSeenOnboarding = true))
            }
            if (reviewMode) {
                navController.popBackStack()
            } else if (markTour) {
                navController.navigate("dashboard?startTour=true") { popUpTo("permissions") { inclusive = true } }
            } else {
                navController.navigate("dashboard") { popUpTo("permissions") { inclusive = true } }
            }
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

    // The permission check refreshes on return from system settings; the
    // moment the grant is real, the pill becomes "See your dashboard".
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
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                HatDynamicHeader(
                    title = if (position == 0) "Welcome to HAT" else "One permission",
                    subtitle = if (position == 0) "First run" else "Read only"
                )

                when (position) {
                    0 -> {
                        Spacer(modifier = Modifier.height(24.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // The launcher icon inside the app's card anatomy
                            // (1dp 50% border, 24dp radius, transparent fill).
                            Box(
                                modifier = Modifier
                                    .size(148.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .border(1.dp, primary.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.background),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(112.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.ic_launcher_background),
                                        contentDescription = null,
                                        modifier = Modifier.matchParentSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Image(
                                        painter = painterResource(R.drawable.ic_launcher_foreground),
                                        contentDescription = "HAT",
                                        modifier = Modifier.matchParentSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            "HAT counts your screen time. Everything stays on this phone. No account. No internet. No one reads it but you.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = onSurface.copy(alpha = 0.85f)
                        )
                    }
                    1 -> {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "HAT reads the usage records Android already keeps. It cannot change settings, read your files, or send anything anywhere. You can turn it off any time in Android settings. Tap Allow access, then come back.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = onSurface.copy(alpha = 0.85f)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        // The real switch, in the app's own colors, inside the
                        // HatOutlinedDialog card anatomy (2dp primary border,
                        // 24dp radius).
                        Card(
                            modifier = Modifier.fillMaxWidth().border(2.dp, primary, RoundedCornerShape(24.dp)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Usage access", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = onSurface)
                                    Text("Android setting", style = MaterialTheme.typography.bodyMedium, color = onSurface.copy(alpha = 0.55f))
                                }
                                Switch(checked = usageGranted, onCheckedChange = null, colors = hatSwitchColors())
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Bottom rail: the app's real button pair, end-aligned.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (position) {
                        0 -> {
                            if (!reviewMode) {
                                PromptOutlineButton("Skip") { completeSetup(false) }
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            PromptHighlightButton("Next") { goNext() }
                        }
                        else -> {
                            if (usageGranted) {
                                PromptHighlightButton(if (reviewMode) "Done" else "See your dashboard") { completeSetup(!reviewMode) }
                            } else {
                                PromptOutlineButton("Not now") { completeSetup(false) }
                                Spacer(modifier = Modifier.width(12.dp))
                                PromptHighlightButton("Allow access") {
                                    try { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply { data = android.net.Uri.parse("package:${context.packageName}") }) } catch (e: Exception) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                if (position == 0 && !reviewMode) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Text("Have a backup?  ", style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.6f))
                        Text(
                            "Restore vault",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = primary,
                            modifier = Modifier.clickableNoRipple { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                        )
                    }
                } else if (position == 1) {
                    Text(
                        "Read these screens anytime: Settings > How HAT Works.",
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/** Quiet text exit without the ripple (links only; actions use the real pills). */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}
