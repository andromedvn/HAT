package andromedvn.heuristic.activity.tracker.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
    private val SETTINGS_KEY = stringPreferencesKey("user_settings")
    private val HIDDEN_APPS_KEY = stringPreferencesKey("hidden_apps")
    
    private val json = Json { ignoreUnknownKeys = true }
    private val storageScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    val dbHelper = HatDatabaseHelper(context)

    val settings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        prefs[SETTINGS_KEY]?.let { try { json.decodeFromString(it) } catch(e: Exception) { UserSettings() } } ?: UserSettings()
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

    fun checkpointDatabase() {
        try { dbHelper.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } } catch (e: Exception) {}
    }

    fun getDatabaseFile(): File = context.getDatabasePath("hat_heuristic.db")

    suspend fun mergeDatabase(importedDbFile: File): Boolean {
        val currentDb = dbHelper.writableDatabase
        var importedDb: SQLiteDatabase? = null
        var success = false

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
                    currentDb.beginTransaction()
                    try {
                        while (it.moveToNext()) {
                            val ts = it.getLong(timeIdx)
                            val dur = it.getLong(durIdx)
                            val end = ts + dur

                            val checkCur = currentDb.rawQuery(
                                "SELECT 1 FROM ${HatDatabaseHelper.TABLE_OFFLINE} WHERE ${HatDatabaseHelper.COL_TIMESTAMP} < ? AND ${HatDatabaseHelper.COL_TIMESTAMP} + ${HatDatabaseHelper.COL_DURATION} > ? LIMIT 1",
                                arrayOf(end.toString(), ts.toString())
                            )
                            val hasOverlap = checkCur.moveToFirst()
                            checkCur.close()
                            
                            if (!hasOverlap) {
                                val cv = ContentValues().apply {
                                    put(HatDatabaseHelper.COL_ID, it.getLong(idIdx))
                                    put(HatDatabaseHelper.COL_TITLE, it.getString(titleIdx))
                                    put(HatDatabaseHelper.COL_DURATION, dur)
                                    put(HatDatabaseHelper.COL_TIMESTAMP, ts)
                                    put(HatDatabaseHelper.COL_ICON, it.getString(iconIdx))
                                }
                                currentDb.insertWithOnConflict(HatDatabaseHelper.TABLE_OFFLINE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                            } else {
                                HatLogger.logError("OfflineStorage", "Vault Merge: Skipped imported offline activity due to temporal overlap.")
                            }
                            
                            count++
                            if (count % 500 == 0) {
                                currentDb.setTransactionSuccessful()
                                currentDb.endTransaction()
                                yield()
                                currentDb.beginTransaction()
                            }
                        }
                        currentDb.setTransactionSuccessful()
                    } finally {
                        if (currentDb.inTransaction()) currentDb.endTransaction()
                    }
                }
            } catch (e: Exception) { HatLogger.logError("OfflineStorage", "Legacy Merge: Offline Table missing", e) }

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
                            if (count % 500 == 0) {
                                currentDb.setTransactionSuccessful()
                                currentDb.endTransaction()
                                yield()
                                currentDb.beginTransaction()
                            }
                        }
                        currentDb.setTransactionSuccessful()
                    } finally {
                        if (currentDb.inTransaction()) currentDb.endTransaction()
                    }
                }
            } catch (e: Exception) { HatLogger.logError("OfflineStorage", "Legacy Merge: Archive Table missing", e) }

            try {
                val curIgnored = importedDb.query(HatDatabaseHelper.TABLE_IGNORED, arrayOf("package_name", "start_millis", "end_millis"), null, null, null, null, null)
                curIgnored.use {
                    val pkgIdx = it.getColumnIndexOrThrow("package_name")
                    val sIdx = it.getColumnIndexOrThrow("start_millis")
                    val eIdx = it.getColumnIndexOrThrow("end_millis")
                    
                    var count = 0
                    currentDb.beginTransaction()
                    try {
                        while (it.moveToNext()) {
                            val cv = ContentValues().apply { put("package_name", it.getString(pkgIdx)); put("start_millis", it.getLong(sIdx)); put("end_millis", it.getLong(eIdx)) }
                            currentDb.insertWithOnConflict(HatDatabaseHelper.TABLE_IGNORED, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                            
                            count++
                            if (count % 500 == 0) {
                                currentDb.setTransactionSuccessful()
                                currentDb.endTransaction()
                                yield()
                                currentDb.beginTransaction()
                            }
                        }
                        currentDb.setTransactionSuccessful()
                    } finally {
                        if (currentDb.inTransaction()) currentDb.endTransaction()
                    }
                }
            } catch (e: Exception) { HatLogger.logError("OfflineStorage", "Legacy Merge: Ignored Table missing (Skipping)", e) }

            try {
                val curAck = importedDb.query(HatDatabaseHelper.TABLE_ACK_GHOSTS, arrayOf("package_name", "start_millis", "end_millis"), null, null, null, null, null)
                curAck.use {
                    val pkgIdx = it.getColumnIndexOrThrow("package_name")
                    val sIdx = it.getColumnIndexOrThrow("start_millis")
                    val eIdx = it.getColumnIndexOrThrow("end_millis")
                    
                    var count = 0
                    currentDb.beginTransaction()
                    try {
                        while (it.moveToNext()) {
                            val cv = ContentValues().apply { put("package_name", it.getString(pkgIdx)); put("start_millis", it.getLong(sIdx)); put("end_millis", it.getLong(eIdx)) }
                            currentDb.insertWithOnConflict(HatDatabaseHelper.TABLE_ACK_GHOSTS, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                            
                            count++
                            if (count % 500 == 0) {
                                currentDb.setTransactionSuccessful()
                                currentDb.endTransaction()
                                yield()
                                currentDb.beginTransaction()
                            }
                        }
                        currentDb.setTransactionSuccessful()
                    } finally {
                        if (currentDb.inTransaction()) currentDb.endTransaction()
                    }
                }
            } catch (e: Exception) { HatLogger.logError("OfflineStorage", "Legacy Merge: Ack Table missing (Skipping)", e) }

            success = true
        } catch (e: android.database.sqlite.SQLiteException) {
            HatLogger.logError("OfflineStorage", "Vault Merge Failed: Imported file is malformed or not a valid SQLite database.", e)
        } catch (e: Exception) {
            HatLogger.logError("OfflineStorage", "Exception during Time-Stitch Database Merge", e)
        } finally {
            importedDb?.close()
        }
        
        return success
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
                        } catch (e: Exception) {} 
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
                        } catch (e: Exception) {} 
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
                } catch(e: Exception) {}
            }
        }
    }

    suspend fun clearAllData() {
        val db = dbHelper.writableDatabase
        try { db.execSQL("DELETE FROM ${HatDatabaseHelper.TABLE_OFFLINE}") } catch (e: Exception) {}
        try { db.execSQL("DELETE FROM ${HatDatabaseHelper.TABLE_ARCHIVE}") } catch (e: Exception) {}
        try { db.execSQL("DELETE FROM ${HatDatabaseHelper.TABLE_IGNORED}") } catch (e: Exception) {}
        try { db.execSQL("DELETE FROM ${HatDatabaseHelper.TABLE_ACK_GHOSTS}") } catch (e: Exception) {}
        context.dataStore.edit { prefs -> prefs.remove(HIDDEN_APPS_KEY) }
    }

    suspend fun saveSettings(newSettings: UserSettings) { 
        context.dataStore.edit { it[SETTINGS_KEY] = json.encodeToString(newSettings) } 
    }

    fun addActivityAtomic(activity: OfflineActivityEntity) { 
        insertActivityInternal(dbHelper.writableDatabase, activity) 
    }

    fun saveActivities(list: List<OfflineActivityEntity>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try { list.forEach { insertActivityInternal(db, it) }; db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    fun deleteOfflineActivitiesByTitle(title: String, start: Long, end: Long) {
        dbHelper.writableDatabase.delete(HatDatabaseHelper.TABLE_OFFLINE, "${HatDatabaseHelper.COL_TITLE} = ? AND ${HatDatabaseHelper.COL_TIMESTAMP} >= ? AND ${HatDatabaseHelper.COL_TIMESTAMP} <= ?", arrayOf(title, start.toString(), end.toString()))
    }

    fun updateOfflineActivitiesByTitle(oldTitle: String, newTitle: String, newIconName: String, start: Long, end: Long) {
        val cv = ContentValues().apply { put(HatDatabaseHelper.COL_TITLE, newTitle); put(HatDatabaseHelper.COL_ICON, newIconName) }
        dbHelper.writableDatabase.update(HatDatabaseHelper.TABLE_OFFLINE, cv, "${HatDatabaseHelper.COL_TITLE} = ? AND ${HatDatabaseHelper.COL_TIMESTAMP} >= ? AND ${HatDatabaseHelper.COL_TIMESTAMP} <= ?", arrayOf(oldTitle, start.toString(), end.toString()))
    }

    suspend fun saveHiddenPackages(packages: Set<String>) { 
        context.dataStore.edit { it[HIDDEN_APPS_KEY] = json.encodeToString(packages) } 
    }

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

    private fun mapCursorToActivity(cursor: Cursor): OfflineActivityEntity = OfflineActivityEntity(id = cursor.getLong(cursor.getColumnIndexOrThrow(HatDatabaseHelper.COL_ID)), title = cursor.getString(cursor.getColumnIndexOrThrow(HatDatabaseHelper.COL_TITLE)), durationInMillis = cursor.getLong(cursor.getColumnIndexOrThrow(HatDatabaseHelper.COL_DURATION)), timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(HatDatabaseHelper.COL_TIMESTAMP)), colorHex = 0L, iconName = cursor.getString(cursor.getColumnIndexOrThrow(HatDatabaseHelper.COL_ICON)))

    fun getOfflineActivitiesBetween(start: Long, end: Long): List<OfflineActivityEntity> {
        val list = mutableListOf<OfflineActivityEntity>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(HatDatabaseHelper.TABLE_OFFLINE, null, "${HatDatabaseHelper.COL_TIMESTAMP} >= ? AND ${HatDatabaseHelper.COL_TIMESTAMP} <= ?", arrayOf(start.toString(), end.toString()), null, null, "${HatDatabaseHelper.COL_TIMESTAMP} ASC")
        cursor.use { while (it.moveToNext()) list.add(mapCursorToActivity(it)) }
        return list
    }

    fun getOfflineActivitiesByTitleBetween(title: String, start: Long, end: Long): List<OfflineActivityEntity> {
        val list = mutableListOf<OfflineActivityEntity>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(HatDatabaseHelper.TABLE_OFFLINE, null, "${HatDatabaseHelper.COL_TITLE} = ? AND ${HatDatabaseHelper.COL_TIMESTAMP} >= ? AND ${HatDatabaseHelper.COL_TIMESTAMP} <= ?", arrayOf(title, start.toString(), end.toString()), null, null, "${HatDatabaseHelper.COL_TIMESTAMP} ASC")
        cursor.use { while (it.moveToNext()) list.add(mapCursorToActivity(it)) }
        return list
    }

    fun getAllOfflineActivities(): List<OfflineActivityEntity> = getOfflineActivitiesBetween(0L, Long.MAX_VALUE)

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
            val pkgIdx = it.getColumnIndexOrThrow("package_name")
            val sIdx = it.getColumnIndexOrThrow("start_millis")
            val eIdx = it.getColumnIndexOrThrow("end_millis")
            while(it.moveToNext()) list.add(IgnoredSession(it.getString(pkgIdx), it.getLong(sIdx), it.getLong(eIdx)))
        }
        return list
    }
    
    fun getIgnoredSessionsBetween(startMillis: Long, endMillis: Long): List<IgnoredSession> {
        val list = mutableListOf<IgnoredSession>()
        dbHelper.readableDatabase.query(
            HatDatabaseHelper.TABLE_IGNORED, null,
            "end_millis >= ? AND start_millis <= ?",
            arrayOf(startMillis.toString(), endMillis.toString()),
            null, null, null
        ).use {
            val pkgIdx = it.getColumnIndexOrThrow("package_name")
            val sIdx = it.getColumnIndexOrThrow("start_millis")
            val eIdx = it.getColumnIndexOrThrow("end_millis")
            while(it.moveToNext()) list.add(IgnoredSession(it.getString(pkgIdx), it.getLong(sIdx), it.getLong(eIdx)))
        }
        return list
    }

    fun addIgnoredSession(session: IgnoredSession) {
        val cv = ContentValues().apply { put("package_name", session.packageName); put("start_millis", session.startMillis); put("end_millis", session.endMillis) }
        dbHelper.writableDatabase.insertWithOnConflict(HatDatabaseHelper.TABLE_IGNORED, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removeIgnoredSessionExact(startMillis: Long, endMillis: Long) {
        dbHelper.writableDatabase.delete(HatDatabaseHelper.TABLE_IGNORED, "start_millis = ? AND end_millis = ?", arrayOf(startMillis.toString(), endMillis.toString()))
    }

    fun getAllAcknowledgedGhosts(): List<AcknowledgedGhost> {
        val list = mutableListOf<AcknowledgedGhost>()
        dbHelper.readableDatabase.query(HatDatabaseHelper.TABLE_ACK_GHOSTS, null, null, null, null, null, null).use {
            val pkgIdx = it.getColumnIndexOrThrow("package_name")
            val sIdx = it.getColumnIndexOrThrow("start_millis")
            val eIdx = it.getColumnIndexOrThrow("end_millis")
            while(it.moveToNext()) list.add(AcknowledgedGhost(it.getString(pkgIdx), it.getLong(sIdx), it.getLong(eIdx)))
        }
        return list
    }
    
    fun getAcknowledgedGhostsBetween(startMillis: Long, endMillis: Long): List<AcknowledgedGhost> {
        val list = mutableListOf<AcknowledgedGhost>()
        dbHelper.readableDatabase.query(
            HatDatabaseHelper.TABLE_ACK_GHOSTS, null, 
            "end_millis >= ? AND start_millis <= ?", 
            arrayOf(startMillis.toString(), endMillis.toString()), 
            null, null, null
        ).use {
            val pkgIdx = it.getColumnIndexOrThrow("package_name")
            val sIdx = it.getColumnIndexOrThrow("start_millis")
            val eIdx = it.getColumnIndexOrThrow("end_millis")
            while(it.moveToNext()) list.add(AcknowledgedGhost(it.getString(pkgIdx), it.getLong(sIdx), it.getLong(eIdx)))
        }
        return list
    }

    fun addAcknowledgedGhost(ghost: AcknowledgedGhost) {
        val cv = ContentValues().apply { put("package_name", ghost.packageName); put("start_millis", ghost.startMillis); put("end_millis", ghost.endMillis) }
        dbHelper.writableDatabase.insertWithOnConflict(HatDatabaseHelper.TABLE_ACK_GHOSTS, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    // V.15.1: Ghost Recovery Matrix 
    fun removeAcknowledgedGhost(packageName: String, startMillis: Long, endMillis: Long) {
        dbHelper.writableDatabase.delete(
            HatDatabaseHelper.TABLE_ACK_GHOSTS, 
            "package_name = ? AND start_millis = ? AND end_millis = ?", 
            arrayOf(packageName, startMillis.toString(), endMillis.toString())
        )
    }

    private fun clearIgnoredAndAckGhosts() {
        try { dbHelper.writableDatabase.execSQL("DELETE FROM ${HatDatabaseHelper.TABLE_IGNORED}") } catch (e: Exception) {}
        try { dbHelper.writableDatabase.execSQL("DELETE FROM ${HatDatabaseHelper.TABLE_ACK_GHOSTS}") } catch (e: Exception) {}
    }
}
