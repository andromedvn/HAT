package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KEYGUARD_SHOWN (17) as a "the device is not being used" signal.
 *
 * Before this, `SESSION_TERMINATORS` was `{SCREEN_NON_INTERACTIVE,
 * DEVICE_SHUTDOWN}`. The lock screen is itself interactive, so Android does
 * NOT emit SCREEN_NON_INTERACTIVE when a device merely locks, the screen-off
 * event may be tens of minutes away (long timeout, always-on display) or may
 * never arrive at all. Everything between the lock and the screen-off was
 * therefore replayed as foreground app time.
 *
 * The two events are NOT interchangeable, which is why the fix adds a signal
 * rather than swapping one for the other:
 *
 *  - SCREEN_NON_INTERACTIVE with no KEYGUARD_HIDDEN afterwards (pocketed
 *    device, screen off, never unlocked) was already handled.
 *  - KEYGUARD_SHOWN with no SCREEN_NON_INTERACTIVE afterwards (device locked
 *    and left on the table) was not handled at all.
 *
 * State transition introduced:
 *
 *  1. [replayUsageIntervals], KEYGUARD_SHOWN joins SESSION_TERMINATORS, so it
 *     closes every in-flight resumed interval at its own timestamp and clears
 *     the in-flight map, exactly as a screen-off does. Every locked
 *     millisecond after that point is no longer attributed to an app.
 *  2. (Retired with the idle feature, 2026-11-08) replayGhostIntervals used
 *     the same signal to mark a locked device as not-human; the idle/ghost
 *     projections are gone, so the lock's remaining job is the terminator.
 *
 * No double counting: a terminator is idempotent. It closes the open sessions
 * and clears the map, so a second terminator in the same millisecond, the
 * usual shape, since Android emits ACTIVITY_PAUSED and KEYGUARD_SHOWN together
 * when a device locks, finds nothing left to close. Whichever of the two is
 * ordered first emits the single interval and the other is a no-op, so the
 * interval length is identical either way.
 *
 * No gap in the sense that matters: the interval is cut at the lock instant,
 * not left open. The locked stretch afterwards is classified as NEITHER app
 * time nor a ghost until something resumes while locked, i.e. it becomes
 * unlabelled gap time the user can label, rather than invented screen time.
 * That direction is the safe one for the day invariant: the change can only
 * move a total DOWN, never past the length of the day.
 */
class KeyguardLockScreenReplayTest {

    private fun resumed(at: Long, pkg: String = "com.a", clazz: String = "MainActivity") =
        RawEvent(EventCode.ACTIVITY_RESUMED, at, pkg, clazz)

    private fun paused(at: Long, pkg: String = "com.a", clazz: String = "MainActivity") =
        RawEvent(EventCode.ACTIVITY_PAUSED, at, pkg, clazz)

    /** System events carry a null package, which the drain maps to "". */
    private fun lock(at: Long) = RawEvent(EventCode.KEYGUARD_SHOWN, at, "")
    private fun unlock(at: Long) = RawEvent(EventCode.KEYGUARD_HIDDEN, at, "")
    private fun screenOff(at: Long) = RawEvent(EventCode.SCREEN_NON_INTERACTIVE, at, "")

    // --- the terminator set ------------------------------------------------

    @Test
    fun `keyguard shown is a session terminator and keyguard hidden is not`() {
        assertTrue(
            "a lock must terminate in-flight sessions",
            EventCode.KEYGUARD_SHOWN in EventCode.SESSION_TERMINATORS
        )
        assertTrue(
            "an unlock must NOT truncate the session it reveals",
            EventCode.KEYGUARD_HIDDEN !in EventCode.SESSION_TERMINATORS
        )
        assertEquals(17, EventCode.KEYGUARD_SHOWN)
        assertEquals(18, EventCode.KEYGUARD_HIDDEN)
    }

    // --- screen time -------------------------------------------------------

    @Test
    fun `keyguard shown closes the resumed interval`() {
        val events = listOf(resumed(1_000L), lock(5_000L))
        assertEquals(
            mapOf("com.a" to listOf(1_000L to 5_000L)),
            replayUsageIntervals(events, 0L, 10_000L)
        )
    }

