package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.UserSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tour flag rides in UserSettings like hasSeenOnboarding. Default is
 * false for EVERY install (the tour runs once per fresh state; replay lives
 * in Settings > How HAT Works). These tests pin the default and the JSON
 * round-trip, so a renaming refactor cannot silently strand the flag.
 */
class TourSeenSettingTest {

    @Test
    fun `hasSeenTour defaults to false so the tour runs once`() {
        val settings = UserSettings()
        assertFalse(settings.hasSeenTour)
        assertFalse(settings.hasSeenOnboarding)
    }

    @Test
    fun `hasSeenTour survives a settings JSON round trip`() {
        val json = Json.encodeToString(UserSettings.serializer(), UserSettings(hasSeenTour = true))
        val restored = Json.decodeFromString(UserSettings.serializer(), json)
        assertTrue(restored.hasSeenTour)
    }

    @Test
    fun `old settings JSON without the flag decodes with the default`() {
        val json = """{"themeType":"STATIC","sortType":"RECENT","hasSeenOnboarding":true}"""
        val restored = Json.decodeFromString(UserSettings.serializer(), json)
        assertEquals(true, restored.hasSeenOnboarding)
        // The tour is new in this build: no stored opinion means it should run.
        assertFalse(restored.hasSeenTour)
    }
}
