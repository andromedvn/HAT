package andromedvn.heuristic.activity.tracker.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import andromedvn.heuristic.activity.tracker.domain.Interval
import andromedvn.heuristic.activity.tracker.domain.IntervalOverlapIndex
import andromedvn.heuristic.activity.tracker.domain.subtractIntervals
import andromedvn.heuristic.activity.tracker.domain.SettingsMigration
import andromedvn.heuristic.activity.tracker.utils.HatLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val Context.dataStore by preferencesDataStore("hat_secure_prefs")

class OfflineStorage private constructor(private val context: Context) {
    /**
     * Chunked-transaction convention for the vault merge: every 500 rows,
     * commit, yield to other writers, and open the next chunk. Keeps one big
     * merge from holding the write lock for the whole import. The caller
     * opens the first transaction and does the final setTransactionSuccessful
     * in its own finally.
     */
    private suspend fun chunkTx(db: android.database.sqlite.SQLiteDatabase, count: Int) {
        if (count % 500 == 0) {
            db.setTransactionSuccessful()
            db.endTransaction()
            yield()
            db.beginTransaction()
        }
    }

    private val SETTINGS_KEY = stringPreferencesKey("user_settings")
    private val HIDDEN_APPS_KEY = stringPreferencesKey("hidden_apps")
    
    // encodeDefaults is REQUIRED: with kotlinx's default (false), a field set
    // back to its data-class default (Gap Minimum "1 min" = detailLevel 0) is
    // OMITTED from the saved JSON, the migration then re-derives the old value
    // on the next read, and the setting reads as stuck.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val storageScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    val dbHelper = HatDatabaseHelper(context)

