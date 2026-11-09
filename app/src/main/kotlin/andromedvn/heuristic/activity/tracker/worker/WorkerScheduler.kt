package andromedvn.heuristic.activity.tracker.worker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object WorkerScheduler {
    private const val ARCHIVE_WORK_NAME = "HatArchiveSyncWork"
    private const val BACKUP_WORK_NAME = "HatRollingVaultWork"

    /**
     * Always pass BOTH intervals explicitly: omitting one used to mean
     * "cancel that worker" (a general settings change silently cancelled the
     * Rolling Vault schedule until MainActivity re-asserted it on next resume).
     */
    fun updateWorkers(context: Context, archiveHours: Int, backupHours: Int) {
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

        // Rolling Vault (r12p): deploy or cancel the scheduled vault writer.
        if (backupHours > 0) {
            val backupRequest = PeriodicWorkRequestBuilder<AutoBackupWorker>(backupHours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            workManager.enqueueUniquePeriodicWork(BACKUP_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, backupRequest)
        } else {
            workManager.cancelUniqueWork(BACKUP_WORK_NAME)
        }
    }
}
