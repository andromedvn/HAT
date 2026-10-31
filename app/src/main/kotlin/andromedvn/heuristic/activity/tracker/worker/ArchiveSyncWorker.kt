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
     * [ActivityRepository.syncArchive] processes at most [DAYS_PER_PASS] days
     * and then returns; calling it repeatedly resumes where the previous call
     * stopped. A single WorkManager run therefore used to archive 5 days, and
     * a user returning after a year needed 365/5 = 73 separate scheduled runs
     * - at a default multi-hour interval, weeks of history that is queryable
     * but not yet archived.
     *
     * ## Progress is measured by the ATTEMPTED-day cursor, not the watermark
     *
     * The watermark is `MAX(start_millis)` over the archive table, so it only
     * advances on a day that produced rows. A day with no usage inserts
     * nothing - and before 2026-10-28 the walk restarted from the watermark,
     * so five or more consecutive empty days (a dead-battery weekend) made
     * every pass re-query the same days forever while the days after the hole
     * aged out of Android's retention window. [ArchiveCursor] now records the
     * last ATTEMPTED local midnight in settings, and it advances on empty
     * days too: the walk can never wedge. Progress (and the caught-up check)
     * therefore read the cursor, not the watermark.
     *
     * The loop stops on *either*:
     *
     *  - the cursor reaching the start of today (caught up), or
     *  - a pass making no cursor progress - nothing left to archive, or
     *    another thread holds the archiver lock, or
     *  - [MAX_CATCH_UP_PASSES], the hard ceiling.
     *
     * The ceiling alone covers the intended backfill: 365 days at
     * [DAYS_PER_PASS] days per pass is exactly 73 passes, so a first run after
     * a year-long gap completes in one execution instead of 73.
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
