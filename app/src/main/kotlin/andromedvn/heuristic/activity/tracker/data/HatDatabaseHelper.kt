package andromedvn.heuristic.activity.tracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import andromedvn.heuristic.activity.tracker.utils.HatLogger

class HatDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    
    init {
        setWriteAheadLoggingEnabled(true)
    }

    companion object {
        private const val DATABASE_NAME = "hat_heuristic.db"

        /**
         * 3 (2026-10-16): added `screen_off_dismissed`. A dismissed screen-off
         * span is one the user said "this really was usage" about; the engine
         * merges it back into that app's usage totals. Version 2's upgrade
         * never shipped publicly (all published releases were debug-signed),
         * but this phone already runs a version-2 database, so the step is
         * kept distinct and the creation is idempotent either way.
         */
        /**
         * 4 (2026-11-02): added `raw_usage_events`. The archive stores
         * intervals only, which is structurally blind to the events the
         * presence work needs (screen state, keyguard, USER_INTERACTION,
         * foreground services). From version 4 the sync persists the raw
         * stream as it is drained, so every future day carries the full
         * evidence. Old days stay interval-only and honestly classify as
         * UNDECIDED.
         */
        private const val DATABASE_VERSION = 5

        const val TABLE_OFFLINE = "offline_activities"
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_DURATION = "duration"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_ICON = "icon_name"

        const val TABLE_ARCHIVE = "archived_app_usage"
        const val COL_ARCHIVE_ID = "id"
        const val COL_PKG = "package_name"
        const val COL_START = "start_millis"
        const val COL_END = "end_millis"

        const val TABLE_IGNORED = "ignored_sessions"
        const val TABLE_ACK_GHOSTS = "ack_ghosts"
        const val TABLE_SCREEN_OFF_DISMISSED = "screen_off_dismissed"
        const val TABLE_RAW_EVENTS = "raw_usage_events"
    }

    override fun onCreate(db: SQLiteDatabase) {
        ensureTablesExist(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ensureTablesExist(db)
        // v5: idle detection was removed entirely (r9). These two tables were
        // its suppression record; stale rows still carved real usage out of
        // the interval cache and manufactured phantom gaps. One deliberate
        // wipe, then the feature is fully gone.
        if (oldVersion < 5) {
            try { db.execSQL("DELETE FROM ${TABLE_IGNORED}") } catch (e: Exception) { }
            try { db.execSQL("DELETE FROM ${TABLE_ACK_GHOSTS}") } catch (e: Exception) { }
        }
        // Legacy rows can predate the unique indexes. Repair them once, here,
        // where a destructive statement is a deliberate choice rather than a
        // side effect of something unrelated failing.
        purgeDuplicateRows(db)
        // Indexes may have failed to build on a previous run; try again now
        // that any duplicates are gone.
        ensureTablesExist(db)
    }

    // Deliberately does NOT call ensureTablesExist(). Tables and indexes are
    // created in onCreate and onUpgrade. Re-running ~9 CREATE ... IF NOT EXISTS
    // statements on every single database open was pure overhead, and it meant
    // the (previously destructive) index path ran on every launch.
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
    }

    private fun createUniqueIndexSafely(db: SQLiteDatabase, indexName: String, tableName: String, cols: List<String>) {
        try {
            db.execSQL(uniqueIndexDdl(indexName, tableName, cols))
        } catch (e: Exception) {
            // A unique index is a performance and integrity aid, not a
            // correctness requirement: reads and writes stay correct without
            // it, only lookups get slower.
            //
            // This used to fall back to deleting every row that was not the
            // MIN(id) of its group, and re-attempt the index. That meant a
            // transient failure - a full disk, a briefly locked database, a
            // corrupt page - silently destroyed user history, and because
            // ensureTablesExist() runs from onOpen() it could do so on any app
            // launch. Duplicates are now repaired deliberately in onUpgrade,
            // never as a side effect of a failed DDL statement.
            HatLogger.logError("HatDatabaseHelper", "Could not create index $indexName; continuing without it", e)
        }
    }

    /**
     * Removes rows that predate the unique indexes, so the indexes can be built.
     * Runs once per upgrade - never as a recovery path, and never on app open.
     */
    private fun purgeDuplicateRows(db: SQLiteDatabase) {
        val targets = listOf(
            Triple(HatDatabaseHelper.TABLE_ARCHIVE, HatDatabaseHelper.COL_ARCHIVE_ID, listOf(HatDatabaseHelper.COL_PKG, HatDatabaseHelper.COL_START, HatDatabaseHelper.COL_END)),
            Triple(HatDatabaseHelper.TABLE_IGNORED, "id", listOf("package_name", "start_millis", "end_millis")),
            Triple(HatDatabaseHelper.TABLE_ACK_GHOSTS, "id", listOf("package_name", "start_millis", "end_millis")),
            Triple(HatDatabaseHelper.TABLE_SCREEN_OFF_DISMISSED, "id", listOf("package_name", "start_millis", "end_millis"))
        )
        for ((table, idCol, cols) in targets) {
            try {
                db.execSQL(dedupeRowsSql(table, idCol, cols))
            } catch (e: Exception) {
                HatLogger.logError("HatDatabaseHelper", "Duplicate purge failed on $table", e)
            }
        }
    }

    private fun ensureTablesExist(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_OFFLINE (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_TITLE TEXT NOT NULL,
                $COL_DURATION INTEGER NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_ICON TEXT NOT NULL
            )
        """.trimIndent())
        
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_offline_timestamp ON $TABLE_OFFLINE($COL_TIMESTAMP)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_offline_title_timestamp ON $TABLE_OFFLINE($COL_TITLE, $COL_TIMESTAMP)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_ARCHIVE (
                $COL_ARCHIVE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PKG TEXT NOT NULL,
                $COL_START INTEGER NOT NULL,
                $COL_END INTEGER NOT NULL
            )
        """.trimIndent())
        
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_archive_pkg_time ON $TABLE_ARCHIVE($COL_PKG, $COL_START, $COL_END)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_archive_start ON $TABLE_ARCHIVE($COL_START)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_IGNORED (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                start_millis INTEGER NOT NULL,
                end_millis INTEGER NOT NULL
            )
        """.trimIndent())
        
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_ACK_GHOSTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                start_millis INTEGER NOT NULL,
                end_millis INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SCREEN_OFF_DISMISSED (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                start_millis INTEGER NOT NULL,
                end_millis INTEGER NOT NULL
            )
        """.trimIndent())

        createUniqueIndexSafely(db, "idx_archive_unique", TABLE_ARCHIVE, listOf(COL_PKG, COL_START, COL_END))
        createUniqueIndexSafely(db, "idx_ignored_unique", TABLE_IGNORED, listOf("package_name", "start_millis", "end_millis"))
        createUniqueIndexSafely(db, "idx_ack_unique", TABLE_ACK_GHOSTS, listOf("package_name", "start_millis", "end_millis"))
        createUniqueIndexSafely(db, "idx_screen_off_dismissed_unique", TABLE_SCREEN_OFF_DISMISSED, listOf("package_name", "start_millis", "end_millis"))

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_RAW_EVENTS (
                ts_utc_ms INTEGER NOT NULL,
                type_int INTEGER NOT NULL,
                package_name TEXT NOT NULL,
                class_name TEXT,
                PRIMARY KEY (ts_utc_ms, type_int, package_name, class_name)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_events_ts ON $TABLE_RAW_EVENTS(ts_utc_ms)")
    }
}
