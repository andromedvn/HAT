package andromedvn.heuristic.activity.tracker.domain

/**
 * Pure planning for the archive walk (2026-10-28).
 *
 * The old walk restarted from MAX(start_millis) of archived rows every pass.
 * A day with zero usage inserts no rows, so five or more consecutive empty
 * days (a dead-battery weekend, an airplane flight, an OEM freezing the app)
 * re-queried the same empty days forever: the watermark never moved, and
 * every day after the hole stayed unarchived until Android's retention
 * window deleted it. The fix is a cursor that records ATTEMPTED days - it
 * advances on empty days too, so the walk can never wedge.
 *
 * Pure and JVM-testable, like IntervalMath: no Android types, no clock.
 */
object ArchiveCursor {

    const val DAY_MILLIS: Long = 86_400_000L

    /**
     * The local midnights one pass attempts: [cursor, cursor + daysPerPass),
     * never reaching [startOfToday]. Day steps go through [LocalDay.nextLocalMidnight]
     * so a 23- or 25-hour DST day is walked the way the calendar says it is,
     * exactly like the repository walk it plans.
     */
    fun passDays(cursor: Long, startOfToday: Long, daysPerPass: Int = 5): List<Long> {
        require(daysPerPass > 0) { "daysPerPass must be positive, was $daysPerPass" }
        val out = ArrayList<Long>(daysPerPass)
        var day = cursor
        var n = 0
        while (day < startOfToday && n < daysPerPass) {
            out.add(day)
            day = nextLocalMidnight(day)
            n++
        }
        return out
    }

    /**
     * The new cursor after a pass that attempted [attempted]: the midnight
     * AFTER the last attempted day - i.e. the next day to attempt. Empty days
     * ADVANCE the cursor (that is the whole point); only a pass that
     * attempted nothing leaves it alone. Returning the next day rather than
     * the last attempted one means the following pass never re-queries a day
     * it has already walked.
     */
    fun advance(cursor: Long, attempted: List<Long>): Long =
        attempted.lastOrNull()?.let { nextLocalMidnight(it) } ?: cursor

    /** True when the walk has nothing left before today. */
    fun isCaughtUp(cursor: Long, startOfToday: Long): Boolean = cursor >= startOfToday
}
