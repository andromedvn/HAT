package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The device-level dark-window projection (r12p): screen-off to screen-on,
 * no app attached. The screen-state machine is the same vocabulary the four
 * proven projections use, so these replays pin the honest edges: a window
 * opens on SCREEN_NON_INTERACTIVE, closes on SCREEN_INTERACTIVE or
 * DEVICE_SHUTDOWN, and an unclosed window runs to the requested end.
 */
class DeviceScreenOffReplayTest {

    private fun ev(ts: Long, type: Int, pkg: String = "") = RawEvent(type, ts, pkg, null)

    @Test
    fun `a dark stretch closes at the next screen-on`() {
        val events = listOf(
            ev(0, EventCode.SCREEN_NON_INTERACTIVE),
            ev(3_600_000, EventCode.SCREEN_INTERACTIVE)
        )
        assertEquals(listOf(0L to 3_600_000L), replayDeviceScreenOffIntervals(events, 0, 7_200_000))
    }

    @Test
    fun `a shutdown closes an open dark window`() {
        val events = listOf(
            ev(0, EventCode.SCREEN_NON_INTERACTIVE),
            ev(3_600_000, EventCode.DEVICE_SHUTDOWN)
        )
        assertEquals(listOf(0L to 3_600_000L), replayDeviceScreenOffIntervals(events, 0, 7_200_000))
    }

    @Test
    fun `an unclosed dark window runs to the requested end`() {
        val events = listOf(ev(0, EventCode.SCREEN_NON_INTERACTIVE))
        assertEquals(listOf(0L to 7_200_000L), replayDeviceScreenOffIntervals(events, 0, 7_200_000))
    }

    @Test
    fun `screen-on with no prior darkness produces nothing`() {
        val events = listOf(ev(0, EventCode.SCREEN_INTERACTIVE))
        assertEquals(emptyList<Pair<Long, Long>>(), replayDeviceScreenOffIntervals(events, 0, 7_200_000))
    }

    @Test
    fun `back-to-back screen-offs do not double-open`() {
        val events = listOf(
            ev(0, EventCode.SCREEN_NON_INTERACTIVE),
            ev(1_000, EventCode.SCREEN_NON_INTERACTIVE),
            ev(5_000, EventCode.SCREEN_INTERACTIVE)
        )
        assertEquals(listOf(0L to 5_000L), replayDeviceScreenOffIntervals(events, 0, 10_000))
    }

    @Test
    fun `app events between screen edges are ignored`() {
        val events = listOf(
            ev(0, EventCode.SCREEN_NON_INTERACTIVE),
            ev(1_000, EventCode.ACTIVITY_RESUMED, "com.tiktok"),
            ev(2_000, EventCode.KEYGUARD_HIDDEN),
            ev(9_000, EventCode.SCREEN_INTERACTIVE)
        )
        assertEquals(listOf(0L to 9_000L), replayDeviceScreenOffIntervals(events, 0, 20_000))
    }
}
