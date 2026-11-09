package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ONE setting that replaced three (2026-10-18), renamed 2026-10-20.
 *
 * Before this, the Settings screen exposed Idle App Detection, Actionable Gap
 * Minimum, and Session Merge Tolerance as separate sliders. All three asked
 * the same question in different words: "How short can something be before
 * it's not worth showing you?" Users couldn't tell them apart. Now one
 * Minimum Duration answers it for every category at once.
 *
 * The first version called itself "Detail Level" with options named
 * "Everything / Balanced / Big Picture". Those names lied - they sounded like
 * they changed WHAT you see, but every option showed the same categories and
 * only moved one number. The tests now pin the honest version: the options
 * are the numbers themselves, plus Custom for any value the user wants.
 *
 * These tests pin the exact mapping so a future edit cannot silently make
 * one category stricter or looser than the others.
 */
class DetailLevelTest {

    private fun settings(level: Int, custom: Int = -1) = UserSettings(detailLevel = level, customDetailMinutes = custom)

    @Test
    fun `preset levels show spans from their minute counts up`() {
        assertEquals(1, DetailLevel.minDurationMinutes(settings(DetailLevel.ONE_MIN)))
        assertEquals(60_000L, DetailLevel.minDurationMillis(settings(DetailLevel.ONE_MIN)))
        assertEquals(5, DetailLevel.minDurationMinutes(settings(DetailLevel.FIVE_MIN)))
        assertEquals(300_000L, DetailLevel.minDurationMillis(settings(DetailLevel.FIVE_MIN)))
        assertEquals(15, DetailLevel.minDurationMinutes(settings(DetailLevel.FIFTEEN_MIN)))
        assertEquals(900_000L, DetailLevel.minDurationMillis(settings(DetailLevel.FIFTEEN_MIN)))
    }

    @Test
    fun `custom level uses the user's own minutes`() {
        assertEquals(7, DetailLevel.minDurationMinutes(settings(DetailLevel.CUSTOM, 7)))
        assertEquals(420_000L, DetailLevel.minDurationMillis(settings(DetailLevel.CUSTOM, 7)))
        assertEquals(1, DetailLevel.minDurationMinutes(settings(DetailLevel.CUSTOM, 1)))
        assertEquals(1440, DetailLevel.minDurationMinutes(settings(DetailLevel.CUSTOM, 1440)))
    }

    @Test
    fun `custom level with unset or invalid minutes falls back to five`() {
        // Old settings JSON lacks customDetailMinutes, so it deserializes as
        // -1. A CUSTOM level reading -1 must never produce a zero or negative
        // threshold; it falls back to the default instead of lying about
        // filtering everything.
        assertEquals(5, DetailLevel.minDurationMinutes(settings(DetailLevel.CUSTOM)))
        assertEquals(5, DetailLevel.minDurationMinutes(settings(DetailLevel.CUSTOM, -1)))
        assertEquals(5, DetailLevel.minDurationMinutes(settings(DetailLevel.CUSTOM, 0)))
    }


    @Test
    fun `unknown level falls back to five minutes`() {
        assertEquals(5, DetailLevel.minDurationMinutes(settings(99)))
        assertEquals(5, DetailLevel.minDurationMinutes(settings(-1)))
    }

    @Test
    fun `old vault levels zero one two keep their minute meanings`() {
        // Old settings JSON stores the same indices this version does. A
        // vault written before the rename must behave identically after it.
        assertEquals(1, DetailLevel.minDurationMinutes(settings(0)))
        assertEquals(5, DetailLevel.minDurationMinutes(settings(1)))
        assertEquals(15, DetailLevel.minDurationMinutes(settings(2)))
    }

    @Test
    fun `session merge presets carry the chosen tolerance`() {
        assertEquals(listOf("1 min" to 1, "2 min" to 2, "5 min" to 5), DetailLevel.SESSION_MERGE_PRESETS)
        // The default UserSettings (no stored key) still reads 1 minute.
        assertEquals(1, DetailLevel.sessionMergeMins(UserSettings()))
    }

    @Test
    fun `labels match the selector order`() {
        assertEquals(4, DetailLevel.LABELS.size)
        assertEquals("1 min", DetailLevel.LABELS[0].first)
        assertEquals("5 min", DetailLevel.LABELS[1].first)
        assertEquals("15 min", DetailLevel.LABELS[2].first)
        assertEquals("Custom", DetailLevel.LABELS[3].first)
    }

    @Test
    fun `custom cap is one day in minutes`() {
        // The Settings dialog states this limit in its error message; the
        // constant must stay in sync with what the user is told.
        assertEquals(1440, DetailLevel.CUSTOM_MAX_MINUTES)
    }
}
