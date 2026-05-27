package andromedvn.heuristic.activity.tracker.data

import android.content.Context
import android.util.LruCache
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
            val startOfToday = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val minSystemData = getOldestSystemData()
            val startOfMinSystemData = Calendar.getInstance().apply { timeInMillis = minSystemData; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val lastArchived = storage.getLatestArchivedDayMillis()
            var currentDay = if (lastArchived != null) lastArchived + 86400000L else startOfMinSystemData
            val db = storage.dbHelper.writableDatabase
            
            var daysProcessed = 0
            while (currentDay < startOfToday && daysProcessed < 5) {
                yield()
                if (currentDay >= startOfMinSystemData) {
                    val raw = engine.getUsageIntervals(currentDay, currentDay + 86400000L, emptySet())
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
                daysProcessed++
                val driftCal = Calendar.getInstance().apply { timeInMillis = currentDay }; driftCal.add(Calendar.DAY_OF_YEAR, 1); currentDay = driftCal.timeInMillis
            }
        } finally { isArchiverRunning.set(false) }
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

            val ignoredSessions = storage.getIgnoredSessionsBetween(windowStart, windowEnd)
            if (ignoredSessions.isNotEmpty()) {
                val ignoredByPkg = ignoredSessions.groupBy { it.packageName }
                for (pkg in fetchedIntervals.keys.toList()) {
                    ignoredByPkg[pkg]?.let { ignores ->
                        fetchedIntervals[pkg] = engine.subtractIntervals(fetchedIntervals[pkg]!!, ignores.map { it.startMillis to it.endMillis }).toMutableList()
                    }
                }
            }
            cachedIntervals = fetchedIntervals; cacheKey = Pair(windowStart, windowEnd); lastCacheTime = lockedNow
            clampIntervals(cachedIntervals, start, end)
        }
    }

    suspend fun getFilteredIntervals(start: Long, end: Long, hiddenPkgs: Set<String>): Map<String, List<Pair<Long, Long>>> = getAllIntervalsCached(start, end).filterKeys { it !in hiddenPkgs }
    fun getRawOfflineActivitiesBetween(start: Long, end: Long) = storage.getOfflineActivitiesBetween(start, end)
    fun getRawOfflineActivitiesByTitleBetween(title: String, start: Long, end: Long) = storage.getOfflineActivitiesByTitleBetween(title, start, end)
    fun getAcknowledgedGhostsBetween(start: Long, end: Long) = storage.getAcknowledgedGhostsBetween(start, end)
    
    suspend fun clearAllData() { storage.clearAllData() }
    
    suspend fun exportSecureVault(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        storage.checkpointDatabase()
        VaultSecurity.exportMasterVault(outputStream, storage.getDatabaseFile(), storage.getCombinedPreferencesJson())
    }

    suspend fun restoreSecureVault(inputStream: InputStream, tempDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = VaultSecurity.importMasterVault(inputStream, tempDir)
            if (result != null) {
                val (dbFile, prefs) = result
                storage.restoreCombinedPreferences(prefs)
                val mergeSuccess = storage.mergeDatabase(dbFile)
                dbFile.delete(); forceInvalidateCache()
                return@withContext mergeSuccess
            }
            return@withContext false
        } catch (e: Exception) { return@withContext false }
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
            offlineMatrix.groupBy { "OFFLINE::${it.title}" }.forEach { (titleKey, items) -> writer.name(titleKey); writer.beginArray(); items.sortedBy { it.timestamp }.forEach { item -> writer.beginObject().name("s").value(item.timestamp).name("e").value(item.timestamp + item.durationInMillis).endObject() }; writer.endArray() }
            writer.endObject()
            val rawMatrix = getFilteredIntervals(chunkStart, chunkEnd, hiddenPkgs)
            writer.name("APP_USAGE"); writer.beginObject()
            rawMatrix.forEach { (pkg, intervals) -> writer.name(pkg); writer.beginArray(); intervals.forEach { (s, e) -> writer.beginObject().name("s").value(s).name("e").value(e).endObject() }; writer.endArray() }
            writer.endObject(); writer.endObject() 
            cachedIntervals = emptyMap(); System.gc(); chunkStart = chunkEnd
        }
        writer.endArray(); writer.close(); forceInvalidateCache()
    }

    suspend fun exportCrashLogs(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val logs = HatLogger.getCrashLogs(context); val writer = outputStream.bufferedWriter()
        if (logs.isEmpty()) writer.write("No crash logs found.\n") else logs.forEach { file -> writer.write("=== File: ${file.name} ===\n"); writer.write(file.readText()); writer.write("\n\n") }
        writer.flush()
    }
    
    suspend fun exportDiagnosticLogs(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val logs = HatLogger.getDiagnosticLogs(context); val writer = outputStream.bufferedWriter()
        if (logs.isEmpty()) writer.write("No diagnostic events found.\n") else logs.forEach { file -> writer.write("=== File: ${file.name} ===\n"); writer.write(file.readText()); writer.write("\n\n") }
        writer.flush()
    }

    suspend fun clearCrashLogs() = withContext(Dispatchers.IO) { HatLogger.clearLogs(context) }
    suspend fun deleteOfflineActivitiesByTitle(title: String, start: Long, end: Long) = withContext(Dispatchers.IO) { val acts = storage.getOfflineActivitiesByTitleBetween(title, start, end); storage.deleteOfflineActivitiesByTitle(title, start, end); acts.forEach { storage.removeIgnoredSessionExact(it.timestamp, it.timestamp + it.durationInMillis) } }
    suspend fun updateOfflineActivitiesByTitle(oldTitle: String, newTitle: String, newIconName: String, start: Long, end: Long) = withContext(Dispatchers.IO) { storage.updateOfflineActivitiesByTitle(oldTitle, newTitle, newIconName, start, end) }
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
        val weights = mutableMapOf<String, Double>()
        activities.forEach { act -> 
            val distance = kotlin.math.abs(Calendar.getInstance().apply { timeInMillis = act.timestamp }.get(Calendar.HOUR_OF_DAY) - targetHour)
            weights[act.title] = (weights[act.title] ?: 0.0) + (if(distance == 0) 3.0 else if(distance <= 2) 1.5 else 0.5) 
        }
        weights.entries.sortedByDescending { it.value }.take(5).map { it.key } 
    }
}