    @Test
    fun `a screen left on while locked stops counting as screen time`() {
        // The defect. The device locks at 5s and the screen never goes dark;
        // the app is never paused. With only SCREEN_NON_INTERACTIVE as a
        // terminator the trailing session ran to the cap.
        val locked = listOf(resumed(1_000L), lock(5_000L))
        val withoutSignal = listOf(resumed(1_000L))

        val after = totalDuration(replayUsageIntervals(locked, 0L, 10_000L, cap = 10_000L).values.flatten())
        val before = totalDuration(replayUsageIntervals(withoutSignal, 0L, 10_000L, cap = 10_000L).values.flatten())

        assertEquals("only the unlocked stretch is screen time", 4_000L, after)
        assertEquals("what the missing signal used to report", 9_000L, before)
        assertTrue("the fix can only reduce screen time, never inflate it", after < before)
    }

    @Test
    fun `a pause and a lock in the same millisecond produce exactly one interval`() {
        // Android emits both when a device locks. Order is not guaranteed, so
        // both orders must give the same single interval, this is the
        // no-double-count property.
        val pauseFirst = listOf(resumed(1_000L), paused(5_000L), lock(5_000L))
        val lockFirst = listOf(resumed(1_000L), lock(5_000L), paused(5_000L))

        val a = replayUsageIntervals(pauseFirst, 0L, 10_000L).getValue("com.a")
        val b = replayUsageIntervals(lockFirst, 0L, 10_000L).getValue("com.a")

        assertEquals(listOf(1_000L to 5_000L), a)
        assertEquals("order must not matter", a, b)
        assertEquals(4_000L, totalDuration(a))
    }

    @Test
    fun `unlocking never truncates the session it reveals`() {
        // The mirror guard: 18 must stay OUT of the terminator set, or every
        // session would be cut at every unlock.
        val events = listOf(resumed(1_000L), unlock(5_000L), paused(9_000L))
        assertEquals(
            listOf(1_000L to 9_000L),
            replayUsageIntervals(events, 0L, 10_000L).getValue("com.a")
        )
    }

    @Test
    fun `lock then unlock then resume yields two disjoint intervals and no invented time`() {
        // The full cycle. The physical span is 11s; 4s of it is spent locked
        // and must be reported as neither app time nor a duplicate.
        val events = listOf(
            resumed(1_000L),
            lock(5_000L),
            unlock(8_000L),
            resumed(9_000L),
            paused(12_000L)
        )
        val intervals = replayUsageIntervals(events, 0L, 20_000L).getValue("com.a")

        assertEquals(listOf(1_000L to 5_000L, 9_000L to 12_000L), intervals)
        assertEquals("no millisecond is counted twice", 7_000L, totalDuration(intervals))
        assertEquals("and exactly the locked stretch is unaccounted", 4_000L, 11_000L - 7_000L)
        assertEquals("the locked stretch is the gap between the two", 4_000L, intervals[1].first - intervals[0].second)
    }

    @Test
    fun `a lock after a screen off does not shorten the already-closed interval`() {
        // Both terminators in one lock cycle. The first one closes the session
        // and the second is a no-op, so the interval is not truncated to the
        // later of the two events.
        val events = listOf(resumed(1_000L), screenOff(5_000L), lock(5_500L))
        assertEquals(
            listOf(1_000L to 5_000L),
            replayUsageIntervals(events, 0L, 10_000L).getValue("com.a")
        )
    }

    @Test
    fun `several packages in flight are all closed by one lock`() {
        // Distinct class names: the in-flight map is keyed by class, so two
        // packages sharing a class name would resolve to one session.
        val events = listOf(
            resumed(1_000L, "com.a", "A"),
            resumed(2_000L, "com.b", "B"),
            lock(5_000L)
        )
        val result = replayUsageIntervals(events, 0L, 10_000L)
        assertEquals(listOf(1_000L to 5_000L), result["com.a"])
        assertEquals(listOf(2_000L to 5_000L), result["com.b"])
        assertEquals(7_000L, totalDuration(result.values.flatten()))
    }
}
