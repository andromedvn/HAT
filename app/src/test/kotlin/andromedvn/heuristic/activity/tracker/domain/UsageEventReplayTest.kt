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
    fun `a dark-screen resume is screen-off activity, not usage and not a ghost`() {
        // The three-way split (2026-10-16): a resume while the screen is
        // PROVABLY dark is Screen-Off Activity (music mode, background
        // re-binds, lock-screen apps). It is not usage (nobody can use a dark
        // screen) and not a ghost (ghosts keep the ambiguous screen-on-locked
        // cases). The user's own overnight archive was full of exactly this:
        // single apps "running" for 3 hours while the user slept.
        val events = listOf(
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 0L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 5_000L, "com.a")
        )
        assertEquals("no usage from a dark resume", emptyMap<String, List<Interval>>(), replayUsageIntervals(events, 0L, 10_000L))
        assertEquals("no ghost claim on a dark resume", emptyList<GhostSpan>(), replayGhostIntervals(events, 0L, 10_000L))
        assertEquals(
            "the span belongs to the screen-off category",
            mapOf("com.a" to listOf(1_000L to 5_000L)),
            replayScreenOffIntervals(events, 0L, 10_000L)
        )
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

    // --- a pending ghost must survive a package switch while unlocked-absent --

    @Test
    fun `a resume of a different package closes the pending ghost at the switch instant`() {
        // A locked screen that cycles through more than one package (SystemUI,
        // then a notification's activity) used to lose every span but the last
        // one: the resume overwrote currentPkg/ghostStart in place, discarding
        // the previous package's idle time instead of closing it where the
        // foreground demonstrably moved.
        // NOTE (2026-10-16): opens with a LOCK, not a screen-off. A dark-screen
        // resume is Screen-Off Activity now; the lock keeps the screen on, so
        // this stays the ambiguous case ghosts exist for.
        val events = listOf(
            RawEvent(EventCode.KEYGUARD_SHOWN, 0L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.systemui"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 3_000L, "com.music"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 5_000L, "com.music"),
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 6_000L, "")
        )
        val ghosts = replayGhostIntervals(events, 0L, 10_000L)
        assertEquals(
            "both packages' idle spans are surfaced, split at the switch",
            listOf(
                GhostSpan("com.systemui", 1_000L, 3_000L),
                GhostSpan("com.music", 3_000L, 5_000L)
            ),
            ghosts
        )
    }

    @Test
    fun `a re-resume of the same package keeps the original ghost start`() {
        // The old code reset ghostStart on EVERY resume, so a package that
        // re-resumed while the device was untouched lost the head of its
        // ghost candidate. The span is continuous: the candidate must start
        // where the first resume happened, not where the latest one did.
        // NOTE (2026-10-16): lock, not screen-off; see the switch test above.
        val events = listOf(
            RawEvent(EventCode.KEYGUARD_SHOWN, 0L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.music"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 3_000L, "com.music"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 5_000L, "com.music"),
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 6_000L, "")
        )
        val ghosts = replayGhostIntervals(events, 0L, 10_000L)
        assertEquals(
            "the candidate spans from the FIRST resume to the pause",
            listOf(GhostSpan("com.music", 1_000L, 5_000L)),
            ghosts
        )
    }

    @Test
    fun `a zero-length switch does not emit an empty ghost`() {
        // Same-millisecond resume events must not produce a backwards or
        // empty span for the outgoing package.
        // NOTE (2026-10-16): lock, not screen-off; see the switch test above.
        val events = listOf(
            RawEvent(EventCode.KEYGUARD_SHOWN, 0L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 2_000L, "com.systemui"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 2_000L, "com.music"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 4_000L, "com.music")
        )
        val ghosts = replayGhostIntervals(events, 0L, 10_000L)
        assertEquals(
            "only the surviving span is reported",
            listOf(GhostSpan("com.music", 2_000L, 4_000L)),
            ghosts
        )
    }

    @Test
    fun `an unproven pending ghost is dropped at every emit point not just switches`() {
        // A candidate that opened on a bare resume, before ANY screen-off or
        // lock in the stream, has a head that may cover real usage. It must
        // be dropped wherever it closes: at a package switch AND at a pause.
        // The original rule only dropped at switches and emitted the same
        // untrusted candidate at pauses, which the invariant fuzzer caught
        // (2026-10-23) as a double-claim against the unattended projection
        // on identical head spans. (KeyguardLockScreenReplayTest pins the
        // same-package re-resume rule: the head re-anchors to the resume.)
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 3_000L, "com.b"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 5_000L, "com.b")
        )
        val ghosts = replayGhostIntervals(events, 0L, 10_000L)
        assertEquals(
            "no idle signal was ever proven, so no candidate may be emitted",
            emptyList<GhostSpan>(),
            ghosts
        )
    }
}
