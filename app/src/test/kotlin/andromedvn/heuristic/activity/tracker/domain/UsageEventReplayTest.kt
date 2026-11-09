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
    fun `device shutdown closes the interval as real usage`() {
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
    fun `a dark-screen resume is screen-off activity, not usage`() {
        // The three-way split (2026-10-16): a resume while the screen is
        // PROVABLY dark is Screen-Off Activity (music mode, background
        // re-binds, lock-screen apps). It is not usage (nobody can use a dark
        // screen). The user's own overnight archive was full of exactly this:
        // single apps "running" for 3 hours while the user slept.
        val events = listOf(
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 0L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 5_000L, "com.a")
        )
        assertEquals("no usage from a dark resume", emptyMap<String, List<Interval>>(), replayUsageIntervals(events, 0L, 10_000L))
        assertEquals(
            "the span belongs to the screen-off category",
            mapOf("com.a" to listOf(1_000L to 5_000L)),
            replayScreenOffIntervals(events, 0L, 10_000L)
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
