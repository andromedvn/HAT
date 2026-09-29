package andromedvn.heuristic.activity.tracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import andromedvn.heuristic.activity.tracker.data.ActivityRepository
import andromedvn.heuristic.activity.tracker.data.OfflineStorage
import andromedvn.heuristic.activity.tracker.utils.HatLogger
import kotlinx.coroutines.yield
import java.util.Calendar

class ArchiveSyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        try {
            HatLogger.logBackgroundEvent("ArchiveWorker", "Starting silent background sync execution")
            val storage = OfflineStorage.getInstance(applicationContext)
            val repository = ActivityRepository(applicationContext, storage)
            runCatchUp(repository, storage)
            HatLogger.logBackgroundEvent("ArchiveWorker", "Execution successful. Data safely archived.")
            return Result.success()
        } catch (e: Exception) {
            HatLogger.logBackgroundEvent("ArchiveWorker", "Execution failed: ${e.message}")
            return Result.retry()
        }
    }

    /**
     * Drives the archive to the present in one run instead of 5 days per run.
     *
     * ## Why a loop is needed here and not in the repository
     *
     * [ActivityRepository.syncArchive] processes at most [DAYS_PER_PASS] days
     * and then returns; it re-reads the archive watermark on every call, so
     * calling it repeatedly resumes exactly where the previous call stopped.
     * A single WorkManager run therefore used to archive 5 days, and a user
     * returning after a year needed 365/5 = 73 separate scheduled runs — at a
     * default multi-hour interval, weeks of history that is queryable but not
     * yet archived.
     *
     * The loop lives in the worker rather than in the repository because the
     * repository is a shared, re-entrancy-guarded component that is also
     * driven from the foreground (see
     * `DashboardViewModel.triggerBackgroundArchiveSync`); raising its internal
     * batch would lengthen a foreground call the user is waiting on. Here the
     * work is already off the UI thread and cancellable.
     *
     * ## Bounded, and why a bound is required rather than merely prudent
     *
     * The watermark is `MAX(start_millis)` over the archive table, so it only
     * advances on a day that actually produced spans. A day with no usage
     * inserts nothing, the watermark stays put, and the next pass re-queries
     * the same days forever. The loop therefore stops on *either*:
     *
     *  - the watermark reaching the start of today (caught up), or
     *  - a pass making no progress (watermark unchanged) — nothing left to
     *    archive, or another thread holds the archiver lock, or
     *  - [MAX_CATCH_UP_PASSES], the hard ceiling.
     *
     * The ceiling alone covers the intended backfill: 365 days at
     * [DAYS_PER_PASS] days per pass is exactly 73 passes, so a first run after
     * a year-long gap completes in one execution instead of 73.
     *
     * Behaviour otherwise unchanged: each pass is the same call, writing the
     * same rows with the same conflict-ignore, just resumed further. `yield()`
     * between passes keeps the coroutine cancellable and stops a long backfill
     * from monopolising the dispatcher thread.
     */
    private suspend fun runCatchUp(repository: ActivityRepository, storage: OfflineStorage) {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        var previousWatermark = storage.getLatestArchivedDayMillis()
        var passes = 0
        while (passes < MAX_CATCH_UP_PASSES) {
            repository.syncArchive()
            passes++

            val watermark = storage.getLatestArchivedDayMillis()
            if (watermark == previousWatermark) {
                // No day advanced. Either there is genuinely nothing to
                // archive, or syncArchive returned early because the
                // foreground already held the archiver lock. Either way,
                // looping again would re-query identical days.
                HatLogger.logBackgroundEvent("ArchiveWorker", "Archive caught up after $passes pass(es); no further progress available")
                return
            }
            previousWatermark = watermark

            if (watermark != null && watermark + 86400000L >= startOfToday) {
                HatLogger.logBackgroundEvent("ArchiveWorker", "Archive caught up to today after $passes pass(es)")
                return
            }

            yield()
        }
        HatLogger.logBackgroundEvent("ArchiveWorker", "Archive backfill hit the $MAX_CATCH_UP_PASSES-pass ceiling; remaining days continue next run")
    }

    companion object {
        /** Days [ActivityRepository.syncArchive] handles per call. */
        private const val DAYS_PER_PASS = 5

        /** History the first run after a long gap aims to backfill. */
        private const val CATCH_UP_TARGET_DAYS = 365

        /**
         * Hard ceiling on passes: exactly enough for [CATCH_UP_TARGET_DAYS] at
         * [DAYS_PER_PASS] days per pass. Never exceeded, so a first run after a
         * long gap cannot run unbounded; anything beyond resumes next run.
         */
        private const val MAX_CATCH_UP_PASSES = CATCH_UP_TARGET_DAYS / DAYS_PER_PASS
    }
}
