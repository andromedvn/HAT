package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.SessionItem
import andromedvn.heuristic.activity.tracker.data.TimeRangeLabel
import andromedvn.heuristic.activity.tracker.ui.components.chartSyncKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Guards the chart-to-list sync key.
 *
 * The defect: ActivityDetailsScreen matched a tapped bar to its session row by
 * formatting the session start with SessionItem.getTimeRangeStr, which uses
 * Locale.getDefault(), and then string-testing that against ChartPoint.syncTag.
 * The engine stamps syncTag with Locale.ENGLISH, so on a device whose default
 * locale was not English the two strings for the same instant differed and no
 * row was ever highlighted or scrolled to.
 */
class ChartSyncKeyTest {

    // 2024-06-14 is a Friday, so the WEEK key has a weekday name to localise.
    private val friday = utcMillis(2024, Calendar.JUNE, 14, 14, 30)

    private val originalLocale = Locale.getDefault()
    private val originalZone = TimeZone.getDefault()

    private val locales = listOf(
        Locale.ENGLISH, Locale.US, Locale.GERMANY, Locale.JAPAN,
        Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN")
    )

    @Before
    fun pinTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    @After
    fun restoreDefaults() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
    }

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(year, month, day, hour, minute, 0)
        }.timeInMillis

    // --- locale independence (the regression this key exists to prevent) ----

    @Test
    fun `key is identical under every default locale`() {
        for (range in TimeRangeLabel.values()) {
            Locale.setDefault(Locale.ENGLISH)
            val expected = chartSyncKey(friday, range)
            for (locale in locales) {
                Locale.setDefault(locale)
                assertEquals(
                    "range $range under $locale", expected, chartSyncKey(friday, range)
                )
            }
        }
    }

    @Test
    fun `old display-string comparison did fail under a non-english locale`() {
        // Documents WHY the key exists. The old WEEK/MONTH match formatted with
        // Locale.getDefault() and substring-tested against the engine's
        // Locale.ENGLISH tag, so it silently matched nothing.
        val session = SessionItem(friday, friday + 600_000L, 600_000L)
        val weekTag = chartSyncKey(friday, TimeRangeLabel.WEEK)
        val monthTag = chartSyncKey(friday, TimeRangeLabel.MONTH)

        Locale.setDefault(Locale.ENGLISH)
        assertEquals(weekTag, session.getTimeRangeStr(TimeRangeLabel.WEEK).split(",")[0])
        assertEquals(monthTag, session.getTimeRangeStr(TimeRangeLabel.MONTH).split(",")[0])

        Locale.setDefault(Locale.JAPAN)
        assertFalse(
            "Japanese formatting must not reproduce the engine's English tag",
            weekTag.contains(session.getTimeRangeStr(TimeRangeLabel.WEEK).split(",")[0])
        )
        assertFalse(
            "Japanese formatting must not reproduce the engine's English tag",
            monthTag.contains(session.getTimeRangeStr(TimeRangeLabel.MONTH).split(",")[0])
        )
    }

    // --- the key agrees with the tag the engine stamps ---------------------

    @Test
    fun `day key is the hour of day`() {
        assertEquals("14", chartSyncKey(friday, TimeRangeLabel.DAY))
        assertEquals("0", chartSyncKey(utcMillis(2024, Calendar.JUNE, 14, 0, 0), TimeRangeLabel.DAY))
        assertEquals("23", chartSyncKey(utcMillis(2024, Calendar.JUNE, 14, 23, 59), TimeRangeLabel.DAY))
    }

    @Test
    fun `week and month keys use english abbreviations`() {
        assertEquals("Fri", chartSyncKey(friday, TimeRangeLabel.WEEK))
        assertEquals("Jun 14", chartSyncKey(friday, TimeRangeLabel.MONTH))
    }

    @Test
    fun `every minute inside a bucket yields the same key`() {
        // Sessions are clustered and may start mid-bucket; the key must depend on
        // the bucket, not on the second the session happened to start.
        val bucketStart = utcMillis(2024, Calendar.JUNE, 14, 14, 0)
        val expectedDay = chartSyncKey(bucketStart, TimeRangeLabel.DAY)
        for (minute in 0..59) {
            val millis = bucketStart + minute * 60_000L
            assertEquals(expectedDay, chartSyncKey(millis, TimeRangeLabel.DAY))
        }
        assertEquals("Jun 14", chartSyncKey(bucketStart + 59 * 60_000L, TimeRangeLabel.MONTH))
    }

    @Test
    fun `keys differ across buckets`() {
        assertFalse(chartSyncKey(friday, TimeRangeLabel.DAY) == chartSyncKey(friday + 3_600_000L, TimeRangeLabel.DAY))
        assertFalse(chartSyncKey(friday, TimeRangeLabel.MONTH) == chartSyncKey(friday + 86_400_000L, TimeRangeLabel.MONTH))
    }
}
