package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.dedupeRowsSql
import andromedvn.heuristic.activity.tracker.data.uniqueIndexDdl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dedupe statement is the only DELETE in HAT's schema code. It is guarded
 * by MIN($idCol) so that it removes duplicate copies while always keeping one
 * row per group. If that guard is ever dropped or weakened, this statement
 * deletes a user's entire history, which is exactly what happened before
 * (see HatDatabaseHelper.createUniqueIndexSafely).
 */
class DatabaseSchemaTest {

    @Test
    fun `dedupe keeps one row per group via MIN of the id column`() {
        val sql = dedupeRowsSql("app_archive", "id", listOf("pkg", "start", "end"))
        assertTrue(
            "dedupe must select MIN(id) so a row per group survives: $sql",
            sql.contains("MIN(id)")
        )
    }

    @Test
    fun `dedupe deletes from the named table only`() {
        val sql = dedupeRowsSql("app_archive", "id", listOf("pkg"))
        assertTrue(sql.trimStart().startsWith("DELETE FROM app_archive"))
        assertEquals(1, sql.split("DELETE FROM").size - 1)
    }

    @Test
    fun `dedupe groups by every supplied column`() {
        val sql = dedupeRowsSql("app_ignored", "id", listOf("package_name", "start_millis", "end_millis"))
        assertTrue(sql.contains("GROUP BY package_name, start_millis, end_millis"))
    }

    @Test
    fun `dedupe is not destructive when the id column is the grouping column`() {
        // Degenerate case: grouping by id alone can never have duplicates, so the
        // statement must still be well formed rather than deleting everything.
        val sql = dedupeRowsSql("t", "id", listOf("id"))
        assertTrue(sql.contains("NOT IN (SELECT MIN(id) FROM t GROUP BY id)"))
    }

    @Test
    fun `unique index ddl is idempotent`() {
        val ddl = uniqueIndexDdl("idx_archive_unique", "app_archive", listOf("pkg", "start", "end"))
        assertTrue(ddl.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS idx_archive_unique"))
        assertTrue(ddl.endsWith("app_archive(pkg, start, end)"))
    }

    @Test
    fun `ddl builders never emit a destructive statement`() {
        val ddl = uniqueIndexDdl("i", "t", listOf("a"))
        assertTrue("index DDL must not modify data: $ddl", !ddl.contains("DELETE", ignoreCase = true))
        assertTrue(!ddl.contains("DROP", ignoreCase = true))
        assertTrue(!ddl.contains("UPDATE", ignoreCase = true))
    }
}
