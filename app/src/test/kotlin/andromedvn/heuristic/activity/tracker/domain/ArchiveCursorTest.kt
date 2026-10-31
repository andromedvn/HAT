package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Pins the archive-cursor planning that ended the empty-day stall.
 *
 * The bug this guards (found in the 2026-10-28 persona arena, fixed the same
 * day): the walk restarted from MAX(start_millis) of archived rows, so five
 * or more consecutive zero-usage days re-queried the same days forever and
 * everything after them was lost when Android's retention window arrived.
 * The cursor advances on empty days; these tests pin that it can never wedge.
 */
class ArchiveCursorTest {

    private val tz = TimeZone.getTimeZone("UTC")
    private val originalTz = TimeZone.getDefault()

    @org.junit.Before
    fun pinZone() {
        TimeZone.setDefault(tz)
    }

    @org.junit.After
    fun restoreZone() {
        TimeZone.setDefault(originalTz)
    }

    private fun midnight(day: Int): Long = Calendar.getInstance(tz).apply {
        set(2026, Calendar.SEPTEMBER, day, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `a pass attempts at most five days and never reaches today`() {
        val sep16 = midnight(16)
        val days = ArchiveCursor.passDays(sep16, midnight(30))
        assertEquals(5, days.size)
        assertEquals(sep16, days.first())
        assertTrue(days.last() < midnight(30))
    }

    @Test
    fun `a caught-up cursor attempts nothing`() {
        assertTrue(ArchiveCursor.passDays(midnight(30), midnight(30)).isEmpty())
        assertTrue(ArchiveCursor.passDays(midnight(30), midnight(16)).isEmpty())
    }

    @Test
    fun `the cursor advances across a hole of empty days`() {
        // 12-day hole: pass 1 crosses days 1-5, pass 2 days 6-10, pass 3 days 11-12.
        // Each pass's cursor is the LAST ATTEMPTED day, empty or not - this is
        // the property whose absence wedged the old walk at MAX(start_millis).
        var cursor = midnight(16)
        val startOfToday = midnight(30)
        val passCursors = mutableListOf<Long>()
        var passes = 0
        while (!ArchiveCursor.isCaughtUp(cursor, startOfToday)) {
            val attempted = ArchiveCursor.passDays(cursor, startOfToday)
            assertTrue("pass $passes must attempt days while not caught up", attempted.isNotEmpty())
            cursor = ArchiveCursor.advance(cursor, attempted)
            passCursors.add(cursor)
            passes++
            assertTrue("hole must be crossed in 3 passes, was $passes", passes <= 3)
        }
        assertEquals(listOf(midnight(21), midnight(26), midnight(30)), passCursors)
        assertTrue(ArchiveCursor.isCaughtUp(cursor, startOfToday))
    }

    @Test
    fun `a pass with zero produced rows still moves the cursor`() {
        // The exact wedge: rows are empty (the OS deleted the day), but the
        // ATTEMPTED day list is not. Advance must take the last attempted day.
        val attempted = ArchiveCursor.passDays(midnight(16), midnight(30))
        assertEquals(midnight(21), ArchiveCursor.advance(midnight(16), attempted))
        assertEquals(midnight(16), ArchiveCursor.advance(midnight(16), emptyList()))
    }

    @Test
    fun `a one-day pass boundary never reaches the day in progress`() {
        val sep29 = midnight(29)
        val sep30 = midnight(30)
        val days = ArchiveCursor.passDays(sep29, sep30)
        assertEquals(listOf(sep29), days)
    }

    @Test
    fun `days per pass must be positive`() {
        try {
            ArchiveCursor.passDays(0L, 1L, 0)
            throw AssertionError("expected require to fail")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
