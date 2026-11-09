package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.OfflineOverlapSql
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the offline-label read bug found 2026-09-30.
 *
 * HAT saved offline labels to `offline_activities` perfectly, but the query that
 * reads them back bound its bounds as SQL TEXT, so it never matched a row. Proof
 * from the user's own exported database, same file, same SQL:
 *
 *     bound as numbers -> 6 rows
 *     bound as text    -> 0 rows
 *
 * The user-visible effect was that a gap could be labelled and would still show
 * "Unlabeled Gap" with Total Offline Time stuck at 0m.
 *
 * This suite is pure JVM on purpose: the project's unit tests run without
 * Robolectric, so the predicate is pinned as text and the overlap arithmetic is
 * pinned directly. The device run is the behavioural proof.
 */
class OfflineQueryBindingTest {

    // Real values from the user's vault: a 3h 28m label starting 01:07:46,
    // inside a day window of 00:00:00 to 23:59:59.
    private val labelStart = 1_790_701_666_838L
    private val labelDuration = 12_501_018L
    private val dayStart = 1_790_697_600_000L
    private val dayEnd = 1_790_783_999_999L

    @Test
    fun predicateCastsBothBoundsToInteger() {
        // If either CAST is dropped the read bug returns.
        assertEquals(2, Regex("CAST\\(\\? AS INTEGER\\)").findAll(OfflineOverlapSql.PREDICATE).count())
    }

    @Test
    fun predicateComparesNumerically() {
        val p = OfflineOverlapSql.PREDICATE
        assertTrue("start-of-row bound", p.contains("timestamp <= CAST(? AS INTEGER)"))
        assertTrue("end-of-row bound", p.contains("(timestamp + duration) >= CAST(? AS INTEGER)"))
    }

    @Test
    fun argsAreBoundAsStringsBecauseTheApiOnlyAcceptsThem() {
        assertArrayEquals(arrayOf(dayEnd.toString(), dayStart.toString()), OfflineOverlapSql.args(dayEnd, dayStart))
    }

    @Test
    fun labelInsideDayIsFullyOverlapping() {
        // This is the row that used to be invisible.
        assertEquals(
            labelDuration,
            OfflineOverlapSql.overlapMillis(labelStart, labelDuration, dayStart, dayEnd)
        )
    }

    @Test
    fun labelCrossingMidnightIsClippedPerDay() {
        val midnight = dayStart
        val startsBefore = midnight - 3_600_000L   // 23:00 previous day
        val runsFor = 7_200_000L                  // 2h, so it ends 01:00 today
        // Only the part inside today counts: 01:00 - 00:00 = 1 hour.
        assertEquals(
            3_600_000L,
            OfflineOverlapSql.overlapMillis(startsBefore, runsFor, midnight, dayEnd)
        )
    }

    @Test
    fun rowEntirelyOutsideWindowIsNotMatched() {
        assertNull(OfflineOverlapSql.overlapMillis(dayEnd + 60_000L, 60_000L, dayStart, dayEnd))
        assertNull(OfflineOverlapSql.overlapMillis(dayStart - 60_000L, 30_000L, dayStart, dayEnd))
    }

    @Test
    fun nonPositiveDurationNeverMatches() {
        assertNull(OfflineOverlapSql.overlapMillis(labelStart, 0L, dayStart, dayEnd))
        assertNull(OfflineOverlapSql.overlapMillis(labelStart, -5L, dayStart, dayEnd))
    }

    // --- the by-title paths (known issue #2, fixed 2026-10-14) --------------

    @Test
    fun titledPredicateKeepsBothCasts() {
        // The detail / rename / delete paths flow through TITLED_PREDICATE; if
        // either CAST is dropped there, the read bug returns through the
        // second door, a midnight-crossing label visible on day 2 but not
        // openable, renamable or deletable from there.
        assertEquals(2, Regex("CAST\\(\\? AS INTEGER\\)").findAll(OfflineOverlapSql.TITLED_PREDICATE).count())
    }

    @Test
    fun titledPredicateScopesToTitleAndTestsOverlap() {
        val p = OfflineOverlapSql.TITLED_PREDICATE
        assertTrue("title scoping comes first", p.startsWith("title = ? AND "))
        assertTrue("then the same overlap predicate as the reads", p.endsWith(OfflineOverlapSql.PREDICATE))
    }

    @Test
    fun titledArgsBindTitleThenEndThenStart() {
        assertArrayEquals(
            arrayOf("Sleep", dayEnd.toString(), dayStart.toString()),
            OfflineOverlapSql.titledArgs("Sleep", dayEnd, dayStart)
        )
    }
}
