package andromedvn.heuristic.activity.tracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import andromedvn.heuristic.activity.tracker.data.ActivityRepository
import andromedvn.heuristic.activity.tracker.data.OfflineStorage
import andromedvn.heuristic.activity.tracker.utils.HatLogger
import kotlinx.coroutines.flow.first

/**
 * Rolling Vault (r12p): re-exports the encrypted vault into the user-picked
 * SAF folder on a schedule. Silent by charter: no notification channel, no
 * permission ask. A failed run retries on the next periodic slot; a missing
 * folder (deleted or revoked) logs and reports failure without crashing.
 */
class AutoBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val repository = ActivityRepository(applicationContext, OfflineStorage.getInstance(applicationContext))
            val uri = repository.settings.first().autoBackupUri
            if (uri.isBlank()) return Result.success()
            val ok = repository.performAutoBackup(uri)
            if (ok) {
                HatLogger.logBackgroundEvent("VaultWorker", "Rolling Vault written.")
                Result.success()
            } else Result.retry()
        } catch (e: Exception) {
            HatLogger.logBackgroundEvent("VaultWorker", "Rolling Vault failed: ${e.message}")
            Result.retry()
        }
    }
}
