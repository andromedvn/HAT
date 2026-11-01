@file:OptIn(ExperimentalFoundationApi::class)

package andromedvn.heuristic.activity.tracker.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.R
import andromedvn.heuristic.activity.tracker.data.OfflineStorage
import andromedvn.heuristic.activity.tracker.data.ActivityRepository
import andromedvn.heuristic.activity.tracker.domain.AppTourState
import andromedvn.heuristic.activity.tracker.utils.hasUsageStatsPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-launch onboarding (2026-11-01): the v1.1.1 anatomy the owner asked to
 * bring back, three screens. Welcome (the original: 72dp tinted launcher-glyph
 * box, centered headline, PrivacyPoint rows, animated pill-dots) -> the Usage
 * access ask (the original page with its PrivacyPoints) -> the battery
 * exemption as its own screen (the old page-3 ask, back in the flow where the
 * owner wants it). Continue on the battery page launches the app tour.
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

    val pageCount = 3
    val pagerState = rememberPagerState(pageCount = { pageCount })
    var usageGranted by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    var batteryExempt by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    var setupCompleted by remember { mutableStateOf(false) }

    // markTour=true only from the battery page: Skip lands on the dashboard
    // without the tour (the permission card waits there instead).
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
                AppTourState.start()
                navController.navigate("dashboard") { popUpTo("permissions") { inclusive = true } }
            } else {
                navController.navigate("dashboard") { popUpTo("permissions") { inclusive = true } }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val report = context.contentResolver.openInputStream(uri)?.use { repository.restoreSecureVault(it, context.cacheDir) }
                    withContext(Dispatchers.Main) {
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
                } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to read file", Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    // The permission check refreshes on return from system settings; the
    // moment the grant is real, "Grant Access" becomes "Continue".
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageGranted = hasUsageStatsPermission(context)
                batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // System back reviews the previous page instead of leaving the app.
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1, animationSpec = tween(400, easing = FastOutSlowInEasing)) }
    }

    fun goNext() = scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1, animationSpec = tween(400, easing = FastOutSlowInEasing)) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()).fillMaxSize().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { position ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    when (position) {
                        0 -> {
                            Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                                Image(painter = painterResource(id = R.drawable.ic_launcher_foreground), contentDescription = "HAT Logo", colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary), contentScale = ContentScale.FillBounds, modifier = Modifier.requiredSize(84.dp))
                            }
                            Spacer(modifier = Modifier.height(32.dp))
                            Text("Welcome to HAT", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("HAT counts your screen time and keeps it on this phone. No account. No internet.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(48.dp))
                            PrivacyPoint(Icons.Default.Timeline, "HAT translates your raw screen usage into a readable timeline.")
                            Spacer(modifier = Modifier.height(24.dp))
                            PrivacyPoint(Icons.Default.Splitscreen, "Active apps and empty gaps are shown exactly as they happened.")
                            Spacer(modifier = Modifier.height(24.dp))
                            PrivacyPoint(Icons.Default.Edit, "Label the offline moments or leave them empty. It's your call.")
                            Spacer(modifier = Modifier.height(24.dp))
                            PrivacyPoint(Icons.Default.Security, "No internet permission. Nothing can leave this phone.")
                        }
                        1 -> {
                            Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                            }
                            Spacer(modifier = Modifier.height(32.dp))
                            Text("Usage Access Required", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("HAT reads activity states only. We never access your messages, content, or personal data.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(48.dp))
                            PrivacyPoint(Icons.Default.Security, "Android requires \"Usage Access\" so HAT can see when apps move to the foreground or background.")
                            Spacer(modifier = Modifier.height(24.dp))
                            PrivacyPoint(Icons.Default.OfflineBolt, "This allows HAT to detect when your screen was active and when it was off.")
                            Spacer(modifier = Modifier.height(24.dp))
                            PrivacyPoint(Icons.Default.Security, "All data is processed entirely on your device. HAT does not have internet permissions and cannot upload your timeline.")
                        }
                        2 -> {
                            Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.BatterySaver, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                            }
                            Spacer(modifier = Modifier.height(32.dp))
                            Text("Keep Your History Safe", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Android deletes old usage records after a few days. HAT archives them first so your history survives.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(48.dp))
                            PrivacyPoint(Icons.Default.OfflineBolt, "Battery savers can stop that archive mid-save. When it stops, days of history are gone for good.")
                            Spacer(modifier = Modifier.height(24.dp))
                            PrivacyPoint(Icons.Default.BatterySaver, "Exempting HAT is a normal Android setting. It does not disable battery saving for anything else.")
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }

            // Bottom bar: the v1.1.1 animated pill-dots + the OutlinedButton.
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(pageCount) { index ->
                        val isSelected = pagerState.currentPage == index
                        val width by animateDpAsState(targetValue = if (isSelected) 24.dp else 8.dp, animationSpec = tween(300, easing = FastOutSlowInEasing), label = "width")
                        val color by animateColorAsState(targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), animationSpec = tween(300), label = "color")
                        Box(modifier = Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
                    }
                }
                when (pagerState.currentPage) {
                    0 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!reviewMode) Text("Skip", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickableNoRipple { completeSetup(false) })
                            OnboardingOutlinedButton("Next") { goNext() }
                        }
                    }
                    1 -> {
                        if (usageGranted) {
                            OnboardingOutlinedButton("Continue") { goNext() }
                        } else {
                            OnboardingOutlinedButton("Grant Access") {
                                try { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") }) } catch (e: Exception) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                            }
                        }
                    }
                    else -> {
                        if (batteryExempt) {
                            OnboardingOutlinedButton(if (reviewMode) "Done" else "See Your Dashboard") { completeSetup(!reviewMode) }
                        } else {
                            OnboardingOutlinedButton("Exempt Battery") {
                                try { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${context.packageName}") }) } catch (e: Exception) { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The v1.1.1 page button: 16dp radius, 15% fill, 2dp border, titleMedium Bold. */
@Composable
private fun OnboardingOutlinedButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    ) { Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
}

/** The v1.1.1 PrivacyPoint row, verbatim. */
@Composable
fun PrivacyPoint(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp).padding(top = 2.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
