package andromedvn.heuristic.activity.tracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import andromedvn.heuristic.activity.tracker.data.ActivityRepository
import andromedvn.heuristic.activity.tracker.data.OfflineStorage
import andromedvn.heuristic.activity.tracker.domain.ArchiveCursor
import andromedvn.heuristic.activity.tracker.utils.HatLogger
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
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
     * [ActivityRepository.syncArchive] processes at most [ArchiveCursor.DEFAULT_PASS_DAYS] days
     * and then returns; calling it repeatedly resumes where the previous call
     * stopped. A single WorkManager run therefore used to archive 5 days, and
     * a user returning after a year needed 365/5 = 73 separate scheduled runs
     * - at a default multi-hour interval, weeks of history that is queryable
     * but not yet archived.
     *
     * ## Progress is measured by the ATTEMPTED-day cursor, not the watermark
     *
     * A day with no usage inserts nothing, so a watermark-based check would
     * re-run passes forever after empty days. Progress (and the caught-up
     * check) read the cursor; the full story is [ArchiveCursor]'s doc.
     *
     * The loop stops on *either*:
     *
     *  - the cursor reaching the start of today (caught up), or
     *  - a pass making no cursor progress - nothing left to archive, or
     *    another thread holds the archiver lock, or
     *  - [MAX_CATCH_UP_PASSES], the hard ceiling.
     *
     * The ceiling alone covers the intended backfill: 365 days at
     * [ArchiveCursor.DEFAULT_PASS_DAYS] days per pass is exactly 73 passes, so a
     * first run after a year-long gap completes in one execution instead of 73.
     */
    private suspend fun runCatchUp(repository: ActivityRepository, storage: OfflineStorage) {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        var previousCursor = storage.settings.first().archiveCursorMillis
        var passes = 0
        while (passes < MAX_CATCH_UP_PASSES) {
            repository.syncArchive()
            passes++

            val cursor = storage.settings.first().archiveCursorMillis
            if (cursor == previousCursor) {
                // No day advanced. Either there is genuinely nothing to
                // archive, or syncArchive returned early because the
                // foreground already held the archiver lock. Either way,
                // looping again would re-query identical days.
                HatLogger.logBackgroundEvent("ArchiveWorker", "Archive caught up after $passes pass(es); no further progress available")
                return
            }
            previousCursor = cursor

            if (ArchiveCursor.isCaughtUp(cursor, startOfToday)) {
                HatLogger.logBackgroundEvent("ArchiveWorker", "Archive caught up to today after $passes pass(es)")
                return
            }

            yield()
        }
        HatLogger.logBackgroundEvent("ArchiveWorker", "Archive backfill hit the $MAX_CATCH_UP_PASSES-pass ceiling; remaining days continue next run")
    }

    companion object {
        // The pass length lives in ArchiveCursor.DEFAULT_PASS_DAYS (one home);
        // syncArchive() uses the domain default, so this file no longer restates it.

        /** History the first run after a long gap aims to backfill. */
        private const val CATCH_UP_TARGET_DAYS = 365

        /**
         * Hard ceiling on passes: exactly enough for [CATCH_UP_TARGET_DAYS] at
         * [ArchiveCursor.DEFAULT_PASS_DAYS] days per pass. Never exceeded, so a
         * first run after a long gap cannot run unbounded; anything beyond
         * resumes next run.
         */
        private const val MAX_CATCH_UP_PASSES = CATCH_UP_TARGET_DAYS / ArchiveCursor.DEFAULT_PASS_DAYS
    }
}
