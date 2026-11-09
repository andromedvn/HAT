package andromedvn.heuristic.activity.tracker.utils

import android.content.Context
import android.content.Intent
import andromedvn.heuristic.activity.tracker.data.ActivityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The vault restore + safe-restart flow, one copy (was two: Settings and the
 * onboarding screen, with already-drifted failure wording). mergeDatabase only
 * ever INSERTs, so a failed restore never deleted anything; the failure toast
 * must say incomplete, not tampered.
 */
suspend fun restoreVaultAndRestart(context: Context, repository: ActivityRepository, uri: android.net.Uri) {
    val report = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { repository.restoreSecureVault(it, context.cacheDir) }
        } catch (e: Exception) {
            HatLogger.logError("VaultRestore", "restore failed", e)
            null
        }
    }
    withContext(Dispatchers.Main) {
        if (report != null && report.success) {
            android.widget.Toast.makeText(context, "Vault Restored. ${report.offlineMerged} label(s) merged, ${report.offlineSkipped} skipped as overlapping existing ones. Restarting safely...", android.widget.Toast.LENGTH_LONG).show()
            delay(1000)
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val componentName = intent?.component
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        } else {
            // mergeDatabase only ever INSERTs (CONFLICT_IGNORE),
            // so nothing existing was deleted - but some tables may
            // have merged before the failure. Do not claim the file
            // was tampered with: an incomplete merge is a different
            // failure and the user needs to know which they hit.
            val counts = report?.let { " (${it.offlineMerged} label(s) merged, ${it.offlineSkipped} skipped)" } ?: ""
            android.widget.Toast.makeText(context, "Restore incomplete: some data could not be restored. Nothing was deleted, but the vault was not fully applied.$counts", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
