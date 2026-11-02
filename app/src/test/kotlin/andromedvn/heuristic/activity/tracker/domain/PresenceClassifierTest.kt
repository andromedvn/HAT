package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Golden replay tests for the presence classifier (2026-11-02): one per
 * edge-signature row of the arena's adopted spine. Every scenario is a
 * scripted event stream; the classifier must return exactly the row's class.
 * Constants used: SCREEN_OFF_TIMEOUT = 30_000 ms in every scenario.
 */
class PresenceClassifierTest {

    private val timeout = 30_000L

    private fun ev(type: Int, ts: Long, pkg: String = "com.test.app") = RawEvent(type, ts, pkg, null)

    private companion object {
        const val RESUMED = 1
        const val INTERACTION = 7
        const val SCREEN_ON = 15
        const val SCREEN_OFF = 16
        const val KEYGUARD_SHOWN = 17
        const val KEYGUARD_HIDDEN = 18
    }

    @Test
    fun `unlock inside the block is an anchored presence fact`() {
        val t0 = 1_000_000L
        val events = listOf(
            ev(RESUMED, t0),
            // the gap runs t0+1min to t0+31min, with an unlock in the middle
            ev(KEYGUARD_HIDDEN, t0 + 16 * 60_000L),
        )
        val v = PresenceClassifier.classify(events, t0 + 60_000L, t0 + 31 * 60_000L, timeout)
        assertEquals(PresenceClassifier.Kind.ANCHORED, v.kind)
        assertEquals(t0 + 16 * 60_000L, v.anchorTs)
    }

    @Test
    fun `keyguard within 1_5s of screen off is a deliberate lock`() {
        val t0 = 1_000_000L
        val screenOff = t0 + 20 * 60_000L
        val events = listOf(
            ev(RESUMED, t0),
            ev(KEYGUARD_SHOWN, screenOff - 300L),
            ev(SCREEN_OFF, screenOff),
        )
        val v = PresenceClassifier.classify(events, screenOff + 1L, screenOff + 45 * 60_000L, timeout)
        assertEquals(PresenceClassifier.Kind.CHOSE_TO_STOP, v.kind)
        assertEquals(screenOff, v.anchorTs)
    }

    @Test
    fun `screen off one timeout after last event then a far unlock is a timeout`() {
        val t0 = 1_000_000L
        val lastUse = t0 + 20 * 60_000L
        val screenOff = lastUse + timeout // exactly one timeout later
        val unlock = screenOff + 2L * 60 * 60_000L // two hours later
        val events = listOf(
            ev(RESUMED, t0),
            ev(RESUMED, lastUse - 5_000L),
            ev(SCREEN_OFF, screenOff),
            ev(KEYGUARD_HIDDEN, unlock),
            ev(RESUMED, unlock + 1_000L, "com.other"),
        )
        val v = PresenceClassifier.classify(events, screenOff + 1L, unlock - 1L, timeout)
        assertEquals(PresenceClassifier.Kind.TIMEOUT, v.kind)
    }

    @Test
    fun `screen off far from any human event still reads as timeout and never guesses presence`() {
        val t0 = 1_000_000L
        val screenOff = t0 + 3 * 60 * 60_000L // screen-off with no events for hours before
        val events = listOf(
            ev(RESUMED, t0),
            ev(SCREEN_OFF, screenOff),
            ev(KEYGUARD_HIDDEN, screenOff + 60_000L),
        )
        val v = PresenceClassifier.classify(events, screenOff + 1L, screenOff + 55_000L, timeout)
        assertEquals(PresenceClassifier.Kind.TIMEOUT, v.kind)
    }

    @Test
    fun `same app resuming right after screen on with no unlock is playback continued`() {
        val t0 = 1_000_000L
        val screenOff = t0 + 45 * 60_000L
        val screenOn = screenOff + 40_000L
        val events = listOf(
            ev(RESUMED, t0),
            ev(SCREEN_OFF, screenOff),
            ev(SCREEN_ON, screenOn),
            ev(RESUMED, screenOn + 1_500L),
        )
        val v = PresenceClassifier.classify(events, t0 + 60_000L, screenOff + 30_000L, timeout)
        assertEquals(PresenceClassifier.Kind.MEDIA_CONTINUED, v.kind)
    }

    @Test
    fun `screen on with no resumption and no unlock is undecided`() {
        val t0 = 1_000_000L
        val events = listOf(
            ev(RESUMED, t0),
        )
        val v = PresenceClassifier.classify(events, t0 + 60_000L, t0 + 40 * 60_000L, timeout)
        assertEquals(PresenceClassifier.Kind.UNDECIDED, v.kind)
        assertNull(v.anchorTs)
    }

    @Test
    fun `no events at all around the block is undecided`() {
        val v = PresenceClassifier.classify(emptyList(), 1_000_000L, 2_000_000L, timeout)
        assertEquals(PresenceClassifier.Kind.UNDECIDED, v.kind)
    }

    @Test
    fun `an unlock between screen on and the resume breaks the media continuation`() {
        val t0 = 1_000_000L
        val screenOff = t0 + 45 * 60_000L
        val screenOn = screenOff + 40_000L
        val events = listOf(
            ev(RESUMED, t0),
            ev(SCREEN_OFF, screenOff),
            ev(SCREEN_ON, screenOn),
            ev(KEYGUARD_HIDDEN, screenOn + 500L),
            ev(RESUMED, screenOn + 1_500L),
        )
        val v = PresenceClassifier.classify(events, t0 + 60_000L, screenOff + 30_000L, timeout)
        assertEquals(PresenceClassifier.Kind.UNDECIDED, v.kind)
    }
}
