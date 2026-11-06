package andromedvn.heuristic.activity.tracker.data

import android.content.Context
import android.util.LruCache
import andromedvn.heuristic.activity.tracker.domain.ArchiveCursor
import andromedvn.heuristic.activity.tracker.domain.EventCode
import andromedvn.heuristic.activity.tracker.domain.circularHourDistance
import andromedvn.heuristic.activity.tracker.domain.hourProximityWeight
import andromedvn.heuristic.activity.tracker.domain.nextLocalMidnight
import andromedvn.heuristic.activity.tracker.utils.UsageStatsEngine
import andromedvn.heuristic.activity.tracker.utils.VaultSecurity
import andromedvn.heuristic.activity.tracker.utils.HatLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ActivityRepository(private val context: Context, val storage: OfflineStorage) {
    val settings: Flow<UserSettings> = storage.settings
    private val engine = UsageStatsEngine(context)
    
    private val appNameCache = LruCache<String, String>(500)

    // ProcessCategory (r12p, ported from HAT Pro): one lazy PackageManager
    // query classifies every package as SELF/LAUNCHER/SYSTEM_UI/STANDARD and
    // memoizes it, so thousands of interval lookups never hit IPC again.
    // Infrastructure only: no caller changes behavior until the gap-explainer
    // wording work lands.
    private val categoryCache = LruCache<String, ProcessCategory>(500)
    @Volatile private var launcherPackages: Set<String>? = null
    
    private val cacheMutex = Mutex()
    private val oldestDataMutex = Mutex()

    @Volatile private var cachedIntervals: Map<String, List<Pair<Long, Long>>> = emptyMap()
    @Volatile private var cacheKey: Pair<Long, Long> = Pair(0L, 0L)
    @Volatile private var lastCacheTime: Long = 0L
    @Volatile private var oldestSystemDataCache: Long = -1L

    private val isArchiverRunning = AtomicBoolean(false)

    fun forceInvalidateCache() { lastCacheTime = 0L; oldestSystemDataCache = -1L }

    suspend fun getOldestSystemData(): Long = withContext(Dispatchers.IO) {
        if (oldestSystemDataCache > 0 && (System.currentTimeMillis() - lastCacheTime < 3600000L)) return@withContext oldestSystemDataCache
        
        oldestDataMutex.withLock {
            if (oldestSystemDataCache > 0 && (System.currentTimeMillis() - lastCacheTime < 3600000L)) return@withContext oldestSystemDataCache
            val oldest = engine.getOldestEventTimestamp()
            oldestSystemDataCache = oldest
            oldest
        }
    }

    suspend fun syncArchive() = withContext(Dispatchers.IO) {
        if (!isArchiverRunning.compareAndSet(false, true)) return@withContext
        HatLogger.log("ActivityRepository: Starting Archive Sync (Micro-Batching)")
        try {
            // Raw event persistence (schema v4, 2026-11-02): the interval
            // archive is structurally blind to screen, keyguard, touch and
            // foreground-service events. Persisting the drained stream on
            // every sync means every day from now on carries full presence
            // evidence. The 48h overlap makes the write idempotent.
            try {
                val now = System.currentTimeMillis()
                val raw = engine.collectRawEventsForExport(now - 48L * 3600_000L, now)
                storage.persistRawEvents(raw)
                storage.pruneRawEvents(now - 180L * 86_400_000L)
                HatLogger.log("ActivityRepository: raw events persisted (${raw.size} rows in window)")
            } catch (e: Exception) {
                HatLogger.logError("ActivityRepository", "raw event persistence failed; continuing with interval sync", e)
            }
            val startOfToday = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val minSystemData = getOldestSystemData()
            val startOfMinSystemData = Calendar.getInstance().apply { timeInMillis = minSystemData; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val lastArchived = storage.getLatestArchivedDayMillis()
            // The cursor (2026-10-28): the last local midnight ATTEMPTED, empty
            // days included. The old walk restarted from the watermark
            // (MAX(start_millis)), and because an empty day inserts no rows,
            // five or more consecutive empty days re-queried the same days
            // forever - everything after the hole stayed unarchived until
            // Android's retention window deleted it. The cursor advances on
            // empty days too, so the walk can never wedge. Installs predating
            // the field (cursor 0) fall back to the watermark-based start.
            val savedCursor = storage.settings.first().archiveCursorMillis
            val cursorStart = if (savedCursor > 0) savedCursor
                else (lastArchived?.let { nextLocalMidnight(it) } ?: startOfMinSystemData)
            // Calendar day, not `+ 86_400_000L`: a fixed 24-hour seed lands at
            // 01:00 local the day after a spring-forward and at 23:00 the
            // evening after a fall-back, and every window after the boundary
            // inherits the wrong anchor. `lastArchived` is already normalised
            // to a local midnight by getLatestArchivedDayMillis, so the next
            // calendar midnight is exactly the next day to archive.
            var currentDay = maxOf(cursorStart, startOfMinSystemData)
            val db = storage.dbHelper.writableDatabase

            val days = ArchiveCursor.passDays(currentDay, startOfToday)
            for (day in days) {
                yield()
                currentDay = day
                if (day >= startOfMinSystemData) {
                    // End the window at the NEXT local midnight, not
                    // `currentDay + 86_400_000L`: on a 25-hour fall-back day
                    // the fixed end cut off the local 23:00-24:00 hour, and
                    // that hour was never archived by any later pass either -
                    // the next window started at the next midnight. A day is
                    // however long the calendar says it is.
                    val dayEnd = nextLocalMidnight(day)
                    val raw = engine.getUsageIntervals(day, dayEnd, emptySet())
                    val computedSpans = mutableListOf<Triple<String, Long, Long>>()
                    for ((pkg, intervals) in raw) {
                        for ((s, e) in engine.mergeIntervals(intervals)) computedSpans.add(Triple(pkg, s, e))
                    }
                    db.beginTransaction()
                    try {
                        for (span in computedSpans) storage.insertArchivedSpanInternal(db, span.first, span.second, span.third)
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
            }
            // Persist the cursor as the NEXT day to attempt, even when the
            // attempted days produced no rows: a day with zero usage must
            // still advance the walk, or a hole of empty days wedges it at
            // the watermark forever.
            if (days.isNotEmpty()) {
                storage.saveSettings(storage.settings.first().copy(archiveCursorMillis = nextLocalMidnight(days.last())))
                HatLogger.log("ActivityRepository: Archive pass attempted ${days.size} day(s) through ${days.last()}")
            }
        } finally { isArchiverRunning.set(false) }
    }

    /**
     * The package's process category. SYSTEM_UI covers the Android SystemUi
     * and input-method packages; LAUNCHER anything answering CATEGORY_HOME;
     * SELF this app; everything else STANDARD.
     */
    fun getAppCategory(pkg: String): ProcessCategory {
        categoryCache.get(pkg)?.let { return it }
        val category = computeCategory(pkg)
        categoryCache.put(pkg, category)
        return category
    }

    fun isSystemApp(pkg: String): Boolean =
        getAppCategory(pkg) == ProcessCategory.SYSTEM_UI || getAppCategory(pkg) == ProcessCategory.LAUNCHER

    private fun computeCategory(pkg: String): ProcessCategory {
        if (pkg == context.packageName) return ProcessCategory.SELF
        if (pkg == "com.android.systemui" || pkg.startsWith("com.android.inputmethod")) {
            return ProcessCategory.SYSTEM_UI
        }
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
            val isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val launchers = launcherPackages ?: synchronized(this) {
                launcherPackages ?: run {
                    val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
                    val found = pm.queryIntentActivities(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                        .mapTo(mutableSetOf()) { it.activityInfo.packageName }
                    launcherPackages = found
                    found
                }
            }
            if (pkg in launchers) return ProcessCategory.LAUNCHER
            if (isSystem) return ProcessCategory.SYSTEM_UI
        } catch (_: Exception) { }
        return ProcessCategory.STANDARD
    }

    fun getAppName(pkg: String): String {
        val cached = appNameCache.get(pkg)
        if (cached != null) return cached
        val name = try { 
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
            pm.getApplicationLabel(info).toString() 
        } catch (e: Exception) { 
            val parts = pkg.split(".")
            if (parts.isNotEmpty()) parts.last().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } else pkg 
        }
        appNameCache.put(pkg, name)
        return name
    }

    suspend fun getOldestDataTimestamp(): Long = withContext(Dispatchers.IO) {
        val oldestOffline = storage.getOldestOfflineActivityTimestamp() ?: Long.MAX_VALUE
        val oldestArchive = storage.getOldestArchivedTimestamp() ?: Long.MAX_VALUE
        val oldestOS = getOldestSystemData()
        val installDate = storage.settings.first().installDateMillis
        minOf(oldestOffline, oldestArchive, oldestOS, installDate)
    }

    private fun clampIntervals(intervalsMap: Map<String, List<Pair<Long, Long>>>, start: Long, end: Long): Map<String, List<Pair<Long, Long>>> {
        val clamped = mutableMapOf<String, List<Pair<Long, Long>>>()
        for ((pkg, intervals) in intervalsMap) {
            val valid = intervals.mapNotNull { (s, e) -> val cS = maxOf(s, start); val cE = minOf(e, end); if (cE > cS) cS to cE else null }
            if (valid.isNotEmpty()) clamped[pkg] = valid
        }
        return clamped
    }

    suspend fun getAllIntervalsCached(start: Long, end: Long): Map<String, List<Pair<Long, Long>>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (start >= cacheKey.first && end <= cacheKey.second && (now - lastCacheTime) < 300000L) return@withContext clampIntervals(cachedIntervals, start, end)
        
        cacheMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (start >= cacheKey.first && end <= cacheKey.second && (lockedNow - lastCacheTime) < 300000L) return@withContext clampIntervals(cachedIntervals, start, end)
            
            val windowStart = start - (3L * 86400000L); val windowEnd = end + (1L * 86400000L)
            val fetchedIntervals = mutableMapOf<String, MutableList<Pair<Long, Long>>>()
            val oldestOS = getOldestSystemData()
            val latestArchived = storage.getLatestArchivedDayMillis() ?: oldestOS
            
            storage.getArchivedIntervals(windowStart - 86400000L, minOf(windowEnd, latestArchived)).forEach { (pkg, intervals) -> fetchedIntervals.getOrPut(pkg) { mutableListOf() }.addAll(intervals) }
            if (windowEnd > latestArchived) engine.getUsageIntervals(maxOf(windowStart - 86400000L, latestArchived), windowEnd, emptySet()).forEach { (pkg, intervals) -> fetchedIntervals.getOrPut(pkg) { mutableListOf() }.addAll(intervals) }

            for (pkg in fetchedIntervals.keys.toList()) {
                yield()
                val valid = engine.mergeIntervals(fetchedIntervals[pkg]!!).mapNotNull { (s, e) -> val cS = maxOf(s, windowStart); val cE = minOf(e, windowEnd); if (cE > cS) cS to cE else null }
                fetchedIntervals[pkg] = valid.toMutableList()
            }

            // Ignored-session subtraction removed (r9): idle detection is gone,
            // nothing writes new ignored rows, and stale rows from the idle era
            // manufactured phantom gaps by carving real usage out of the cache.
            // The table is cleared by the schema v5 migration.
            cachedIntervals = fetchedIntervals; cacheKey = Pair(windowStart, windowEnd); lastCacheTime = lockedNow
            clampIntervals(cachedIntervals, start, end)
        }
    }

    suspend fun getFilteredIntervals(start: Long, end: Long, hiddenPkgs: Set<String>): Map<String, List<Pair<Long, Long>>> = getAllIntervalsCached(start, end).filterKeys { it !in hiddenPkgs }
    fun getRawOfflineActivitiesBetween(start: Long, end: Long) = storage.getOfflineActivitiesBetween(start, end)
    fun getRawOfflineActivitiesByTitleBetween(title: String, start: Long, end: Long) = storage.getOfflineActivitiesByTitleBetween(title, start, end)
    fun getAcknowledgedGhostsBetween(start: Long, end: Long) = storage.getAcknowledgedGhostsBetween(start, end)
    
    suspend fun clearAllData() { storage.clearAllData() }
    
    /**
     * Rolling Vault (r12p): the scheduled backup. Same checkpoint-then-export
     * discipline as the manual vault (a busy WAL aborts rather than writing an
     * incomplete vault), but the target is the user-picked SAF folder and the
     * run is silent.
     */
    suspend fun performAutoBackup(uriString: String): Boolean = withContext(Dispatchers.IO) {
        yield()
        val stream = VaultSecurity.openAutoBackupStream(context, uriString)
        if (stream == null) {
            HatLogger.logError("Vault", "Rolling Vault aborted: the backup folder is unavailable.")
            return@withContext false
        }
        stream.use { exportSecureVault(it) }
    }

    suspend fun exportSecureVault(outputStream: OutputStream): Boolean = withContext(Dispatchers.IO) {
        // The checkpoint must fully flush the WAL before the file is copied, because
        // only hat_heuristic.db is archived - never -wal/-shm. A partial checkpoint
        // would emit a vault that looks fine but is missing recent writes (or cannot
        // be opened at all). Better to fail loudly than to hand back a bad backup.
        if (!storage.checkpointDatabase()) {
            HatLogger.logError("Vault", "Export aborted: WAL checkpoint was busy, vault would have been incomplete.")
            return@withContext false
        }
        VaultSecurity.exportMasterVault(outputStream, storage.getDatabaseFile(), storage.getCombinedPreferencesJson())
        true
    }

    suspend fun restoreSecureVault(inputStream: InputStream, tempDir: File): OfflineStorage.RestoreReport = withContext(Dispatchers.IO) {
        try {
            val result = VaultSecurity.importMasterVault(inputStream, tempDir)
            if (result != null) {
                val (dbFile, prefs) = result
                storage.restoreCombinedPreferences(prefs)
                val report = storage.mergeDatabase(dbFile)
                dbFile.delete(); forceInvalidateCache()
                return@withContext report
            }
            return@withContext OfflineStorage.RestoreReport(success = false, offlineMerged = 0, offlineSkipped = 0)
        } catch (e: Exception) { return@withContext OfflineStorage.RestoreReport(success = false, offlineMerged = 0, offlineSkipped = 0) }
    }
    
    suspend fun exportCalculatedMatrix(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val startBound = getOldestSystemData(); val endBound = System.currentTimeMillis(); val hiddenPkgs = storage.getHiddenPackagesFlow().first()
        val writer = android.util.JsonWriter(java.io.OutputStreamWriter(outputStream, "UTF-8"))
        writer.setIndent("  "); writer.beginArray()
        var chunkStart = startBound; val chunkDuration = 30L * 86400000L 
        while (chunkStart < endBound) {
            yield(); val chunkEnd = minOf(chunkStart + chunkDuration, endBound)
            writer.beginObject().name("CHUNK_START").value(chunkStart).name("CHUNK_END").value(chunkEnd)
            val offlineMatrix = storage.getOfflineActivitiesBetween(chunkStart, chunkEnd)
            writer.name("OFFLINE"); writer.beginObject()
            // getOfflineActivitiesBetween now returns rows that merely OVERLAP the
            // chunk, so a row straddling a 30-day boundary comes back in two
            // consecutive chunks. Writing it unclamped would export the whole row
            // twice and the two halves would not add up to the original duration.
            // Each chunk therefore carries only the part of the row inside it.
            offlineMatrix.groupBy { "OFFLINE::${it.title}" }.forEach { (titleKey, items) -> writer.name(titleKey); writer.beginArray(); items.sortedBy { it.timestamp }.forEach { item ->
                val rowStart = maxOf(item.timestamp, chunkStart)
                val rowEnd = minOf(item.timestamp + item.durationInMillis, chunkEnd)
                if (rowEnd > rowStart) writer.beginObject().name("s").value(rowStart).name("e").value(rowEnd).endObject()
            }; writer.endArray() }
            writer.endObject()
            val rawMatrix = getFilteredIntervals(chunkStart, chunkEnd, hiddenPkgs)
            writer.name("APP_USAGE"); writer.beginObject()
            rawMatrix.forEach { (pkg, intervals) -> writer.name(pkg); writer.beginArray(); intervals.forEach { (s, e) -> writer.beginObject().name("s").value(s).name("e").value(e).endObject() }; writer.endArray() }
            writer.endObject(); writer.endObject() 
            // Dropping the reference is what lets the chunk be collected; the
            // explicit System.gc() that used to sit here was a stop-the-world
            // pause once per 30-day chunk and does not change whether an OOM
            // would occur - ART's collector decides that either way.
            cachedIntervals = emptyMap(); chunkStart = chunkEnd
        }
        writer.endArray(); writer.close(); forceInvalidateCache()
    }

    suspend fun exportCrashLogs(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val logs = HatLogger.getCrashLogs(context); val writer = outputStream.bufferedWriter()
        if (logs.isEmpty()) writer.write("No crash logs found.\n") else logs.forEach { file -> writer.write("=== File: ${file.name} ===\n"); writer.write(file.readText()); writer.write("\n\n") }
        writer.flush()
    }

    /**
     * Exports the raw usage event stream for the last [days] days, one line
     * per event: millisecond timestamp, local-time rendering, type code and
     * name, package, class. Events appear in the exact order queryEvents
     * yields them, which is the order HAT's replays resolve ties with, and
     * which no logcat or dumpsys view can show at millisecond precision.
     * Born from the Bitget anchor investigation (2026-10-23): the idle span
     * on the card read 8:23 PM while the session itself began 7:54 PM, and
     * the only way to settle which event order the phone really delivers is
     * to see the stream the app actually receives.
     */
    suspend fun exportRawEventStream(outputStream: OutputStream, days: Int = 3) = withContext(Dispatchers.IO) {
        val end = System.currentTimeMillis()
        val start = end - days * 86_400_000L
        // The replays query with a 24h buffer before the window; the export
        // uses the same buffer so head-of-window events match what a day
        // view would see.
        val events = engine.collectRawEventsForExport(start - 86_400_000L, end)
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        val writer = outputStream.bufferedWriter()
        writer.write("# HAT raw event stream, ${sdf.format(java.util.Date(start))} to ${sdf.format(java.util.Date(end))}")
        writer.write("\n# Arrival order preserved, millisecond timestamps, not re-sorted.\n")
        for (e in events) {
            yield()
            writer.write("ts=${e.timestamp} [${sdf.format(java.util.Date(e.timestamp))}] type=${e.type} (${EventCode.nameOf(e.type)}) pkg=${e.packageName} class=${e.className ?: ""}\n")
        }
        writer.flush()
    }
    
    suspend fun exportDiagnosticLogs(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val logs = HatLogger.getDiagnosticLogs(context); val writer = outputStream.bufferedWriter()
        if (logs.isEmpty()) writer.write("No diagnostic events found.\n") else logs.forEach { file -> writer.write("=== File: ${file.name} ===\n"); writer.write(file.readText()); writer.write("\n\n") }
        writer.flush()
    }

    suspend fun clearCrashLogs() = withContext(Dispatchers.IO) { HatLogger.clearLogs(context) }
    // Deleting a label makes its window unknown-offline again, so the idle
    // suppression for that window has to go with it - see
    // OfflineStorage.removeIgnoredSessionsInWindow. Windows are de-duplicated
    // because two activities can share one, and the delete is window-wide.
    suspend fun deleteOfflineActivitiesByTitle(title: String, start: Long, end: Long) = withContext(Dispatchers.IO) {
        val acts = storage.getOfflineActivitiesByTitleBetween(title, start, end)
        storage.deleteOfflineActivitiesByTitle(title, start, end)
        acts.map { it.timestamp to (it.timestamp + it.durationInMillis) }.distinct().forEach { (from, to) -> storage.removeIgnoredSessionsInWindow(from, to) }
    }
    suspend fun updateOfflineActivitiesByTitle(oldTitle: String, newTitle: String, newIconName: String, start: Long, end: Long) = withContext(Dispatchers.IO) { storage.updateOfflineActivitiesByTitle(oldTitle, newTitle, newIconName, start, end) }
    // A fresh label for a gap is an INSERT. Editing an existing one renames it via
    // updateOfflineActivitiesByTitle. Nothing here guesses which existing row to
    // overwrite - see the removed insertOrReplaceLabel for why that destroyed labels.
    suspend fun saveOfflineActivity(title: String, duration: Long, timestamp: Long, iconName: String) = withContext(Dispatchers.IO) { if (duration > 0L) storage.addActivityAtomic(OfflineActivityEntity(title = title, durationInMillis = duration, timestamp = timestamp, iconName = iconName)) }
    suspend fun hideApp(packageName: String) { storage.saveHiddenPackages(storage.getHiddenPackagesFlow().first() + packageName) }
    suspend fun unhideApp(packageName: String) { storage.saveHiddenPackages(storage.getHiddenPackagesFlow().first() - packageName) }
    suspend fun getHiddenAppList(): List<AppUsageItem> = withContext(Dispatchers.IO) { storage.getHiddenPackagesFlow().first().map { pkg -> AppUsageItem(pkg, getAppName(pkg), 0L, 0L) }.sortedBy { it.title } }
    suspend fun restoreAcknowledgedGhost(packageName: String, startMillis: Long, endMillis: Long) = withContext(Dispatchers.IO) { storage.removeAcknowledgedGhost(packageName, startMillis, endMillis); forceInvalidateCache() }

    suspend fun getSmartSuggestions(targetHour: Int): List<String> = withContext(Dispatchers.IO) { 
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 86400000L)
        val activities = storage.getOfflineActivitiesBetween(thirtyDaysAgo, System.currentTimeMillis())
        if (activities.isEmpty()) {
            return@withContext when (targetHour) {
                in 22..23, in 0..5 -> listOf("Sleep", "Relax", "Read")
                in 6..9 -> listOf("Commute", "Work", "Coffee", "Study")
                in 10..16 -> listOf("Work", "Eat", "Study", "Chat")
                in 17..21 -> listOf("Commute", "Eat", "Family", "Game", "Relax")
                else -> listOf("Sleep", "Work", "Eat")
            }
        }
        val weightByTitle = mutableMapOf<String, Double>()
        activities.forEach { act -> 
            val hour = Calendar.getInstance().apply { timeInMillis = act.timestamp }.get(Calendar.HOUR_OF_DAY)
            // The clock is circular: 23:00 is 2 hours from 01:00, not 22. The old
            // abs(hour - targetHour) scored a late-night habit as maximally
            // distant from an after-midnight one, which is the most common case.
            val weight = hourProximityWeight(circularHourDistance(hour, targetHour))
            weightByTitle[act.title] = (weightByTitle[act.title] ?: 0.0) + weight
        }
        weightByTitle.entries.sortedByDescending { it.value }.take(5).map { it.key } 
    }
}
