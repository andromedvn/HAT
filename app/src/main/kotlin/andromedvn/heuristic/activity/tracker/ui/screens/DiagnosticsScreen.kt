package andromedvn.heuristic.activity.tracker.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import andromedvn.heuristic.activity.tracker.data.ActivityRepository
import andromedvn.heuristic.activity.tracker.ui.components.HatDynamicHeader
import andromedvn.heuristic.activity.tracker.ui.components.hatSwitchColors
import andromedvn.heuristic.activity.tracker.ui.components.verticalFadingEdges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticToggleItem(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    var internalChecked by remember(checked) { mutableStateOf(checked) }
    Card(modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).clickable { internalChecked = !internalChecked; onCheckedChange(internalChecked) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) { Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(checked = internalChecked, onCheckedChange = { internalChecked = it; onCheckedChange(it) }, colors = hatSwitchColors())
        }
    }
}

@Composable
fun DiagnosticSettingsItem(title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column { Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

@Composable
fun DiagnosticsScreen(navController: NavController, repository: ActivityRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val currentSettings by repository.settings.collectAsState(initial = null)

    val diagMatrixLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { repository.exportCalculatedMatrix(it) }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Total Unbounded Matrix Exported", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Matrix Export Failed", Toast.LENGTH_LONG).show() } }
            }
        }
    }

    val crashLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { repository.exportCrashLogs(it) }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Crash Logs Exported", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Export Failed", Toast.LENGTH_SHORT).show() } }
            }
        }
    }
    
    val diagEventLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { repository.exportDiagnosticLogs(it) }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Diagnostic Events Exported", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Export Failed", Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0,0,0,0)) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 16.dp)) {
                HatDynamicHeader(title = "Diagnostics", subtitle = "Developer Mode") {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            
            Box(modifier = Modifier.weight(1f).verticalFadingEdges(true, true)) {
                LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                    
                    if (currentSettings != null) {
                        item {
                            DiagnosticToggleItem(
                                title = "Bypass History Limit", 
                                subtitle = "Allows navigation past device memory limits. Missing history will render as blank gaps.", 
                                checked = currentSettings!!.bypassHistoryLimit
                            ) { state -> 
                                scope.launch { repository.storage.saveSettings(currentSettings!!.copy(bypassHistoryLimit = state)) }
                            }
                        }
                    }
                    
                    item {
                        DiagnosticSettingsItem("Export Total Unbounded Matrix", "Exports every pair of <start, end> coordinates retained over the system's lifetime for all apps and events. JSON encoded array dump.") { 
                            val date = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault()).format(Date())
                            diagMatrixLauncher.launch("HAT_Calculated_Matrix_$date.json") 
                        }
                    }
                    item {
                        DiagnosticSettingsItem("Export Diagnostic Events", "Export non-fatal logic errors (Vault merges, read/write anomalies).") {
                            val date = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault()).format(Date())
                            diagEventLauncher.launch("HAT_Diagnostics_$date.txt")
                        }
                    }
                    item {
                        DiagnosticSettingsItem("Export Crash Logs", "Export stack traces and breadcrumbs of fatal runtime crashes.") {
                            val date = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault()).format(Date())
                            crashLogLauncher.launch("HAT_Crash_Logs_$date.txt")
                        }
                    }
                    item {
                        DiagnosticSettingsItem("Clear Diagnostic Logs", "Delete all stored crash and diagnostic logs to free up space.") {
                            scope.launch {
                                repository.clearCrashLogs()
                                Toast.makeText(context, "Logs Cleared", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    item {
                        DiagnosticSettingsItem("Simulate Fatal Crash", "Trigger a deliberate runtime exception to natively test the disaster recovery hatch.") {
                            throw RuntimeException("Diagnostic Test Crash initiated by user.")
                        }
                    }
                }
            }
        }
    }
}
