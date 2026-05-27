package andromedvn.heuristic.activity.tracker.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.data.*
import andromedvn.heuristic.activity.tracker.ui.components.HatDynamicHeader
import andromedvn.heuristic.activity.tracker.ui.components.HatOutlinedDialog
import andromedvn.heuristic.activity.tracker.ui.components.hatSwitchColors
import andromedvn.heuristic.activity.tracker.worker.WorkerScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(navController: NavController, repository: ActivityRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = remember { OfflineStorage.getInstance(context) }
    
    val currentSettings by storage.settings.collectAsState(initial = null)
    if (currentSettings == null) return 
    
    val scrollState = rememberScrollState()
    var customDialogSetting by remember { mutableStateOf<String?>(null) } 
    var localSortType by remember(currentSettings?.sortType) { mutableStateOf(currentSettings?.sortType ?: SortType.DURATION) }

    val appVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0-FOSS"
        } catch (e: Exception) { "1.0-FOSS" }
    }

    val updateSettings: (UserSettings) -> Unit = { newSet -> 
        scope.launch {
            storage.saveSettings(newSet)
            WorkerScheduler.updateWorkers(context, newSet.archiveSyncIntervalHours)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { repository.exportSecureVault(it) }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Master Vault Exported", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Export Failed", Toast.LENGTH_SHORT).show() } }
            }
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
                        } else {
                            Toast.makeText(context, "Error: Invalid or Tampered File", Toast.LENGTH_LONG).show() 
                        }
                    }
                } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to read file", Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    val openWebLink = { url: String ->
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
    }

    if (customDialogSetting != null) {
        var input by remember { mutableStateOf("") }
        HatOutlinedDialog(onDismissRequest = { customDialogSetting = null }, title = "Custom Value") {
            OutlinedTextField(
                value = input, onValueChange = { if (it.all { char -> char.isDigit() }) input = it }, 
                label = { Text(if (customDialogSetting == "gap" || customDialogSetting == "cluster") "Minutes" else "Hours") }, 
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { customDialogSetting = null }) { Text("Cancel", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(modifier = Modifier.width(16.dp))
                TextButton(onClick = { 
                    val value = input.toIntOrNull() ?: 0
                    when (customDialogSetting) {
                        "gap" -> updateSettings(currentSettings!!.copy(minGapThresholdMins = value))
                        "cluster" -> updateSettings(currentSettings!!.copy(sessionClusteringMins = value))
                        "archive" -> updateSettings(currentSettings!!.copy(archiveSyncIntervalHours = value))
                    }
                    customDialogSetting = null
                }) { Text("Save", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0,0,0,0)) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()).windowInsetsPadding(WindowInsets.statusBars).verticalScroll(scrollState)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                HatDynamicHeader(title = "Settings", subtitle = "App Configuration")

                // GROUP 1: THEME ENGINE
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("THEME ENGINE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
                    ThemeOptionItem("Dynamic Material", "Extracts colors from system wallpaper.", currentSettings!!.themeType == ThemeType.DYNAMIC) { updateSettings(currentSettings!!.copy(themeType = ThemeType.DYNAMIC)) }
                    ThemeOptionItem("Default Material", "Uses the classic HAT Orange.", currentSettings!!.themeType == ThemeType.STATIC) { updateSettings(currentSettings!!.copy(themeType = ThemeType.STATIC)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))

                // GROUP 2: DATA CONTROL
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DATA CONTROL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
                    SettingsItem("Hidden Apps", "Show or hide apps from your timeline.") { navController.navigate("hidden_apps") }
                    SettingsItem("Backup Master Vault", "Export full timeline, app history, and settings.") { val date = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault()).format(Date()); exportLauncher.launch("HAT_Vault_$date.zip") }
                    SettingsItem("Restore Master Vault", "Import a previously saved master vault.") { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))

                // GROUP 3: HEURISTIC ENGINE
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("HEURISTIC ENGINE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
                    HatSlidingSelector<SortType>("Global Timeline Sort", "Determines the order of apps and activities\non the main dashboard cards.", listOf("Duration" to SortType.DURATION, "Recent" to SortType.RECENT, "Chrono" to SortType.CHRONOLOGICAL), localSortType, "", { localSortType = it; updateSettings(currentSettings!!.copy(sortType = it)) }, null)
                    HatSlidingSelector<Int>("Background History Sync", "Wakes up periodically to archive your usage history before Android deletes it.", listOf("Off" to 0, "2h" to 2, "6h" to 6, "Custom" to -1), currentSettings!!.archiveSyncIntervalHours, "h", { updateSettings(currentSettings!!.copy(archiveSyncIntervalHours = it)) }, { customDialogSetting = "archive" })
                    HatSlidingSelector<Int>("Actionable Gap Minimum", "Hides tiny gaps from your labeling list so you aren't asked to categorize quick bathroom breaks.\nThe time itself is never deleted from your timeline.", listOf("Off" to 0, "5m" to 5, "15m" to 15, "Custom" to -1), currentSettings!!.minGapThresholdMins, "m", { updateSettings(currentSettings!!.copy(minGapThresholdMins = it)) }, { customDialogSetting = "gap" })
                    HatSlidingSelector<Int>("Session Merge Tolerance", "If you switch apps and come back within this window,\nHAT treats it as one continuous session.", listOf("Off" to 0, "1m" to 1, "5m" to 5, "Custom" to -1), currentSettings!!.sessionClusteringMins, "m", { updateSettings(currentSettings!!.copy(sessionClusteringMins = it)) }, { customDialogSetting = "cluster" })
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))

                // GROUP 4: ABOUT & LEGAL
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ABOUT & LEGAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
                    SettingsItem("Privacy Policy", "How HAT processes your usage data locally.") { openWebLink("https://andromedvn.github.io/HAT/PRIVACY.html") }
                    SettingsItem("Terms of Service", "Conditions of use and liability limitations.") { openWebLink("https://andromedvn.github.io/HAT/TERMS.html") }
                    SettingsItem("Open Source Licenses", "Third-party software attributions.") { navController.navigate("licenses") }
                    
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                        Text("HAT Engine v$appVersion", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// -----------------------------------------------------------------------------
// LOCAL UI COMPONENTS
// -----------------------------------------------------------------------------

@Composable
fun <T> HatSlidingSelector(title: String, description: String, options: List<Pair<String, T>>, selectedValue: T, unitLabel: String = "", onSelect: (T) -> Unit, onCustomClick: (() -> Unit)? = null) {
    var internalValue by remember(selectedValue) { mutableStateOf(selectedValue) }
    val activeIndex = options.indexOfFirst { it.second == internalValue }.takeIf { it >= 0 } ?: (options.size - 1)
    
    Card(modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(16.dp)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(48.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(4.dp)) {
                val itemWidth = maxWidth / options.size
                val indicatorOffset by animateDpAsState(targetValue = itemWidth * activeIndex, animationSpec = tween(300, easing = FastOutSlowInEasing), label = "indicator")

                Box(modifier = Modifier.offset(x = indicatorOffset).width(itemWidth).fillMaxHeight().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                Row(modifier = Modifier.fillMaxSize()) {
                    options.forEachIndexed { index, pair ->
                        val isSelected = index == activeIndex
                        val label = if (index == options.lastIndex && activeIndex == options.lastIndex && pair.second != selectedValue) "${selectedValue}${unitLabel}" else pair.first
                        val textColor by animateColorAsState(targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), animationSpec = tween(300), label = "text")
                        
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { 
                                if (index == options.lastIndex && onCustomClick != null && pair.second is Int && pair.second == -1) onCustomClick()
                                else { internalValue = pair.second; onSelect(pair.second) }
                            }), 
                            contentAlignment = Alignment.Center
                        ) { Text(text = label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium), color = textColor) }
                    }
                }
            }
        }
    }
}

@Composable
fun ThemeOptionItem(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.background)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun SettingsItem(title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column { Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}
