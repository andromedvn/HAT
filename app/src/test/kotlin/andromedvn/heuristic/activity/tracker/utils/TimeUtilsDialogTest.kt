package andromedvn.heuristic.activity.tracker.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Boundaries for the gap dialog's DURATION seconds, the day words
 * (today / yesterday / then the date), and the midnight-crossing span note.
 * A fixed "now" keeps every case deterministic.
 */
class TimeUtilsDialogTest {

    private fun cal(year: Int, month: Int, day: Int, hour: Int, min: Int, sec: Int = 0): Calendar =
        Calendar.getInstance(Locale.US).apply {
            clear()
            set(year, month - 1, day, hour, min, sec)
        }

    // Fixed now: Nov 8 2026, 21:15 local.
    private val now: Long = cal(2026, 11, 8, 21, 15).timeInMillis

    // ---- formatDurationSecs ----

    @Test
    fun `duration seconds - sub minute keeps only seconds`() {
        assertEquals("45s", TimeUtils.formatDurationSecs(45_000L))
    }

    @Test
    fun `duration seconds - minutes and seconds`() {
        assertEquals("2m 5s", TimeUtils.formatDurationSecs(125_000L))
    }

    @Test
    fun `duration seconds - hours minutes seconds`() {
        assertEquals("7h 16m 32s", TimeUtils.formatDurationSecs(((7 * 60 + 16) * 60 + 32) * 1000L))
    }

    @Test
    fun `duration seconds - zero drops middle units but keeps seconds`() {
        assertEquals("7h 32s", TimeUtils.formatDurationSecs((7 * 3600 + 32) * 1000L))
    }

    @Test
    fun `duration seconds - zero and negative read as 0s`() {
        assertEquals("0s", TimeUtils.formatDurationSecs(0L))
        assertEquals("0s", TimeUtils.formatDurationSecs(-5_000L))
    }

    // ---- relativeDayLabel ----

    @Test
    fun `same calendar day reads today even late at night`() {
        assertEquals("today", TimeUtils.relativeDayLabel(cal(2026, 11, 8, 0, 0, 5).timeInMillis, now))
        assertEquals("today", TimeUtils.relativeDayLabel(cal(2026, 11, 8, 23, 59).timeInMillis, now))
    }

    @Test
    fun `owner rule - same-day gap suppresses day words, crossing shows both`() {
        // The dialog gates the words on spanDays > 1; this pins the
        // pairing the rule produces for the two cases.
        val s = cal(2026, 11, 7, 23, 59, 58).timeInMillis
        val e = cal(2026, 11, 8, 0, 0, 3).timeInMillis
        val crossing = TimeUtils.spanDays(s, e) > 1
        assertEquals(true, crossing)
        assertEquals("yesterday", TimeUtils.relativeDayLabel(s, now))
        assertEquals("today", TimeUtils.relativeDayLabel(e, now))
        // Same-day gap: no words at all (the dialog renders bare times).
        val s2 = cal(2026, 11, 8, 12, 12, 16).timeInMillis
        val e2 = cal(2026, 11, 8, 17, 19, 25).timeInMillis
        assertEquals(false, TimeUtils.spanDays(s2, e2) > 1)
    }

    @Test
    fun `the day before reads yesterday even one second across midnight`() {
        assertEquals("yesterday", TimeUtils.relativeDayLabel(cal(2026, 11, 7, 23, 59, 59).timeInMillis, now))
    }

    @Test
    fun `older than yesterday reads the date, not a count`() {
        assertEquals("Nov 6", TimeUtils.relativeDayLabel(cal(2026, 11, 6, 12, 0).timeInMillis, now))
        assertEquals("Nov 1", TimeUtils.relativeDayLabel(cal(2026, 11, 1, 9, 41).timeInMillis, now))
    }

    @Test
    fun `a different year appends the year`() {
        assertEquals("Dec 25 2025", TimeUtils.relativeDayLabel(cal(2025, 12, 25, 12, 0).timeInMillis, now))
    }

    @Test
    fun `a future stamp reads today`() {
        assertEquals("today", TimeUtils.relativeDayLabel(cal(2026, 11, 9, 8, 0).timeInMillis, now))
    }

    // ---- spanDays ----

    @Test
    fun `same-day gap touches one day`() {
        val s = cal(2026, 11, 8, 15, 12).timeInMillis
        val e = cal(2026, 11, 8, 15, 40).timeInMillis
        assertEquals(1, TimeUtils.spanDays(s, e))
        assertEquals(false, TimeUtils.spanDays(s, e) > 1)
    }

    @Test
    fun `midnight crosser touches two days even at five minutes`() {
        val s = cal(2026, 11, 7, 23, 59, 58).timeInMillis
        val e = cal(2026, 11, 8, 0, 0, 3).timeInMillis
        assertEquals(2, TimeUtils.spanDays(s, e))
        assertEquals(true, TimeUtils.spanDays(s, e) > 1)
    }

    @Test
    fun `a three-day gap touches three days`() {
        val s = cal(2026, 11, 6, 23, 0).timeInMillis
        val e = cal(2026, 11, 8, 7, 0).timeInMillis
        assertEquals(3, TimeUtils.spanDays(s, e))
    }
}
