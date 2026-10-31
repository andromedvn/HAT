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
 * State transition introduced, in two places:
 *
 *  1. [replayUsageIntervals], KEYGUARD_SHOWN joins SESSION_TERMINATORS, so it
 *     closes every in-flight resumed interval at its own timestamp and clears
 *     the in-flight map, exactly as a screen-off does. Every locked
 *     millisecond after that point is no longer attributed to an app.
 *  2. [replayGhostIntervals], KEYGUARD_SHOWN sets `isHuman = false` (a locked
 *     device is not being touched) and discards a pending ghost only when an
 *     unlock happened after that ghost started, which would otherwise make it
 *     re-arm over proven human use.
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

    // --- ghost detection ---------------------------------------------------

    @Test
    fun `an app resumed while the device is locked is a ghost`() {
        // The second half of the transition: `isHuman` becomes false at the
        // lock, so a later resume starts a ghost. This is what makes the locked
        // stretch convertible to an offline label instead of silently counting
        // as screen time.
        val events = listOf(lock(0L), resumed(1_000L), screenOff(5_000L))
        assertEquals(
            listOf(GhostSpan("com.a", 1_000L, 5_000L)),
            replayGhostIntervals(events, 0L, 10_000L)
        )
    }

    @Test
    fun `a ghost running across a lock with no intervening unlock stays valid idle time`() {
        // The device was already untouched when it locked, so the lock carries
        // no new information and the ghost must NOT be discarded. Losing it
        // would under-report idle time. The candidate must also survive a
        // SECOND lock event while the screen is still on.
        // (2026-10-16) The fixture now opens with a lock instead of a
        // screen-off: a dark-screen resume belongs to Screen-Off Activity, so
        // keeping the screen lit keeps this the ambiguous ghost case.
        val events = listOf(
            lock(0L),
            resumed(1_000L),
            lock(2_000L),
            paused(5_000L)
        )
        assertEquals(
            listOf(GhostSpan("com.a", 1_000L, 5_000L)),
            replayGhostIntervals(events, 0L, 10_000L)
        )
    }

    @Test
    fun `a lock after an unlock abandons a stale pending ghost instead of emitting a phantom`() {
        // The hazard the state reset exists to prevent. KEYGUARD_HIDDEN sets
        // `isHuman = true` but does NOT clear the pending ghost, so it is
        // frozen rather than discarded. If KEYGUARD_SHOWN re-armed that stale
        // start, the following screen-off would report 5s of "idle" ghost
        // covering 2s of proven human use.
        val events = listOf(
            screenOff(0L),
            resumed(2_000L),   // ghost starts at 2s
            unlock(3_000L),    // human present from 3s; ghost frozen at 2s
            resumed(4_000L),   // human present: no new ghost
            paused(5_000L),    // human present: nothing closed
            lock(6_000L),      // abandon the stale 2s start
            screenOff(7_000L)  // nothing pending, so nothing to emit
        )

        assertEquals(
            "no phantom may span the 3s-5s the user was demonstrably present for",
            emptyList<GhostSpan>(),
            replayGhostIntervals(events, 0L, 10_000L)
        )
    }

    @Test
    fun `a resume while locked is reported as app time AND as a ghost`() {
        // Known limit of the architecture, pinned so it is not mistaken for a
        // bug in the lock handling. `replayUsageIntervals` and
        // `replayGhostIntervals` are two independent passes over the same
        // stream; only the first one consults the terminator set, because only
        // the second tracks human presence. So a stretch where an app resumes
        // while the device is locked appears in BOTH results.
        //
        // That is safe, and is the existing design rather than a new overlap:
        // when the user converts such a ghost to an offline label, the label and
        // the app interval cover the same millis and sharding removes the
        // overlap exactly once (ClusteringThresholdBoundaryTest, invariant 3).
        // The gap the terminator actually closes is the locked stretch BEFORE
        // any new resume, which is the part that was silently becoming screen
        // time.
        val events = listOf(
            resumed(1_000L),
            lock(5_000L),
            resumed(6_000L),   // resumed while locked
            screenOff(9_000L)
        )
        val screenTime = replayUsageIntervals(events, 0L, 20_000L).getValue("com.a")
        val ghosts = replayGhostIntervals(events, 0L, 20_000L)

        assertEquals("the session is cut at the lock", 1_000L to 5_000L, screenTime[0])
        assertEquals(2, screenTime.size)
        assertEquals(listOf(GhostSpan("com.a", 6_000L, 9_000L)), ghosts)

        val ghostSpan = ghosts.single()
        assertTrue(
            "the ghost lies inside reported app time, which is what sharding exists for",
            ghostSpan.start >= screenTime[1].first && ghostSpan.end <= screenTime[1].second
        )
        val app = mergeIntervals(screenTime)
        assertEquals(
            "converting it yields zero offline: no double count",
            emptyList<Interval>(),
            subtractIntervals(listOf(ghostSpan.start to ghostSpan.end), app)
        )
    }
}
