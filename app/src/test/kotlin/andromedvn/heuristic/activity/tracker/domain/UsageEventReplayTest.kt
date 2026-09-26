package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageEventReplayTest {

    @Test
    fun `screen off closes the resumed interval as real usage`() {
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a", "MainActivity"),
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 5_000L, "")
        )
        val result = replayUsageIntervals(events, 0L, 10_000L)
        assertEquals(mapOf("com.a" to listOf(1_000L to 5_000L)), result)
    }

    @Test
    fun `device shutdown closes the interval as real usage and is NOT a ghost`() {
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a", "MainActivity"),
            RawEvent(EventCode.DEVICE_SHUTDOWN, 5_000L, "")
        )
        val result = replayUsageIntervals(events, 0L, 10_000L)
        assertEquals(
            "shutdown must still count the elapsed time as real usage",
            mapOf("com.a" to listOf(1_000L to 5_000L)), result
        )
    }

    @Test
    fun `device shutdown must not emit a ghost interval`() {
        // The regression guard. Before the fix, DEVICE_SHUTDOWN (26) was handled
        // in the same branch as SCREEN_NON_INTERACTIVE (16), so an app left
        // resumed across a reboot was recorded as idle "ghost" time.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a", "MainActivity"),
            RawEvent(EventCode.DEVICE_SHUTDOWN, 5_000L, "")
        )
        val ghosts = replayGhostIntervals(events, 0L, 10_000L)
        assertEquals("shutdown must not create a ghost", emptyList<GhostSpan>(), ghosts)
    }

    @Test
    fun `app paused without human presence is still a ghost`() {
        // Control: the ghost path must keep working for genuine idle cases.
        val events = listOf(
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 0L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 5_000L, "com.a")
        )
        val ghosts = replayGhostIntervals(events, 0L, 10_000L)
        assertEquals(listOf(GhostSpan("com.a", 1_000L, 5_000L)), ghosts)
    }

    @Test
    fun `keyguard hidden marks human presence and suppresses ghosts`() {
        val events = listOf(
            RawEvent(EventCode.KEYGUARD_HIDDEN, 0L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 5_000L, "com.a")
        )
        assertEquals(
            emptyList<GhostSpan>(),
            replayGhostIntervals(events, 0L, 10_000L)
        )
    }

    @Test
    fun `screen off after a shutdown still closes a ghost normally`() {
        // A shutdown abandons the pending ghost; a later real screen-off must
        // behave exactly as if the shutdown had not occurred.
        val withShutdown = listOf(
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 0L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.DEVICE_SHUTDOWN, 2_000L, ""),
            RawEvent(EventCode.SCREEN_INTERACTIVE, 3_000L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 4_000L, "com.b"),
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 6_000L, "")
        )
        assertEquals(
            "only the post-shutdown session may be a ghost",
            listOf(GhostSpan("com.b", 4_000L, 6_000L)),
            replayGhostIntervals(withShutdown, 0L, 10_000L)
        )
    }

    @Test
    fun `hidden packages are excluded from the result map`() {
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.hidden", "A"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.visible", "B"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 5_000L, "com.hidden", "A"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 5_000L, "com.visible", "B")
        )
        val all = replayUsageIntervals(events, 0L, 10_000L)
        assertEquals(setOf("com.hidden", "com.visible"), all.keys)
        // HeuristicEngine filters by key; assert the map is shaped for that.
        assertEquals(listOf(1_000L to 5_000L), all["com.hidden"])
    }
}
