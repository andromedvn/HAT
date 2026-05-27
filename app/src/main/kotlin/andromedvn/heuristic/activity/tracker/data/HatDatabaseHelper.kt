package andromedvn.heuristic.activity.tracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class HatDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    
    init {
        setWriteAheadLoggingEnabled(true)
    }

    companion object {
        private const val DATABASE_NAME = "hat_heuristic.db"
        private const val DATABASE_VERSION = 1

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
    }

    override fun onCreate(db: SQLiteDatabase) {
        ensureTablesExist(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ensureTablesExist(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureTablesExist(db)
    }

    private fun createUniqueIndexSafely(db: SQLiteDatabase, indexName: String, tableName: String, idCol: String, cols: List<String>) {
        try {
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS $indexName ON $tableName(${cols.joinToString(", ")})")
        } catch (e: Exception) {
            try {
                // V.11.8: Duplication Purge. If the index fails, legacy duplicates exist. Purge them.
                val groupCols = cols.joinToString(", ")
                db.execSQL("DELETE FROM $tableName WHERE $idCol NOT IN (SELECT MIN($idCol) FROM $tableName GROUP BY $groupCols)")
                
                // Re-attempt B-Tree injection now that the table is mathematically unique
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS $indexName ON $tableName(${cols.joinToString(", ")})")
            } catch (e2: Exception) {
                // Absolute failsafe to guarantee the app boots even if cleanup fails
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

        // V.11.7 / V.11.8: Native B-Tree Delegation protected by the Duplication Purge Protocol
        createUniqueIndexSafely(db, "idx_archive_unique", TABLE_ARCHIVE, COL_ARCHIVE_ID, listOf(COL_PKG, COL_START, COL_END))
        createUniqueIndexSafely(db, "idx_ignored_unique", TABLE_IGNORED, "id", listOf("package_name", "start_millis", "end_millis"))
        createUniqueIndexSafely(db, "idx_ack_unique", TABLE_ACK_GHOSTS, "id", listOf("package_name", "start_millis", "end_millis"))
    }
}
