package andromedvn.heuristic.activity.tracker.data

/**
 * Schema SQL, kept as pure functions so it can be unit-tested without a device.
 *
 * HAT deliberately does not use Robolectric (see app/build.gradle.kts), which
 * means the statements themselves cannot be executed by the test suite. What can
 * be pinned is their text - and the text is where the danger lives. The dedupe
 * statement is the only DELETE in HAT's schema code; its MIN($idCol) guard is
 * what makes it non-destructive, so a test asserts that guard is still there.
 * See DatabaseSchemaTest.
 */

/** Idempotent unique index creation. Never modifies rows. */
fun uniqueIndexDdl(indexName: String, tableName: String, cols: List<String>): String =
    "CREATE UNIQUE INDEX IF NOT EXISTS $indexName ON $tableName(${cols.joinToString(", ")})"

/**
 * Removes duplicate rows while keeping exactly one per distinct combination of
 * [cols] - the row with the smallest [idCol].
 *
 * This is a data-destroying statement. It is only ever run from onUpgrade, as a
 * deliberate repair of rows written before a unique index existed. It must never
 * be used as a fallback for a failed CREATE INDEX: that failure can be caused by
 * a full disk, a locked database, or a corrupt page, and destroying a user's
 * entire history because a performance structure could not be built is never the
 * right trade.
 */
fun dedupeRowsSql(tableName: String, idCol: String, cols: List<String>): String {
    val groupCols = cols.joinToString(", ")
    return "DELETE FROM $tableName WHERE $idCol NOT IN (SELECT MIN($idCol) FROM $tableName GROUP BY $groupCols)"
}
