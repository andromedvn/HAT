package andromedvn.heuristic.activity.tracker

import andromedvn.heuristic.activity.tracker.utils.HatLogger
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import andromedvn.heuristic.activity.tracker.ui.theme.ComposeEmptyActivityTheme
import andromedvn.heuristic.activity.tracker.data.UserSettings
import kotlinx.coroutines.launch
import java.io.File

class CrashRecoveryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val logFileName = intent.getStringExtra("LOG_FILE") ?: ""

        setContent {
            val scope = rememberCoroutineScope()
            var confirmReset by remember { mutableStateOf(false) }
            // We use a completely static, isolated theme so the UI engine doesn't crash trying to read corrupted DataStore settings
            ComposeEmptyActivityTheme(settings = UserSettings(staticColor = 0xFFD32F2F)) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(modifier = Modifier.size(100.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(32.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Warning, contentDescription = "Critical Error", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(50.dp))
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            text = "HAT Engine Fault",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "The application encountered a critical runtime exception and was forced to halt to prevent data corruption.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(48.dp))

                        if (logFileName.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().height(56.dp).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        try {
                                            val logFile = java.io.File(java.io.File(filesDir, HatLogger.CRASH_LOGS_DIR), logFileName)
                                            if (logFile.exists()) {
                                                val uri = FileProvider.getUriForFile(this@CrashRecoveryActivity, "${packageName}.provider", logFile)
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                startActivity(Intent.createChooser(shareIntent, "Share Diagnostic Log"))
                                            } else {
                                                Toast.makeText(this@CrashRecoveryActivity, "Log file missing.", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(this@CrashRecoveryActivity, "Failed to share log.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Share Diagnostic Log", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth().height(56.dp).border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    // Charter: irreversible actions never run on one tap.
                                    confirmReset = true
                                },
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Factory Reset Engine", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                if (confirmReset) {
                    AlertDialog(
                        onDismissRequest = { confirmReset = false },
                        title = { Text("Factory Reset Engine?") },
                        text = { Text("This erases everything HAT stored on this device: the database, your labels and settings, and all backups. It cannot be undone.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmReset = false
                                scope.launch {
                                    try {
                                        // Physical reset: bypass DataStore/SQLite object instantiation to avoid inter-process deadlocks.
                                        val dbFile = getDatabasePath("hat_heuristic.db")
                                        dbFile.delete()
                                        File(dbFile.path + "-wal").delete()
                                        File(dbFile.path + "-shm").delete()
                                        File(filesDir, "datastore/hat_secure_prefs.preferences_pb").delete()
                                        File(filesDir, HatLogger.CRASH_LOGS_DIR).deleteRecursively()
                                        File(filesDir, HatLogger.DIAGNOSTICS_DIR).deleteRecursively()
                                        File(filesDir, "offline_chunks").deleteRecursively()
                                        File(filesDir, "app_archive_chunks").deleteRecursively()
                                        Toast.makeText(this@CrashRecoveryActivity, "Engine Reset Complete.", Toast.LENGTH_SHORT).show()
                                        val intent = packageManager.getLaunchIntentForPackage(packageName)
                                        val restartIntent = Intent.makeRestartActivityTask(intent?.component)
                                        startActivity(restartIntent)
                                        Runtime.getRuntime().exit(0)
                                    } catch (e: Exception) {
                                        Toast.makeText(this@CrashRecoveryActivity, "Reset Failed.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) { Text("Erase Everything", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
                        }
                    )
                }
            }
        }
    }
}
