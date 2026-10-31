package andromedvn.heuristic.activity.tracker.data

/**
 * The overlap predicate used to read labelled offline time back out of
 * `offline_activities`.
 *
 * WHY THIS EXISTS (bug found and fixed 2026-09-30):
 *
 * The original query bound its two window bounds with `end.toString()` and
 * `start.toString()`, i.e. as SQL TEXT:
 *
 *     timestamp <= ?  AND  (timestamp + duration) >= ?
 *
 * `timestamp` and `duration` are declared INTEGER, but because the bound values
 * arrive as text SQLite did not coerce them the way the author expected, and the
 * predicate matched nothing. Verified against the user's own exported database:
 *
 *     bound as numbers -> 6 rows
 *     bound as text    -> 0 rows
 *
 * So on the build the user was running, a label saved through the overlap predicate
 * was written to disk correctly and then read back as if it did not exist: the gap
 * stayed "Unlabeled Gap" and Total Offline Time read 0m.
 *
 * CORRECTION (2026-09-30): an earlier version of this comment claimed this affected
 * every label HAT ever saved and that the released version (origin/main) was
 * permanently broken. That was WRONG and was reported to the user as fact. The
 * released version reads labels through a bare-column comparison
 * (`timestamp >= ? AND timestamp <= ?`), where INTEGER affinity does apply and the
 * read works. Only the arithmetic conjunct in the *restore* guard at
 * `OfflineStorage.kt:114` lacks affinity. Verified by reading origin/main directly.
 *
 * The fix is to make the numeric intent explicit in SQL with CAST, so the
 * comparison is numeric no matter how the argument is bound. Keeping the
 * predicate in one tested constant means the CAST cannot be quietly dropped.
 *
 * This is deliberately plain Kotlin with no Android imports: the project runs a
 * pure-JVM unit suite (no Robolectric), so the predicate is tested as text and as
 * arithmetic rather than against a live database.
 */
internal object OfflineOverlapSql {

    /** Must keep both `CAST(? AS INTEGER)` casts. Removing either reintroduces the bug. */
    const val PREDICATE: String =
        "timestamp <= CAST(? AS INTEGER) AND (timestamp + duration) >= CAST(? AS INTEGER)"

    /** Bound as strings because SQLiteDatabase.query only accepts Array<String>. */
    fun args(end: Long, start: Long): Array<String> = arrayOf(end.toString(), start.toString())

    /**
     * The by-title variant of [PREDICATE], for the detail / rename / delete
     * paths. Same overlap test, additionally scoped to one label title.
     *
     * WHY (known issue #2, fixed 2026-10-14): the reads that power the
     * dashboard used the overlap predicate, so a label crossing midnight was
     * VISIBLE on its second day - but the by-title read, the rename and the
     * delete still matched on "row starts inside the window", so tapping that
     * visible label did nothing. A label could be seen on day 2 and neither
     * opened, renamed, nor deleted from there.
     *
     * Like [PREDICATE], this lives in one tested constant so the CASTs cannot
     * be quietly dropped by an edit in OfflineStorage.
     */
    const val TITLED_PREDICATE: String = "title = ? AND $PREDICATE"

    /** [title] first, then the same (end, start) order as [args]. */
    fun titledArgs(title: String, end: Long, start: Long): Array<String> =
        arrayOf(title, end.toString(), start.toString())

    /**
     * Pure reference implementation of what the SQL above computes, so the
     * behaviour is pinned in a JVM test even though no database is available.
     * Returns the overlapping slice of [rowStart]..[rowEnd] within the window,
     * or null when there is no overlap.
     */
    fun overlapMillis(rowStart: Long, rowDuration: Long, windowStart: Long, windowEnd: Long): Long? {
        if (rowDuration <= 0L) return null
        val start = maxOf(rowStart, windowStart)
        val end = minOf(rowStart + rowDuration, windowEnd)
        return if (end > start) end - start else null
    }
}
