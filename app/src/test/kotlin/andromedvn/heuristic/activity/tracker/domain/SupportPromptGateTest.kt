package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.UserSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The support prompt must appear exactly once per install, and only when the
 * user has actually reached the theme control. These are the rules the dialog
 * wires itself to; they are pure so they can be tested without a device.
 */
class SupportPromptGateTest {

    @Test
    fun `prompt shows on first reach of the theme control`() {
        assertTrue(shouldShowSupportPrompt(hasSeen = false, reachedThemeControl = true))
    }

    @Test
    fun `prompt never shows twice`() {
        assertFalse(shouldShowSupportPrompt(hasSeen = true, reachedThemeControl = true))
    }

    @Test
    fun `prompt does not show before the theme control is reached`() {
        assertFalse(shouldShowSupportPrompt(hasSeen = false, reachedThemeControl = false))
    }

    @Test
    fun `prompt does not show on launch even for a fresh install`() {
        assertFalse(shouldShowSupportPrompt(hasSeen = false, reachedThemeControl = false))
    }

    @Test
    fun `fresh install defaults to not having seen the prompt`() {
        assertFalse(UserSettings().hasSeenSupportPrompt)
    }
}