    val settings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        prefs[SETTINGS_KEY]?.let {
            try {
                // Settings upgrade path (2026-10-21): JSON written before
                // 1.1.2 lacks the detailLevel key. The migration derives the
                // new Minimum Duration from the stored legacy gap threshold,
                // emits it, and persists it in the same pass. The write
                // re-emits this flow and the second read finds the key, so
                // the migration runs exactly once per install.
                SettingsMigration.migrate(it)?.also { migrated ->
                    storageScope.launch { runCatching { saveSettings(migrated) } }
                } ?: json.decodeFromString(it)
            } catch(e: Exception) { UserSettings() }
        } ?: UserSettings()
    }

    init { storageScope.launch { migrateJsonToSqlite() } }

    companion object {
        @Volatile private var INSTANCE: OfflineStorage? = null
        fun getInstance(context: Context): OfflineStorage = INSTANCE ?: synchronized(this) { INSTANCE ?: OfflineStorage(context.applicationContext).also { INSTANCE = it } }
    }

    suspend fun getCombinedPreferencesJson(): String {
        val s = settings.first()
        val h = getHiddenPackagesFlow().first()
        val i = getIgnoredSessions()
        val a = getAllAcknowledgedGhosts()
        return json.encodeToString(CombinedPreferences(s, h, i, a))
    }

    suspend fun restoreCombinedPreferences(jsonStr: String) {
        try {
            val incoming = json.decodeFromString<CombinedPreferences>(jsonStr)
            saveSettings(incoming.settings)
            
            val currentHidden = getHiddenPackagesFlow().first()
            saveHiddenPackages(currentHidden + incoming.hiddenApps)
            
            val currentIgnoredSet = getIgnoredSessions().map { "${it.packageName}_${it.startMillis}_${it.endMillis}" }.toHashSet()
            val currentAckSet = getAllAcknowledgedGhosts().map { "${it.packageName}_${it.startMillis}_${it.endMillis}" }.toHashSet()
            
            incoming.ignoredSessions.forEach { incomingSession ->
                val sig = "${incomingSession.packageName}_${incomingSession.startMillis}_${incomingSession.endMillis}"
                if (!currentIgnoredSet.contains(sig)) {
                    addIgnoredSession(incomingSession)
                }
            }
            
            incoming.acknowledgedGhosts.forEach { incomingGhost ->
                val sig = "${incomingGhost.packageName}_${incomingGhost.startMillis}_${incomingGhost.endMillis}"
                if (!currentAckSet.contains(sig)) {
                    addAcknowledgedGhost(incomingGhost)
                }
            }
        } catch(e: Exception) {
            HatLogger.logError("OfflineStorage", "Failed to restore CombinedPreferences", e)
        }
    }

    /**
     * Flush the write-ahead log into the main database file before it is copied.
     *
     * BUG (fixed 2026-09-30, audit F2): this ran the checkpoint and threw the result
     * away. `PRAGMA wal_checkpoint` returns `(busy, logFrames, checkpointedFrames)`, and
     * a non-zero `busy` means the checkpoint could NOT complete because another
     * connection held the database open. HAT reads from several places and caches a
     * 5-minute interval map, so busy=1 is a steady state, not a corner case.
     *
     * Because WAL is enabled and only `hat_heuristic.db` is archived (never -wal/-shm),
     * an incomplete checkpoint means the exported vault silently omits recent writes -
     * verified: a row written moments before export was absent from the exported file -
     * or, when the schema itself is still in the WAL, produces a file that fails to open
     * at restore time with "database disk image is malformed".
     *
     * Returning the flag lets the caller refuse to emit a vault it cannot vouch for,
     * rather than handing the user a backup that looks fine and is not.
     */
    fun checkpointDatabase(): Boolean {
        return try {
            dbHelper.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                it.moveToFirst() && it.getInt(0) == 0
            }
        } catch (e: Exception) {
            HatLogger.logError("OfflineStorage", "WAL checkpoint failed: ${e.message}")
            false
        }
    }

    fun getDatabaseFile(): File = context.getDatabasePath("hat_heuristic.db")

    /**
     * What a vault restore actually did. `success` means every table present
     * in the vault merged cleanly; the counters are the OFFLINE-label table's
     * outcome, the one table whose rows can be legitimately skipped (a row
     * that temporally overlaps one already on the device). Reporting the skip
     * count is the difference between "Restored" and "Restored, minus the
     * labels you already had" - silence there reads as data loss later.
     */
    data class RestoreReport(val success: Boolean, val offlineMerged: Int, val offlineSkipped: Int)

    suspend fun mergeDatabase(importedDbFile: File): RestoreReport {
        val currentDb = dbHelper.writableDatabase
        var importedDb: SQLiteDatabase? = null
        var success = false
        var offlineMerged = 0
        var offlineSkipped = 0
        // Per-table merge failures are tracked rather than swallowed. Previously
        // each table was wrapped in its own catch that logged and continued, and
        // success was set to true unconditionally at the end - so a restore that
        // lost, say, the entire archive table still reported "Vault Restored"
        // and the user had no way to know their history was incomplete.
        val failedTables = mutableListOf<String>()

        fun tableExists(db: SQLiteDatabase, table: String): Boolean =
            db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)).use { it.moveToFirst() }

        // A table the imported vault does not contain is an older vault, not a
        // failure - there is simply nothing to merge. A table that IS present
        // and then fails to merge is a real failure, and must make the whole
        // restore report failure so the user is not told their data is intact
        // when it is not.
        fun recordTableFailure(table: String, e: Exception) {
            if (importedDb?.let { tableExists(it, table) } == true) {
                failedTables += table
                HatLogger.logError("OfflineStorage", "Vault merge FAILED for table $table", e)
            } else {
                HatLogger.logError("OfflineStorage", "Vault predates $table; nothing to merge", e)
            }
        }

        try {
            importedDb = SQLiteDatabase.openDatabase(importedDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

            try {
                val curOffline = importedDb.query(HatDatabaseHelper.TABLE_OFFLINE, null, null, null, null, null, null)
                curOffline.use {
                    val idIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_ID)
                    val titleIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_TITLE)
                    val durIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_DURATION)
                    val timeIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_TIMESTAMP)
                    val iconIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_ICON)
                    
                    var count = 0
                    var skipped = 0
                    // The overlap test used to be a full table scan executed once
                    // per imported row, which is hundreds of millions of
                    // comparisons against a ~23,000-row destination. The
                    // destination is now read once into an in-memory index.
                    //
                    // The index MUST be fed every row actually inserted: the old
                    // SQL saw the table grow as it merged, so each new row had to
                    // become visible to the next row's check. Omitting that is a
                    // correctness bug, not just a lost optimisation.
                    val existingIntervals = currentDb.query(
                        HatDatabaseHelper.TABLE_OFFLINE,
                        arrayOf(HatDatabaseHelper.COL_TIMESTAMP, HatDatabaseHelper.COL_DURATION),
                        null, null, null, null, null
                    ).use { c ->
                        val sIdx = c.getColumnIndexOrThrow(HatDatabaseHelper.COL_TIMESTAMP)
                        val dIdx = c.getColumnIndexOrThrow(HatDatabaseHelper.COL_DURATION)
                        buildList {
                            while (c.moveToNext()) {
                                val s = c.getLong(sIdx)
                                add(s to (s + c.getLong(dIdx)))
                            }
                        }
                    }
                    val overlapIndex = IntervalOverlapIndex.build(existingIntervals)
                    // The same intervals as a plain list: the sharding path
                    // (r12p) subtracts them from each colliding import row, so
                    // a restore keeps the surviving fragments instead of
                    // discarding the whole label.
                    val accounted = mutableListOf<Interval>()
                    accounted.addAll(existingIntervals)
                    currentDb.beginTransaction()
                    try {
                        while (it.moveToNext()) {
                            val ts = it.getLong(timeIdx)
                            val dur = it.getLong(durIdx)
                            val end = ts + dur

                            val hasOverlap = overlapIndex.overlapsAny(ts, end)

                            // Sharding (r12p, ported from HAT Pro's cross-vault
                            // collision handling): a colliding import row is
                            // geometrically cut against what this device already
                            // accounts, and the surviving fragments land as rows.
                            // The old behavior skipped the WHOLE label on any
                            // overlap, silently discarding the valid parts.
                            val fragments = if (hasOverlap) {
                                val kept = subtractIntervals(listOf(ts to end), accounted)
                                kept.filter { it.second > it.first }
                            } else {
                                listOf(ts to end)
                            }

                            if (fragments.isNotEmpty()) {
                                for ((fragStart, fragEnd) in fragments) {
                                    val cv = ContentValues().apply {
                                        // BUG (fixed 2026-09-30, audit F3): the source primary key was
                                        // copied verbatim, so CONFLICT_IGNORE dropped any imported row
                                        // whose id happened to exist here for an UNRELATED reason. Row ids
                                        // are dense and restart at 1 per install, so restoring a vault from
                                        // another phone onto a phone that already has labels collided on
                                        // almost every row - they were silently discarded while still being
                                        // counted as merged, so the UI reported success.
                                        //
                                        // Verified: 4 non-overlapping imported rows against a 3-row
                                        // destination -> 0 rows landed, 4 counted. Ids are per-install
                                        // bookkeeping, not identity; (title, timestamp, duration, icon) is.
                                        // Letting SQLite assign the id removes the collision entirely.
                                        // Sharding (r12p): each fragment keeps the label and gets its
                                        // own surviving bounds.
                                        put(HatDatabaseHelper.COL_TITLE, it.getString(titleIdx))
                                        put(HatDatabaseHelper.COL_DURATION, fragEnd - fragStart)
                                        put(HatDatabaseHelper.COL_TIMESTAMP, fragStart)
                                        put(HatDatabaseHelper.COL_ICON, it.getString(iconIdx))
                                    }
                                    val inserted = currentDb.insertWithOnConflict(HatDatabaseHelper.TABLE_OFFLINE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                                    if (inserted != -1L) {
                                        overlapIndex.add(fragStart, fragEnd)
                                        accounted.add(fragStart to fragEnd)
                                        count++
                                    } else {
                                        HatLogger.logError("OfflineStorage", "Vault Merge: Imported offline activity was rejected on write; not counted as merged.")
                                    }
                                }
                            } else {
                                skipped++
                                HatLogger.logError("OfflineStorage", "Vault Merge: Imported label fully overlapped existing time; nothing survived to shard.")
                            }
                            chunkTx(currentDb, count)
                        }
                        currentDb.setTransactionSuccessful()
                    } finally {
                        if (currentDb.inTransaction()) currentDb.endTransaction()
                    }
                    offlineMerged = count
                    offlineSkipped = skipped
                }
            } catch (e: Exception) { recordTableFailure(HatDatabaseHelper.TABLE_OFFLINE, e) }

            try {
                val curArchive = importedDb.query(HatDatabaseHelper.TABLE_ARCHIVE, arrayOf(HatDatabaseHelper.COL_PKG, HatDatabaseHelper.COL_START, HatDatabaseHelper.COL_END), null, null, null, null, null)
                curArchive.use {
                    val pkgIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_PKG)
                    val sIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_START)
                    val eIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_END)
                    
                    var count = 0
                    currentDb.beginTransaction()
                    try {
                        while (it.moveToNext()) {
                            val cv = ContentValues().apply { put(HatDatabaseHelper.COL_PKG, it.getString(pkgIdx)); put(HatDatabaseHelper.COL_START, it.getLong(sIdx)); put(HatDatabaseHelper.COL_END, it.getLong(eIdx)) }
                            currentDb.insertWithOnConflict(HatDatabaseHelper.TABLE_ARCHIVE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                            
                            count++
                            chunkTx(currentDb, count)
                        }
                        currentDb.setTransactionSuccessful()
                    } finally {
                        if (currentDb.inTransaction()) currentDb.endTransaction()
                    }
                }
            } catch (e: Exception) { recordTableFailure(HatDatabaseHelper.TABLE_ARCHIVE, e) }

            try {
                val curIgnored = importedDb.query(HatDatabaseHelper.TABLE_IGNORED, arrayOf(HatDatabaseHelper.COL_EVENT_PKG, HatDatabaseHelper.COL_EVENT_START, HatDatabaseHelper.COL_EVENT_END), null, null, null, null, null)
                curIgnored.use {
                    val pkgIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_PKG)
                    val sIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_START)
                    val eIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_END)
                    
                    var count = 0
                    currentDb.beginTransaction()
                    try {
                        while (it.moveToNext()) {
                            val cv = ContentValues().apply { put(HatDatabaseHelper.COL_EVENT_PKG, it.getString(pkgIdx)); put(HatDatabaseHelper.COL_EVENT_START, it.getLong(sIdx)); put(HatDatabaseHelper.COL_EVENT_END, it.getLong(eIdx)) }
                            currentDb.insertWithOnConflict(HatDatabaseHelper.TABLE_IGNORED, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                            
                            count++
                            chunkTx(currentDb, count)
                        }
                        currentDb.setTransactionSuccessful()
                    } finally {
                        if (currentDb.inTransaction()) currentDb.endTransaction()
                    }
                }
            } catch (e: Exception) { recordTableFailure(HatDatabaseHelper.TABLE_IGNORED, e) }

            try {
                val curAck = importedDb.query(HatDatabaseHelper.TABLE_ACK_GHOSTS, arrayOf(HatDatabaseHelper.COL_EVENT_PKG, HatDatabaseHelper.COL_EVENT_START, HatDatabaseHelper.COL_EVENT_END), null, null, null, null, null)
                curAck.use {
                    val pkgIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_PKG)
                    val sIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_START)
                    val eIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_END)

                    var count = 0
                    currentDb.beginTransaction()
                    try {
                        while (it.moveToNext()) {
                            val cv = ContentValues().apply { put(HatDatabaseHelper.COL_EVENT_PKG, it.getString(pkgIdx)); put(HatDatabaseHelper.COL_EVENT_START, it.getLong(sIdx)); put(HatDatabaseHelper.COL_EVENT_END, it.getLong(eIdx)) }
                            currentDb.insertWithOnConflict(HatDatabaseHelper.TABLE_ACK_GHOSTS, null, cv, SQLiteDatabase.CONFLICT_IGNORE)

                            count++
                            chunkTx(currentDb, count)
                        }
                        currentDb.setTransactionSuccessful()
                    } finally {
                        if (currentDb.inTransaction()) currentDb.endTransaction()
                    }
                }
            } catch (e: Exception) { recordTableFailure(HatDatabaseHelper.TABLE_ACK_GHOSTS, e) }

            try {
                val curDismissed = importedDb.query(HatDatabaseHelper.TABLE_SCREEN_OFF_DISMISSED, arrayOf(HatDatabaseHelper.COL_EVENT_PKG, HatDatabaseHelper.COL_EVENT_START, HatDatabaseHelper.COL_EVENT_END), null, null, null, null, null)
                curDismissed.use {
                    val pkgIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_PKG)
                    val sIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_START)
                    val eIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_END)

                    var count = 0
                    currentDb.beginTransaction()
                    try {
                        while (it.moveToNext()) {
                            val cv = ContentValues().apply { put(HatDatabaseHelper.COL_EVENT_PKG, it.getString(pkgIdx)); put(HatDatabaseHelper.COL_EVENT_START, it.getLong(sIdx)); put(HatDatabaseHelper.COL_EVENT_END, it.getLong(eIdx)) }
                            currentDb.insertWithOnConflict(HatDatabaseHelper.TABLE_SCREEN_OFF_DISMISSED, null, cv, SQLiteDatabase.CONFLICT_IGNORE)

                            count++
                            chunkTx(currentDb, count)
                        }
                        currentDb.setTransactionSuccessful()
                    } finally {
                        if (currentDb.inTransaction()) currentDb.endTransaction()
                    }
                }
            } catch (e: Exception) { recordTableFailure(HatDatabaseHelper.TABLE_SCREEN_OFF_DISMISSED, e) }

            // Only claim success if every table that was actually present in the
            // vault merged cleanly. A partial restore reported as a successful
            // one is worse than a visible failure: the user believes their
            // history is intact when some of it was never written.
            if (failedTables.isNotEmpty()) {
                HatLogger.logError("OfflineStorage", "Vault restore INCOMPLETE. Failed tables: ${failedTables.joinToString(", ")}")
                success = false
            } else {
                success = true
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            HatLogger.logError("OfflineStorage", "Vault Merge Failed: Imported file is malformed or not a valid SQLite database.", e)
        } catch (e: Exception) {
            HatLogger.logError("OfflineStorage", "Exception during Time-Stitch Database Merge", e)
        } finally {
            importedDb?.close()
        }
        
        return RestoreReport(success, offlineMerged, offlineSkipped)
    }

    private fun migrateJsonToSqlite() {
        val db = dbHelper.writableDatabase
        val chunkDir = File(context.filesDir, "offline_chunks")
        if (chunkDir.exists()) {
            db.beginTransaction()
            try {
                chunkDir.listFiles()?.forEach { file -> 
                    if (file.name.endsWith(".json")) { 
                        try { 
                            json.decodeFromString<List<OfflineActivityEntity>>(file.readText()).forEach { insertActivityInternal(db, it) } 
                            file.delete()
                        } catch (e: Exception) { HatLogger.logError("OfflineStorage", "Legacy activity chunk ${file.name} failed to migrate; keeping the file for retry: ${e.message}") } 
                    } 
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val archiveDir = File(context.filesDir, "app_archive_chunks")
        if (archiveDir.exists()) {
            db.beginTransaction()
            try {
                archiveDir.listFiles()?.forEach { file -> 
                    if (file.name.endsWith(".json")) { 
                        try { 
                            json.decodeFromString<List<ArchivedDay>>(file.readText()).forEach { day -> day.usage.forEach { usage -> usage.spans.forEach { span -> insertArchivedSpanInternal(db, usage.pkg, span.s, span.e) } } } 
                            file.delete()
                        } catch (e: Exception) { HatLogger.logError("OfflineStorage", "Legacy archive chunk ${file.name} failed to migrate; keeping the file for retry: ${e.message}") } 
                    } 
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        
        storageScope.launch {
            val prefs = context.dataStore.data.first()
            prefs[stringPreferencesKey("ignored_sessions")]?.let {
                try {
                    val old: List<IgnoredSession> = json.decodeFromString(it)
                    old.forEach { s -> addIgnoredSession(s) }
                    context.dataStore.edit { p -> p.remove(stringPreferencesKey("ignored_sessions")) }
                } catch(e: Exception) { HatLogger.logError("OfflineStorage", "Legacy ignored_sessions failed to migrate; keeping the entry for retry: ${e.message}") }
            }
        }
    }


    suspend fun saveSettings(newSettings: UserSettings) { 
        context.dataStore.edit { it[SETTINGS_KEY] = json.encodeToString(newSettings) } 
    }

    fun addActivityAtomic(activity: OfflineActivityEntity) { 
        insertActivityInternal(dbHelper.writableDatabase, activity) 
    }


    fun deleteOfflineActivitiesByTitle(title: String, start: Long, end: Long) {
        // Overlap, not "starts inside" (known issue #2): a label crossing
        // midnight is visible on day 2 through the overlap READ, and deleting
        // it from there must work the same way it does from day 1 - the row
        // is one label, so it goes as a whole from whichever day shows it.
        dbHelper.writableDatabase.delete(HatDatabaseHelper.TABLE_OFFLINE, OfflineOverlapSql.TITLED_PREDICATE, OfflineOverlapSql.titledArgs(title, end, start))
    }

    fun updateOfflineActivitiesByTitle(oldTitle: String, newTitle: String, newIconName: String, start: Long, end: Long) {
        val cv = ContentValues().apply { put(HatDatabaseHelper.COL_TITLE, newTitle); put(HatDatabaseHelper.COL_ICON, newIconName) }
        // Same overlap window as the delete and the by-title read, so a
        // midnight-crossing label can be renamed from the day that shows its
        // tail, not only from the day it starts on.
        dbHelper.writableDatabase.update(HatDatabaseHelper.TABLE_OFFLINE, cv, OfflineOverlapSql.TITLED_PREDICATE, OfflineOverlapSql.titledArgs(oldTitle, end, start))
    }

    suspend fun saveHiddenPackages(packages: Set<String>) { 
        context.dataStore.edit { it[HIDDEN_APPS_KEY] = json.encodeToString(packages) } 
    }

    /**
     * Labelling the same gap again should EDIT it, not stack a duplicate copy.
     * BUG (fixed 2026-09-30): id 0 gave every insert a fresh rowid, so
     * CONFLICT_REPLACE never collided and the exported vault held six copies of
     * the same label. Matching on (title, timestamp) fixes the repeat; a removed
     * sibling that matched on timestamp alone rewrote every row sharing that
     * instant, which is why the match is this wide and no wider.
     */
    fun insertActivityInternal(db: SQLiteDatabase, activity: OfflineActivityEntity) {
        val cv = ContentValues().apply { 
            if (activity.id != 0L) put(HatDatabaseHelper.COL_ID, activity.id)
            put(HatDatabaseHelper.COL_TITLE, activity.title)
            put(HatDatabaseHelper.COL_DURATION, activity.durationInMillis)
            put(HatDatabaseHelper.COL_TIMESTAMP, activity.timestamp)
            put(HatDatabaseHelper.COL_ICON, activity.iconName) 
        }
        db.insertWithOnConflict(HatDatabaseHelper.TABLE_OFFLINE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }



    fun insertArchivedSpanInternal(db: SQLiteDatabase, pkg: String, start: Long, end: Long) {
        val cv = ContentValues().apply { put(HatDatabaseHelper.COL_PKG, pkg); put(HatDatabaseHelper.COL_START, start); put(HatDatabaseHelper.COL_END, end) }
        db.insertWithOnConflict(HatDatabaseHelper.TABLE_ARCHIVE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun mapCursorToActivity(cursor: Cursor): OfflineActivityEntity = OfflineActivityEntity(id = cursor.getLong(cursor.getColumnIndexOrThrow(HatDatabaseHelper.COL_ID)), title = cursor.getString(cursor.getColumnIndexOrThrow(HatDatabaseHelper.COL_TITLE)), durationInMillis = cursor.getLong(cursor.getColumnIndexOrThrow(HatDatabaseHelper.COL_DURATION)), timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(HatDatabaseHelper.COL_TIMESTAMP)), iconName = cursor.getString(cursor.getColumnIndexOrThrow(HatDatabaseHelper.COL_ICON)))

    fun getOfflineActivitiesBetween(start: Long, end: Long): List<OfflineActivityEntity> {
        val list = mutableListOf<OfflineActivityEntity>()
        val db = dbHelper.readableDatabase
        // Overlap, not "starts inside". A label running 01:00-03:00 has its
        // timestamp on day 1, so a `timestamp BETWEEN start AND end` test can
        // never return it for day 2 and that day's hours go missing.
        //
        // Widening the query is safe precisely because every reader passes the
        // result through sanitizeOfflineRow, which clamps each row to the
        // window and drops the part that falls outside. A row may therefore
        // match two days and be counted once in each, for exactly the slice
        // that belongs to it, with no double counting and no lost milliseconds.
        val cursor = db.query(HatDatabaseHelper.TABLE_OFFLINE, null,
            OfflineOverlapSql.PREDICATE,
            OfflineOverlapSql.args(end, start), null, null, "${HatDatabaseHelper.COL_TIMESTAMP} ASC")
        cursor.use { while (it.moveToNext()) list.add(mapCursorToActivity(it)) }
        return list
    }

    fun getOfflineActivitiesByTitleBetween(title: String, start: Long, end: Long): List<OfflineActivityEntity> {
        val list = mutableListOf<OfflineActivityEntity>()
        val db = dbHelper.readableDatabase
        // Overlap, not "starts inside" (known issue #2): the detail, rename,
        // delete and cascade paths all flow through here, and every consumer
        // clamps rows to its window (sanitizeOfflineRow), so a label crossing
        // midnight is returned to BOTH of its days for exactly the slice that
        // belongs to each - visible on day 2, and now also openable, renamable
        // and deletable there.
        val cursor = db.query(HatDatabaseHelper.TABLE_OFFLINE, null, OfflineOverlapSql.TITLED_PREDICATE, OfflineOverlapSql.titledArgs(title, end, start), null, null, "${HatDatabaseHelper.COL_TIMESTAMP} ASC")
        cursor.use { while (it.moveToNext()) list.add(mapCursorToActivity(it)) }
        return list
    }


    fun getOldestOfflineActivityTimestamp(): Long? {
        val cursor = dbHelper.readableDatabase.rawQuery("SELECT MIN(${HatDatabaseHelper.COL_TIMESTAMP}) FROM ${HatDatabaseHelper.TABLE_OFFLINE}", null)
        cursor.use { if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0) }
        return null
    }

    fun getOldestArchivedTimestamp(): Long? {
        val cursor = dbHelper.readableDatabase.rawQuery("SELECT MIN(${HatDatabaseHelper.COL_START}) FROM ${HatDatabaseHelper.TABLE_ARCHIVE}", null)
        cursor.use { if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0) }
        return null
    }

    fun getArchivedIntervals(startMillis: Long, endMillis: Long): Map<String, List<Pair<Long, Long>>> {
        val mergedResult = mutableMapOf<String, MutableList<Pair<Long, Long>>>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(HatDatabaseHelper.TABLE_ARCHIVE, arrayOf(HatDatabaseHelper.COL_PKG, HatDatabaseHelper.COL_START, HatDatabaseHelper.COL_END), "${HatDatabaseHelper.COL_END} >= ? AND ${HatDatabaseHelper.COL_START} <= ?", arrayOf(startMillis.toString(), endMillis.toString()), null, null, null)
        cursor.use {
            val pkgIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_PKG)
            val startIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_START)
            val endIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_END)
            while (it.moveToNext()) {
                mergedResult.getOrPut(it.getString(pkgIdx)) { mutableListOf() }.add(it.getLong(startIdx) to it.getLong(endIdx))
            }
        }
        return mergedResult
    }

    fun getLatestArchivedDayMillis(): Long? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT MAX(${HatDatabaseHelper.COL_START}) FROM ${HatDatabaseHelper.TABLE_ARCHIVE}", null)
        var maxTime: Long? = null
        cursor.use { if (it.moveToFirst() && !it.isNull(0)) maxTime = it.getLong(0) }
        return maxTime?.let {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = it; cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
    }

    fun getHiddenPackagesFlow(): Flow<Set<String>> = context.dataStore.data.map { prefs -> prefs[HIDDEN_APPS_KEY]?.let { try { json.decodeFromString(it) } catch(e: Exception) { emptySet() } } ?: emptySet() }
    
    fun getIgnoredSessions(): List<IgnoredSession> {
        val list = mutableListOf<IgnoredSession>()
        dbHelper.readableDatabase.query(HatDatabaseHelper.TABLE_IGNORED, null, null, null, null, null, null).use {
            val pkgIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_PKG)
            val sIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_START)
            val eIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_END)
            while(it.moveToNext()) list.add(IgnoredSession(it.getString(pkgIdx), it.getLong(sIdx), it.getLong(eIdx)))
        }
        return list
    }
    

    fun addIgnoredSession(session: IgnoredSession) {
        val cv = ContentValues().apply { put(HatDatabaseHelper.COL_EVENT_PKG, session.packageName); put(HatDatabaseHelper.COL_EVENT_START, session.startMillis); put(HatDatabaseHelper.COL_EVENT_END, session.endMillis) }
        dbHelper.writableDatabase.insertWithOnConflict(HatDatabaseHelper.TABLE_IGNORED, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * Clears the idle-suppression records whose stored window is exactly
     * (startMillis, endMillis), for every package at once.
     *
     * The match is EXACT, not overlapping: a row is removed only when its
     * stored start and end equal the arguments. That is sufficient because
     * the only writer stores rows with exactly the bounds of the label
     * chunks created at conversion time, so unlabelling the same chunk
     * removes precisely its rows. A row that merely overlaps the window
     * (different bounds) is left alone.
     *
     * The package name is deliberately NOT part of the predicate. An
     * [IgnoredSession] suppresses app time that ghost detection decided was idle;
     * if that time sat inside a window the user has now unlabelled, the window is
     * no longer known to be offline and every app's suppressed time in it should
     * come back - not just the one the label happened to be attributed to. Scoping
     * this to a single package would strand the rest.
     *
     * Do not "fix" this by adding package_name to the WHERE clause.
     */
    fun removeIgnoredSessionsInWindow(startMillis: Long, endMillis: Long) {
        dbHelper.writableDatabase.delete(HatDatabaseHelper.TABLE_IGNORED, "start_millis = ? AND end_millis = ?", arrayOf(startMillis.toString(), endMillis.toString()))
    }

    fun getAllAcknowledgedGhosts(): List<AcknowledgedGhost> {
        val list = mutableListOf<AcknowledgedGhost>()
        dbHelper.readableDatabase.query(HatDatabaseHelper.TABLE_ACK_GHOSTS, null, null, null, null, null, null).use {
            val pkgIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_PKG)
            val sIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_START)
            val eIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_END)
            while(it.moveToNext()) list.add(AcknowledgedGhost(it.getString(pkgIdx), it.getLong(sIdx), it.getLong(eIdx)))
        }
        return list
    }
    

    fun addAcknowledgedGhost(ghost: AcknowledgedGhost) {
        val cv = ContentValues().apply { put(HatDatabaseHelper.COL_EVENT_PKG, ghost.packageName); put(HatDatabaseHelper.COL_EVENT_START, ghost.startMillis); put(HatDatabaseHelper.COL_EVENT_END, ghost.endMillis) }
        dbHelper.writableDatabase.insertWithOnConflict(HatDatabaseHelper.TABLE_ACK_GHOSTS, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }


    // --- Screen-Off Activity dismissals -------------------------------------
    // A dismissed span is one the user looked at and said "this really was
    // usage" about. It is NOT deleted: the engine merges the exact span back
    // into that app's usage totals, which is why the row must persist with
    // millisecond precision. Dismissing again after a restore must not
    // double-merge, hence the unique index on the triple.

    fun getDismissedScreenOffSpans(startMillis: Long, endMillis: Long): List<Triple<String, Long, Long>> {
        val list = mutableListOf<Triple<String, Long, Long>>()
        dbHelper.readableDatabase.query(
            HatDatabaseHelper.TABLE_SCREEN_OFF_DISMISSED, null,
            "end_millis >= ? AND start_millis <= ?",
            arrayOf(startMillis.toString(), endMillis.toString()),
            null, null, null
        ).use {
            val pkgIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_PKG)
            val sIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_START)
            val eIdx = it.getColumnIndexOrThrow(HatDatabaseHelper.COL_EVENT_END)
            while (it.moveToNext()) list.add(Triple(it.getString(pkgIdx), it.getLong(sIdx), it.getLong(eIdx)))
        }
        return list
    }

    fun dismissScreenOffSpan(packageName: String, startMillis: Long, endMillis: Long) {
        val cv = ContentValues().apply { put(HatDatabaseHelper.COL_EVENT_PKG, packageName); put(HatDatabaseHelper.COL_EVENT_START, startMillis); put(HatDatabaseHelper.COL_EVENT_END, endMillis) }
        dbHelper.writableDatabase.insertWithOnConflict(HatDatabaseHelper.TABLE_SCREEN_OFF_DISMISSED, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun restoreScreenOffSpan(packageName: String, startMillis: Long, endMillis: Long) {
        dbHelper.writableDatabase.delete(
            HatDatabaseHelper.TABLE_SCREEN_OFF_DISMISSED,
            "package_name = ? AND start_millis = ? AND end_millis = ?",
            arrayOf(packageName, startMillis.toString(), endMillis.toString())
        )
    }



    /**
     * Persists the raw usage-event stream (schema v4). INSERT OR IGNORE on the
     * (ts, type, package, class) primary key makes the sync idempotent: the
     * 48-hour overlap window re-drains events that are already stored and
     * they simply no-op. Rows from before this field existed do not exist,
     * and old gaps therefore classify as UNDECIDED - the honest answer.
     */
    fun persistRawEvents(events: List<andromedvn.heuristic.activity.tracker.domain.RawEvent>) {
        if (events.isEmpty()) return
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR IGNORE INTO ${HatDatabaseHelper.TABLE_RAW_EVENTS} (ts_utc_ms, type_int, package_name, class_name) VALUES (?, ?, ?, ?)"
            )
            for (e in events) {
                if (e.timestamp <= 0) continue
                stmt.bindLong(1, e.timestamp)
                stmt.bindLong(2, e.type.toLong())
                stmt.bindString(3, e.packageName.ifEmpty { "unknown" })
                if (e.className != null) stmt.bindString(4, e.className) else stmt.bindNull(4)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun pruneRawEvents(olderThanMillis: Long) {
        try {
            dbHelper.writableDatabase.execSQL(
                "DELETE FROM ${HatDatabaseHelper.TABLE_RAW_EVENTS} WHERE ts_utc_ms < ?",
                arrayOf(olderThanMillis)
            )
        } catch (e: Exception) {
            HatLogger.logError("OfflineStorage", "raw event prune failed", e)
        }
    }

    fun loadRawEvents(start: Long, end: Long): List<andromedvn.heuristic.activity.tracker.domain.RawEvent> {
        val out = ArrayList<andromedvn.heuristic.activity.tracker.domain.RawEvent>(512)
        dbHelper.readableDatabase.rawQuery(
            "SELECT ts_utc_ms, type_int, package_name, class_name FROM ${HatDatabaseHelper.TABLE_RAW_EVENTS} WHERE ts_utc_ms >= ? AND ts_utc_ms <= ? ORDER BY ts_utc_ms ASC",
            arrayOf(start.toString(), end.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    andromedvn.heuristic.activity.tracker.domain.RawEvent(
                        type = c.getInt(1),
                        timestamp = c.getLong(0),
                        packageName = c.getString(2) ?: "",
                        className = if (c.isNull(3)) null else c.getString(3)
                    )
                )
            }
        }
        return out
    }

}
