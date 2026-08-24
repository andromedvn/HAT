package andromedvn.heuristic.activity.tracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import andromedvn.heuristic.activity.tracker.data.ActivityRepository
import andromedvn.heuristic.activity.tracker.data.OfflineStorage
import andromedvn.heuristic.activity.tracker.utils.HatLogger

class ArchiveSyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        try {
            HatLogger.logBackgroundEvent("ArchiveWorker", "Starting silent background sync execution")
            val storage = OfflineStorage.getInstance(applicationContext)
            val repository = ActivityRepository(applicationContext, storage)
            repository.syncArchive()
            HatLogger.logBackgroundEvent("ArchiveWorker", "Execution successful. Data safely archived.")
            return Result.success()
        } catch (e: Exception) {
            HatLogger.logBackgroundEvent("ArchiveWorker", "Execution failed: ${e.message}")
            return Result.retry()
        }
    }
}
