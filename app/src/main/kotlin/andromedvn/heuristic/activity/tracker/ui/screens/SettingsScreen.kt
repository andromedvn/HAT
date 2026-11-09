package andromedvn.heuristic.activity.tracker.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.data.*
import andromedvn.heuristic.activity.tracker.domain.DetailLevel
import andromedvn.heuristic.activity.tracker.domain.startOfLocalDay
import andromedvn.heuristic.activity.tracker.domain.nextLocalMidnight
import andromedvn.heuristic.activity.tracker.domain.HeuristicEngine
import andromedvn.heuristic.activity.tracker.domain.shouldShowSupportPrompt
import andromedvn.heuristic.activity.tracker.ui.components.*
import andromedvn.heuristic.activity.tracker.ui.theme.argbToStoredLong
import andromedvn.heuristic.activity.tracker.utils.HatLogger
import andromedvn.heuristic.activity.tracker.utils.restoreVaultAndRestart
import andromedvn.heuristic.activity.tracker.utils.SPONSOR_URL
import andromedvn.heuristic.activity.tracker.utils.openExternalWebLink
import andromedvn.heuristic.activity.tracker.utils.TimeUtils
import andromedvn.heuristic.activity.tracker.utils.UsageStatsEngine
import andromedvn.heuristic.activity.tracker.worker.WorkerScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(navController: NavController, repository: ActivityRepository) {
    val tourRegistry = andromedvn.heuristic.activity.tracker.ui.components.AppTourRegistry
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = remember { OfflineStorage.getInstance(context) }
    
    val currentSettings by storage.settings.collectAsState(initial = null)
    if (currentSettings == null) return 
    
    val scrollState = rememberScrollState()
    var customDialogSetting by remember { mutableStateOf<String?>(null) } 
    var showSupportPrompt by remember { mutableStateOf(false) }
    var localSortType by remember(currentSettings?.sortType) { mutableStateOf(currentSettings?.sortType ?: SortType.DURATION) }

    var showExportDatePicker by rememberSaveable { mutableStateOf(false) }
    var oldestDataTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        oldestDataTimestamp = repository.getOldestDataTimestamp()
    }

    val appVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0-FOSS"
        } catch (e: Exception) { "1.0-FOSS" }
    }

    val updateSettings: (UserSettings) -> Unit = { newSet -> 
        scope.launch {
            storage.saveSettings(newSet)
            WorkerScheduler.updateWorkers(context, newSet.archiveSyncIntervalHours, newSet.autoBackupIntervalHours)
        }
    }

    // Rolling Vault (r12p): picking the folder grants persistable write
    // permission; the schedule then overwrites HAT_AutoBackup.zip in place.
    val backupFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            updateSettings(currentSettings!!.copy(autoBackupUri = uri.toString()))
            andromedvn.heuristic.activity.tracker.worker.WorkerScheduler.updateWorkers(context, currentSettings!!.archiveSyncIntervalHours, if (currentSettings!!.autoBackupIntervalHours > 0) currentSettings!!.autoBackupIntervalHours else 0)
            Toast.makeText(context, "Backup folder set. HAT_AutoBackup.zip will be written there on schedule.", Toast.LENGTH_LONG).show()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val ok = context.contentResolver.openOutputStream(uri)?.use { repository.exportSecureVault(it) } ?: false
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (ok) "Master Vault Exported" else "Export Failed - database was busy, try again",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Export Failed", Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch { restoreVaultAndRestart(context, repository, uri) }
        }
    }

    if (showExportDatePicker) {
        HatDatePickerModal(
            initialDate = System.currentTimeMillis(),
            oldestData = oldestDataTimestamp,
            bypassHistoryLimit = currentSettings!!.bypassHistoryLimit,
            confirmButtonText = "Copy",
            onDismiss = { showExportDatePicker = false },
            onDateSelected = { selectedDateMillis ->
                showExportDatePicker = false
                scope.launch(Dispatchers.Default) {
                    try {
                        val engine = HeuristicEngine(repository, UsageStatsEngine(context))
                        
                        val start = startOfLocalDay(selectedDateMillis)
                        val end = nextLocalMidnight(selectedDateMillis) - 1
                        
                        val hiddenPkgs = storage.getHiddenPackagesFlow().first()
                        
                        val (_, screenTime, topAppsList) = engine.getAppUsage(start, end, TimeRangeLabel.DAY, hiddenPkgs)
                        val offlineItems = engine.getOfflineActivities(start, end, TimeRangeLabel.DAY)
                        
                        // Must be sharded against app foreground time, exactly as the
                        // dashboard does. Summing raw offline durations double-counted
                        // any overlap, and the clamp below then silently absorbed the
                        // error into a wrong "Total Tracked" instead of surfacing it.
                        // This is exported text the user reads and shares, so an
                        // inconsistent pair of numbers matters more here than anywhere.
                        val totalOfflineMillis = engine
                            .getShardedOfflineIntervals(start, end)
                            .sumOf { it.second - it.first }
                        val totalCombinedRaw = screenTime + totalOfflineMillis
                        val clampedCombined = minOf(totalCombinedRaw, end - start)
                        val clampedOffline = if (totalCombinedRaw > end - start) (end - start) - screenTime else totalOfflineMillis
            
                        val dateStr = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(start))
                        
                        val sb = StringBuilder()
                        sb.appendLine("HAT Daily Summary - $dateStr")
                        sb.appendLine("-----------------------------------")
                        sb.appendLine("[Offline Activities]")
                        if (offlineItems.isEmpty()) sb.appendLine("None logged.")
                        else offlineItems.forEach { sb.appendLine("- ${it.title}: ${it.timeFormatted}") }
                        
                        sb.appendLine("\n[Apps Usage]")
                        if (topAppsList.isEmpty()) sb.appendLine("None logged.")
                        else topAppsList.take(15).forEach { sb.appendLine("- ${it.title}: ${it.timeFormatted}") }
                        
                        sb.appendLine("\n[Totals]")
                        sb.appendLine("Screen Time: ${TimeUtils.formatDuration(screenTime)}")
                        sb.appendLine("Offline Activity: ${TimeUtils.formatDuration(clampedOffline)}")
                        sb.appendLine("Total Tracked: ${TimeUtils.formatDuration(clampedCombined)}")
                        
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("HAT Summary", sb.toString())
                        clipboard.setPrimaryClip(clip)
                        
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Summary copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        HatLogger.logError("SettingsScreen", "Failed to export summary", e)
                    }
                }
            }
        )
    }

    if (customDialogSetting != null) {
        var input by remember { mutableStateOf("") }
        // Previously this was `input.toIntOrNull() ?: 0`, so clearing the field
        // and tapping Save silently wrote 0 into the setting. For the archive
        // interval that is not merely a no-op, it is a badly broken value; for
        // the others it disabled the feature without the user ever being told.
        val parsedValue = input.toIntOrNull()
        // The detail minimum is capped at one day (1440 min): no
        // HAT range is longer than a day, so a bigger minimum would guarantee
        // an empty app (no gaps worth showing, or no question cards at all).
        // The limit is stated in the error message, never silently clamped.
        val maxAllowed = when (customDialogSetting) { "archive" -> 9999; "merge" -> 60; else -> DetailLevel.CUSTOM_MAX_MINUTES }
        val valueError = when {
            input.isBlank() -> "Enter a value"
            parsedValue == null -> "Digits only"
            parsedValue <= 0 -> "Must be at least 1"
            parsedValue > maxAllowed -> "Must be $maxAllowed or less"
            else -> null
        }
        HatOutlinedDialog(onDismissRequest = { customDialogSetting = null }, title = "Custom Value") {
            OutlinedTextField(
                value = input, onValueChange = { if (it.all { char -> char.isDigit() }) input = it },
                label = { Text(if (customDialogSetting == "archive") "Hours" else "Minutes") },
                isError = valueError != null,
                supportingText = { if (valueError != null) Text(valueError, color = MaterialTheme.colorScheme.error) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { customDialogSetting = null }) { Text("Cancel", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(modifier = Modifier.width(16.dp))
                TextButton(enabled = valueError == null, onClick = {
                    val value = parsedValue ?: return@TextButton
                    when (customDialogSetting) {
                        "archive" -> updateSettings(currentSettings!!.copy(archiveSyncIntervalHours = value))
                        "detail" -> updateSettings(currentSettings!!.copy(detailLevel = DetailLevel.CUSTOM, customDetailMinutes = value))
                        "merge" -> updateSettings(currentSettings!!.copy(sessionMergeMinutes = value))
                    }
                    customDialogSetting = null
                }) { Text("Save", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }

    if (showSupportPrompt) {
        SupportPromptDialog(onDismissed = { showSupportPrompt = false })
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0,0,0,0)) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()).windowInsetsPadding(WindowInsets.statusBars).verticalScroll(scrollState)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(modifier = Modifier.tutorialTarget("settingsScreen", tourRegistry)) {
                HatDynamicHeader(title = "Settings", subtitle = "App Configuration")
                }

                // GROUP 1: THEME ENGINE
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("THEME ENGINE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
                    SettingsGroupParentCard {
                        ThemePreviewBox(MaterialTheme.colorScheme.primary)
                        ThemeOptionItem("Dynamic Material", "Extracts colors from system wallpaper.", currentSettings!!.themeType == ThemeType.DYNAMIC) { updateSettings(currentSettings!!.copy(themeType = ThemeType.DYNAMIC)) }
                        ThemeOptionItem("Custom Material", "Pick any accent color you like.", currentSettings!!.themeType == ThemeType.STATIC) {
                            val alreadySeen = currentSettings!!.hasSeenSupportPrompt
                            val shouldPrompt = shouldShowSupportPrompt(alreadySeen, reachedThemeControl = true)
                            if (shouldPrompt) showSupportPrompt = true
                            // The "seen" flag is persisted in the SAME write as the theme change.
                            // Writing it from the dialog's dismiss handler instead would race the
                            // theme write and can silently revert the user's choice.
                            updateSettings(currentSettings!!.copy(themeType = ThemeType.STATIC, hasSeenSupportPrompt = alreadySeen || shouldPrompt))
                        }

                        AnimatedVisibility(
                            visible = currentSettings!!.themeType == ThemeType.STATIC,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            HexColorInput(Color(currentSettings!!.staticColor)) { newColor ->
                                updateSettings(currentSettings!!.copy(staticColor = argbToStoredLong(newColor.toArgb())))
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))

                // GROUP 2: DATA CONTROL
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DATA CONTROL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
                    SettingsItem("Daily Summary", "Copy a text summary of your timeline to the clipboard.") { showExportDatePicker = true }
                    SettingsItem("Hidden Apps", "Show or hide apps from your timeline.") { navController.navigate("hidden_apps") }
                    SettingsItem("Battery Optimization", "Keep the archive running by exempting HAT from battery optimization. Tap this if the onboarding battery exemption button does not work.") {
                        try { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    }
                    SettingsItem("Backup Master Vault", "Export full timeline, app history, and settings.") { val date = TimeUtils.stamp(); exportLauncher.launch("HAT_Vault_$date.zip") }
                    SettingsItem("Restore Master Vault", "Import a previously saved master vault.") { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                    HatSlidingSelector<Int>("Automatic Backup", "Re-writes a tamper-checked backup of everything to the folder below on a schedule. Same file, always overwritten.", listOf("Off" to 0, "12h" to 12, "24h" to 24), currentSettings!!.autoBackupIntervalHours, "", { hours ->
                        updateSettings(currentSettings!!.copy(autoBackupIntervalHours = hours))
                        if (hours > 0 && currentSettings!!.autoBackupUri.isBlank()) {
                            Toast.makeText(context, "Pick a backup folder next.", Toast.LENGTH_LONG).show()
                            backupFolderLauncher.launch(null)
                        }
                        andromedvn.heuristic.activity.tracker.worker.WorkerScheduler.updateWorkers(context, currentSettings!!.archiveSyncIntervalHours, hours)
                    }, null)
                    SettingsItem(
                        if (currentSettings!!.autoBackupUri.isBlank()) "Backup Folder" else "Backup Folder: set",
                        if (currentSettings!!.autoBackupUri.isBlank()) "Choose where the automatic backup file lives. It can be a cloud-synced folder."
                        else "HAT_AutoBackup.zip is overwritten in this folder on schedule. Tap to change it."
                    ) { backupFolderLauncher.launch(null) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))

                // GROUP 3: HEURISTIC ENGINE
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("HEURISTIC ENGINE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
                    // Selector convention (the owner's, 2026-10-23): the default
                    // is always the leftmost option, on every dial. Recent is
                    // the stored sort default, 12h is the sync default, 1 min
                    // is the fresh gap default, 15 min is the fresh idle
                    // default.
                    HatSlidingSelector<SortType>("Global Timeline Sort", "Determines the order of apps and activities\non the main dashboard cards.", listOf("Recent" to SortType.RECENT, "Duration" to SortType.DURATION, "Chrono" to SortType.CHRONOLOGICAL), localSortType, "", { localSortType = it; updateSettings(currentSettings!!.copy(sortType = it)) }, null)
                    HatSlidingSelector<Int>("Gap Minimum", "Gaps shorter than what is set to this setting don't appear to declutter the timeline. A gap is time where you weren't on your phone.", listOf("1 min" to DetailLevel.ONE_MIN, "5 min" to DetailLevel.FIVE_MIN, "15 min" to DetailLevel.FIFTEEN_MIN, "Custom" to -1), if (currentSettings!!.detailLevel == DetailLevel.CUSTOM) currentSettings!!.customDetailMinutes else currentSettings!!.detailLevel, " min", { updateSettings(currentSettings!!.copy(detailLevel = it)) }, { customDialogSetting = "detail" })
                    HatSlidingSelector<Int>("Session Merge", "Visits to the same app separated by less than what is set to this setting become one session on the details screen.", DetailLevel.SESSION_MERGE_PRESETS + ("Custom" to -1), currentSettings!!.sessionMergeMinutes, " min", { updateSettings(currentSettings!!.copy(sessionMergeMinutes = it)) }, { customDialogSetting = "merge" })
                    HatSlidingSelector<Int>("Background History Sync", "Wakes up periodically to copy your usage history before Android deletes it. There is no Off: Android deletes old records, the archive keeps them.", listOf("12h" to 12, "6h" to 6, "2h" to 2, "Custom" to -1), currentSettings!!.archiveSyncIntervalHours, "h", { updateSettings(currentSettings!!.copy(archiveSyncIntervalHours = it)) }, { customDialogSetting = "archive" })
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))

                // GROUP 4: SUPPORT (donations handled entirely on an external page)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SUPPORT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
                    SettingsItem("Support Development", "HAT is free and has no ads or subscriptions. Support keeps one developer building full-time.") { context.openExternalWebLink(SPONSOR_URL) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))

                // GROUP 5: ABOUT & LEGAL
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ABOUT & LEGAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
                    SettingsItem("Privacy Policy", "How HAT processes your usage data locally.") { context.openExternalWebLink("https://andromedvn.github.io/HAT/PRIVACY.html") }
                    SettingsItem("Terms of Service", "Conditions of use and liability limitations.") { context.openExternalWebLink("https://andromedvn.github.io/HAT/TERMS.html") }
                    SettingsItem("Open Source Licenses", "Third-party software attributions.") { navController.navigate("licenses") }
                    SettingsItem("How HAT Works", "Shows the onboarding screens again.") { navController.navigate("permissions?review=true") }
                    SettingsItem("Tutorial", "Replay the tour guide.") { navController.navigate("dashboard?startTour=true") }
                    
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                        Text("HAT Engine v$appVersion", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
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
