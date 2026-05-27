package andromedvn.heuristic.activity.tracker.worker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object WorkerScheduler {
    private const val ARCHIVE_WORK_NAME = "HatArchiveSyncWork"

    fun updateWorkers(context: Context, archiveHours: Int) {
        val workManager = WorkManager.getInstance(context)

        // Deploy or cancel Archiver
        if (archiveHours > 0) {
            val archiveRequest = PeriodicWorkRequestBuilder<ArchiveSyncWorker>(archiveHours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            workManager.enqueueUniquePeriodicWork(ARCHIVE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, archiveRequest)
        } else {
            workManager.cancelUniqueWork(ARCHIVE_WORK_NAME)
        }
    }
}
