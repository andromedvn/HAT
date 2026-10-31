package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duplicate-label DETECTION rules. (2026-10-01)
 *
 * These mirror the grouping in `OfflineStorage.findDuplicateOfflineLabels()`.
 *
 * They do NOT execute production SQL, and that limitation is the point of this header:
 * a previous version of the guard tests mirrored an intended rule, passed green, while
 * the production `insertOrReplaceLabel` destroyed a distinct label. A mirror test proves
 * the *rule is coherent*, never that the *code implements it*. The detection logic is
 * therefore deliberately pure and total - grouping by an exact (start, end) window key
 * with no UPDATE anywhere - so there is no write path left to get wrong.
 *
 * The user's decision these encode: duplicates are SHOWN so the user can act. Nothing
 * deletes anything on its own.
 */
class DuplicateLabelDetectionTest {

    private data class Row(val id: Long, val title: String, val startMillis: Long, val durationMillis: Long) {
        val endMillis: Long get() = startMillis + durationMillis
    }

    private data class Group(val startMillis: Long, val endMillis: Long, val ids: List<Long>, val title: String)

    /** Mirror of the production grouping: bucket by exact window, report size >= 2. */
    private fun detect(rows: List<Row>): List<Group> =
        rows.groupBy { it.startMillis to it.endMillis }
            .filterValues { it.size >= 2 }
            .map { (window, entries) ->
                Group(window.first, window.second, entries.map { it.id }, entries.first().title)
            }

    @Test
    fun identicalRowsForOneWindowAreReported() {
        // The real case: six copies of one 3h28m gap.
        val rows = (1L..6L).map { Row(it, "Sleep", 1_000L, 500L) }
        val found = detect(rows)
        assertEquals(1, found.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), found.single().ids)
        assertEquals(1_000L, found.single().startMillis)
        assertEquals(1_500L, found.single().endMillis)
    }

    @Test
    fun twoGapsSharingTheSameWordAreNotDuplicates() {
        // Two naps called "Sleep" at different times. Both are the user's real data.
        val rows = listOf(
            Row(1, "Sleep", 1_000L, 3_600_000L),
            Row(2, "Sleep", 90_000L, 1_800_000L)
        )
        assertTrue(detect(rows).isEmpty())
    }

    @Test
    fun sameStartButDifferentEndIsNotADuplicate() {
        // 1h Work and 3h Sleep that happen to begin together. Two real windows.
        val rows = listOf(
            Row(1, "Work", 0L, 3_600_000L),
            Row(2, "Sleep", 0L, 10_800_000L)
        )
        assertTrue(detect(rows).isEmpty())
    }

    @Test
    fun sameWindowButDifferentWordsIsReportedNotDeleted() {
        // The case that destroyed data under the old UPDATE-based code. Detection must
        // surface it; it must NOT silently pick a winner.
        val rows = listOf(
            Row(1, "Sleep", 1_000L, 500L),
            Row(2, "Sick leave", 1_000L, 500L)
        )
        val found = detect(rows)
        assertEquals(1, found.size)
        // Both ids are reported so the user sees both entries and chooses.
        assertEquals(setOf(1L, 2L), found.single().ids.toSet())
    }

    @Test
    fun detectionNeverChoosesOrRemovesAWinner() {
        // Property check over mixed input: detection reports and mutates nothing.
        //
        // The invariant is NOT "every row appears in some group" - a lone label has no
        // duplicate partner and is correctly neither reported nor touched. The invariant
        // is that detection never *changes* anything, and never invents a winner among
        // the rows it does report.
        val rows = listOf(
            Row(1, "Sleep", 1_000L, 500L),
            Row(2, "sleep", 1_000L, 500L),
            Row(3, "Work", 0L, 3_600_000L),
            Row(4, "Read", 9_000L, 600_000L)
        )
        val before = rows.map { it.copy() }
        val found = detect(rows)

        // (a) input is untouched - detection is a pure read.
        assertEquals(before, rows)

        // (b) every REPORTED id is a real row, and all rows sharing a window are
        //     reported together - so the user always sees the full set, never a
        //     subset that looks like a single entry.
        val ids = rows.map { it.id }.toSet()
        assertTrue(found.flatMap { it.ids }.all { it in ids })
        for (g in found) {
            val sharingWindow = rows.filter { it.startMillis == g.startMillis && it.endMillis == g.endMillis }
            assertEquals(sharingWindow.map { it.id }.toSet(), g.ids.toSet())
        }

        // (c) the two Sleep rows are the pair; Work and Read are untouched singletons.
        assertEquals(setOf(1L, 2L), found.single().ids.toSet())
        assertTrue(rows.filter { it.id in setOf(3L, 4L) }.isNotEmpty())
    }

    @Test
    fun aUsersOwnUnusualWordIsNeverSingledOut() {
        // 'hear' was typed by the user. Detection keys on TIME only and must never
        // mention, flag, or prefer a row because of what it is called.
        val lone = Row(9, "hear", 1_000L, 500L)
        assertTrue(detect(listOf(lone)).isEmpty())
    }

    @Test
    fun threeIdenticalPlusOneDistinctYieldsTwoGroups() {
        val rows = listOf(
            Row(1, "Sleep", 1_000L, 500L),
            Row(2, "Sleep", 1_000L, 500L),
            Row(3, "Sleep", 1_000L, 500L),
            Row(4, "Read", 7_000L, 500L),
            Row(5, "Read", 7_000L, 500L)
        )
        val found = detect(rows)
        assertEquals(2, found.size)
        assertEquals(setOf(1L, 2L, 3L), found.first { it.startMillis == 1_000L }.ids.toSet())
        assertEquals(setOf(4L, 5L), found.first { it.startMillis == 7_000L }.ids.toSet())
    }
}
