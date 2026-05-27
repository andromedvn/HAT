@file:OptIn(ExperimentalFoundationApi::class)

package andromedvn.heuristic.activity.tracker.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Start
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.R
import andromedvn.heuristic.activity.tracker.data.OfflineStorage
import andromedvn.heuristic.activity.tracker.data.ActivityRepository
import andromedvn.heuristic.activity.tracker.utils.hasUsageStatsPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PermissionsScreen(navController: NavController, storage: OfflineStorage, repository: ActivityRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val pageCount = 3
    val pagerState = rememberPagerState(pageCount = { pageCount + 1 })
    var skipRestore by remember { mutableStateOf(false) }

    val completeSetup = {
        scope.launch {
            val settings = storage.settings.first()
            storage.saveSettings(settings.copy(hasSeenOnboarding = true))
            navController.navigate("dashboard") { popUpTo("permissions") { inclusive = true } }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val success = context.contentResolver.openInputStream(uri)?.use { repository.restoreSecureVault(it, context.cacheDir) } ?: false
                    withContext(Dispatchers.Main) { 
                        if(success) {
                            Toast.makeText(context, "Vault Restored. Restarting safely...", Toast.LENGTH_LONG).show()
                            delay(1000)
                            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                            val componentName = intent?.component
                            val restartIntent = Intent.makeRestartActivityTask(componentName)
                            context.startActivity(restartIntent)
                            Runtime.getRuntime().exit(0)
                        } else { Toast.makeText(context, "Error: Invalid or Tampered File", Toast.LENGTH_LONG).show() }
                    }
                } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to read file", Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (hasUsageStatsPermission(context)) {
                    if (pagerState.currentPage == 2) {
                        scope.launch { pagerState.animateScrollToPage(pageCount) } 
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == pageCount) completeSetup()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()).fillMaxSize().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            HorizontalPager(state = pagerState, userScrollEnabled = pagerState.currentPage < pageCount, modifier = Modifier.weight(1f).fillMaxWidth()) { position ->
                if (position < pageCount) {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
                        Spacer(modifier = Modifier.height(16.dp))
                        when (position) {
                            0 -> {
                                Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                                    Image(painter = painterResource(id = R.drawable.ic_launcher_foreground), contentDescription = "HAT Logo", colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary), contentScale = ContentScale.FillBounds, modifier = Modifier.requiredSize(84.dp))
                                }
                                Spacer(modifier = Modifier.height(32.dp))
                                Text("Welcome to HAT", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("A passive activity tracker for life beyond the screen — because life happens offline.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(48.dp))
                                PrivacyPoint(Icons.Default.Timeline, "HAT translates your raw screen usage into a readable timeline.")
                                Spacer(modifier = Modifier.height(24.dp))
                                PrivacyPoint(Icons.Default.Splitscreen, "Active apps and empty gaps are shown exactly as they happened.")
                                Spacer(modifier = Modifier.height(24.dp))
                                PrivacyPoint(Icons.Default.Edit, "Label the offline moments or leave them empty. It's your call.")
                                Spacer(modifier = Modifier.height(24.dp))
                                PrivacyPoint(Icons.Default.Restore, "Swipe left to restore a previous vault, or tap below to start fresh.")
                            }
                            1 -> {
                                Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.SettingsBackupRestore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                                }
                                Spacer(modifier = Modifier.height(32.dp))
                                Text("Restore Vault", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Switching devices? Import your saved timeline and settings to pick up exactly where you left off.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(48.dp))
                                PrivacyPoint(Icons.Default.SettingsBackupRestore, "Labels, hidden apps, and preferences come back exactly as you left them.")
                                Spacer(modifier = Modifier.height(24.dp))
                                PrivacyPoint(Icons.Default.History, "Offline activities and idle sessions are included in the restore.")
                                Spacer(modifier = Modifier.height(24.dp))
                                PrivacyPoint(Icons.Default.Sync, "The timeline stays continuous — no gaps created by the device switch.")
                                Spacer(modifier = Modifier.height(24.dp))
                                PrivacyPoint(Icons.Default.Start, "Tap the Restore Vault button below to start the migration.")
                            }
                            2 -> {
                                Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                                }
                                Spacer(modifier = Modifier.height(32.dp))
                                Text("Usage Access Required", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("HAT reads activity states only. We never access your messages, content, or personal data.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(48.dp))
                                PrivacyPoint(Icons.Default.VisibilityOff, "Android requires \"Usage Access\" so HAT can see when apps move to the foreground or background.")
                                Spacer(modifier = Modifier.height(24.dp))
                                PrivacyPoint(Icons.Default.OfflineBolt, "This allows HAT to detect when your screen was active and when it was off.")
                                Spacer(modifier = Modifier.height(24.dp))
                                PrivacyPoint(Icons.Default.Security, "All data is processed entirely on your device. HAT does not have internet permissions and cannot upload your timeline.")
                            }
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                } else Box(modifier = Modifier.fillMaxSize())
            }

            if (pagerState.currentPage < pageCount) {
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
                        0 -> OutlinedButton(onClick = { skipRestore = true; scope.launch { pagerState.animateScrollToPage(2) } }, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) { Text("Start Fresh", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                        1 -> if (!skipRestore) OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) { Text("Restore Vault", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) } else Spacer(modifier = Modifier.width(1.dp))
                        2 -> OutlinedButton(onClick = { try { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") }) } catch(e: Exception) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } }, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) { Text("Grant Access", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
fun PrivacyPoint(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp).padding(top = 2.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
