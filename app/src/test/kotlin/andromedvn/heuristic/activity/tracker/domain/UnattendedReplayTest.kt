package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Idle Session replay (2026-10-21): app still in front, screen on,
 * phone unlocked, no touch landing for a stretch. The emitted span starts at
 * the LAST touch, which is the whole point: the maintainer built the idle
 * detector to learn the exact minute they fell asleep while browsing, and
 * the answer is the last touch, not the session start.
 *
 * The boundaries are the contract: each handover point gives the stretch to
 * the category that owns it. Locks go to the lock-screen projection (ghosts),
 * screen-offs go to Screen-Off Activity, and the untouched span never
 * claims a minute on the wrong side of either.
 */
class UnattendedReplayTest {

    @Test
    fun `the fell asleep signature emits the span from the last touch`() {
        // Unlock, open TikTok, scroll with touches landing, then silence for
        // hours while autoplay holds the screen awake. The user fell asleep
        // somewhere after the last touch at 3_000.
        val events = listOf(
            RawEvent(EventCode.KEYGUARD_HIDDEN, 0L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.tiktok"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.tiktok"),
            RawEvent(EventCode.USER_INTERACTION, 3_000L, "com.tiktok"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 10_000L, "com.tiktok")
        )
        val spans = replayUnattendedIntervals(events, 0L, 12_000L)
        assertEquals(listOf(GhostSpan("com.tiktok", 3_000L, 10_000L)), spans)
    }

    @Test
    fun `a touch in the middle restarts the stillness clock`() {
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.a"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 6_000L, "com.a")
        )
        val spans = replayUnattendedIntervals(events, 0L, 8_000L)
        assertEquals(listOf(GhostSpan("com.a", 2_000L, 6_000L)), spans)
    }

    @Test
    fun `a session with no touches at all is unattended from the resume`() {
        // Opened it (in the launcher), put it down, never touched: a movie
        // left playing. The span starts at the resume.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.player"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 9_000L, "com.player")
        )
        val spans = replayUnattendedIntervals(events, 0L, 10_000L)
        assertEquals(listOf(GhostSpan("com.player", 1_000L, 9_000L)), spans)
    }

    @Test
    fun `a lock closes the span at the lock instant and claims nothing after`() {
        // Watched untouched for a while, then the phone locked (the lock
        // screen projection owns everything from the lock onward).
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.a"),
            RawEvent(EventCode.KEYGUARD_SHOWN, 6_000L, ""),
            RawEvent(EventCode.ACTIVITY_PAUSED, 9_000L, "com.a")
        )
        val spans = replayUnattendedIntervals(events, 0L, 10_000L)
        assertEquals(listOf(GhostSpan("com.a", 2_000L, 6_000L)), spans)
    }

    @Test
    fun `the screen going off closes the span at that instant`() {
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.a"),
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 5_000L, ""),
            RawEvent(EventCode.ACTIVITY_PAUSED, 9_000L, "com.a")
        )
        val spans = replayUnattendedIntervals(events, 0L, 10_000L)
        assertEquals(listOf(GhostSpan("com.a", 2_000L, 5_000L)), spans)
    }

    @Test
    fun `a resume while the screen is off opens no candidate`() {
        // The screen-off category owns dark-screen resumes entirely; the
        // unattended replay must not open a stillness clock for them, not
        // even when the screen later comes back on without an unlock.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 2_000L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 3_000L, "com.a"),
            RawEvent(EventCode.SCREEN_INTERACTIVE, 4_000L, ""),
            RawEvent(EventCode.ACTIVITY_PAUSED, 9_000L, "com.a")
        )
        val spans = replayUnattendedIntervals(events, 0L, 10_000L)
        assertEquals(
            "the dark resume belongs to Screen-Off Activity, and no attendance was proven after the screen came back on",
            listOf(GhostSpan("com.a", 1_000L, 2_000L)),
            spans
        )
    }

    @Test
    fun `the first touch after the screen comes back on opens the clock`() {
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 2_000L, ""),
            RawEvent(EventCode.SCREEN_INTERACTIVE, 3_000L, ""),
            RawEvent(EventCode.USER_INTERACTION, 4_000L, "com.a"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 9_000L, "com.a")
        )
        val spans = replayUnattendedIntervals(events, 0L, 10_000L)
        // The pre-screen-off stillness (resume at 1_000 with no touch) is
        // real evidence and is emitted too; the Minimum Duration filter
        // decides later whether a one-second span is worth a card.
        assertEquals(
            listOf(GhostSpan("com.a", 1_000L, 2_000L), GhostSpan("com.a", 4_000L, 9_000L)),
            spans
        )
    }

    @Test
    fun `an unlock re-anchors attendance so pre-unlock time is never claimed`() {
        // The phone was locked with the app still resumed, then unlocked:
        // the lock-screen projection owns everything before the unlock.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.KEYGUARD_SHOWN, 2_000L, ""),
            RawEvent(EventCode.KEYGUARD_HIDDEN, 6_000L, ""),
            RawEvent(EventCode.ACTIVITY_PAUSED, 9_000L, "com.a")
        )
        val spans = replayUnattendedIntervals(events, 0L, 10_000L)
        // The unlocked head (resume with no touch, locked at 2_000) is
        // emitted as its own short span; the unlock re-anchors and the
        // post-unlock stillness is a separate span. Never a span crossing
        // the unlock, and never one claiming the locked stretch.
        assertEquals(
            listOf(GhostSpan("com.a", 1_000L, 2_000L), GhostSpan("com.a", 6_000L, 9_000L)),
            spans
        )
    }

    @Test
    fun `a same-package re-resume does not split one autoplay night`() {
        // Internal activity transitions (splash to player, ads, etc.) fire
        // resume events for the SAME package without a touch between them.
        // One untouched night must be one span, not one card per transition.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.player"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.player"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 4_000L, "com.player"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 6_000L, "com.player"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 10_000L, "com.player")
        )
        val spans = replayUnattendedIntervals(events, 0L, 12_000L)
        assertEquals(listOf(GhostSpan("com.player", 2_000L, 10_000L)), spans)
    }

    @Test
    fun `a package switch closes one span and starts a fresh clock`() {
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.a"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 6_000L, "com.b"),
            RawEvent(EventCode.USER_INTERACTION, 7_000L, "com.b"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 9_000L, "com.b")
        )
        val spans = replayUnattendedIntervals(events, 0L, 10_000L)
        // com.a's stillness from its last touch to the switch, and com.b's
        // from its own touch to the pause. One clock per app, each anchored
        // by that app's last touch.
        assertEquals(
            listOf(GhostSpan("com.a", 2_000L, 6_000L), GhostSpan("com.b", 7_000L, 9_000L)),
            spans
        )
    }

    @Test
    fun `a shutdown abandons the candidate without emitting`() {
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.a"),
            RawEvent(EventCode.DEVICE_SHUTDOWN, 6_000L, "")
        )
        val spans = replayUnattendedIntervals(events, 0L, 10_000L)
        assertEquals("a reboot timestamp is a persistence artifact, not evidence", emptyList<GhostSpan>(), spans)
    }

    @Test
    fun `stillness running past the window closes at the cap`() {
        // The user is asleep RIGHT NOW with the app open and untouched; the
        // span closes honestly at the present moment.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.a")
        )
        val spans = replayUnattendedIntervals(events, 0L, 10_000L, cap = 8_000L)
        assertEquals(listOf(GhostSpan("com.a", 2_000L, 8_000L)), spans)
    }

    @Test
    fun `a lock during a pending pause flushes at the pause moment and restores nothing`() {
        // The app paused, the phone locked three seconds later: the
        // stillness honestly ended at the pause, and no churn window may
        // reach across a lock.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.a"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 6_000L, "com.a"),
            RawEvent(EventCode.KEYGUARD_SHOWN, 6_500L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 6_600L, "com.a")
        )
        val spans = replayUnattendedIntervals(events, 0L, 10_000L)
        assertEquals(listOf(GhostSpan("com.a", 2_000L, 6_000L)), spans)
    }

    @Test
    fun `a wifi dialog flash does not split the stillness`() {
        // The maintainer's real night (2026-10-21): Bitget left untouched
        // while a flapping Wi-Fi flashed the "no internet" settings dialog
        // into the foreground every few minutes. Each flash fired a
        // pause/resume burst; without churn absorption it carved one night
        // into four cards with false attendance timestamps. The whole
        // stretch must be ONE span from the last touch.
        val events = listOf(
            RawEvent(EventCode.KEYGUARD_HIDDEN, 0L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.bitget"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.bitget"),
            // Flap one
            RawEvent(EventCode.ACTIVITY_PAUSED, 8_000L, "com.bitget"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 8_000L, "com.android.settings"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 8_050L, "com.android.settings"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 8_100L, "com.bitget"),
            // Flap two
            RawEvent(EventCode.ACTIVITY_PAUSED, 20_000L, "com.bitget"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 20_000L, "com.android.settings"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 20_050L, "com.android.settings"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 20_100L, "com.bitget"),
            // The user comes back and switches to TikTok
            RawEvent(EventCode.ACTIVITY_RESUMED, 30_000L, "com.tiktok")
        )
        val spans = replayUnattendedIntervals(events, 0L, 40_000L)
        // The two settings entries are the dialog's own lifetime (50ms each,
        // restored by its second resume): honest sub-grace spans that the
        // Minimum Duration filter keeps out of the UI. The night itself is
        // ONE Bitget span from the last touch, and TikTok's stretch starts
        // at its own resume.
        assertEquals(
            listOf(
                GhostSpan("com.android.settings", 8_000L, 8_050L),
                GhostSpan("com.android.settings", 20_000L, 20_050L),
                GhostSpan("com.bitget", 2_000L, 30_000L),
                GhostSpan("com.tiktok", 30_000L, 40_000L)
            ),
            spans
        )
    }

    @Test
    fun `a dialog flashing over the app before its pause is absorbed too`() {
        // The second half of the same real night (2026-10-22 fix): the
        // phone's millisecond event order put the dialog's resume BEFORE
        // Bitget's pause, so the "switch emits" rule split the night at the
        // exact dialog timestamp (the card read 8:23 PM, the first flap).
        // A switch now stashes like a pause, so BOTH orders absorb.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.bitget"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.bitget"),
            // Dialog appears BEFORE the app pauses
            RawEvent(EventCode.ACTIVITY_RESUMED, 8_000L, "com.android.settings"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 8_010L, "com.android.settings"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 8_020L, "com.android.settings"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 8_030L, "com.android.settings"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 8_040L, "com.bitget"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 8_050L, "com.bitget"),
            // The user comes back and switches away for real
            RawEvent(EventCode.ACTIVITY_RESUMED, 30_000L, "com.tiktok")
        )
        val spans = replayUnattendedIntervals(events, 0L, 40_000L)
        // Same residue note: the settings entry is the dialog's 30ms
        // lifetime, filtered out of the UI by Minimum Duration.
        assertEquals(
            "dialog-first order must produce the same single night span as pause-first",
            listOf(
                GhostSpan("com.android.settings", 8_000L, 8_030L),
                GhostSpan("com.bitget", 2_000L, 30_000L),
                GhostSpan("com.tiktok", 30_000L, 40_000L)
            ),
            spans
        )
    }

    @Test
    fun `a quick real switch preserves each app's own stillness start`() {
        // App A left untouched, the user switches to app B within the grace
        // window (B's resume deferred behind A's pending), then leaves B:
        // B's span must start at ITS OWN resume, not be lost or re-anchored.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.a"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 6_000L, "com.b"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 26_000L, "com.b")
        )
        val spans = replayUnattendedIntervals(events, 0L, 30_000L)
        assertEquals(
            listOf(GhostSpan("com.a", 2_000L, 6_000L), GhostSpan("com.b", 6_000L, 26_000L)),
            spans
        )
    }

    @Test
    fun `an internal activity transition does not reset the clock`() {
        // TikTok switches from MainActivity to a detail page: a pause of one
        // activity and a resume of another, same package, milliseconds
        // apart. The stillness clock must run straight through it.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.player"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.player"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 10_000L, "com.player"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 10_500L, "com.player"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 20_000L, "com.player")
        )
        val spans = replayUnattendedIntervals(events, 0L, 30_000L)
        assertEquals(listOf(GhostSpan("com.player", 2_000L, 20_000L)), spans)
    }

    @Test
    fun `a real return after the grace window anchors fresh`() {
        // The user left the app, did something else long enough to prove
        // real time passed, and came back: the old span closes at its
        // pause, and the new stillness starts at the return, not before.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.a"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 10_000L, "com.a"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 25_000L, "com.a"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 28_000L, "com.a")
        )
        val spans = replayUnattendedIntervals(events, 0L, 30_000L)
        assertEquals(
            listOf(GhostSpan("com.a", 2_000L, 10_000L), GhostSpan("com.a", 25_000L, 28_000L)),
            spans
        )
    }

    @Test
    fun `a stale activity stopped after its replacement resumed does not close the candidate`() {
        // The real Bitget night, traced from the phone's own raw event
        // export (2026-10-23). The splash activity reported STOPPED 1.6
        // seconds after MainActivity already resumed; keyed on package
        // alone, that stale event erased the night's 7:54 PM anchor, and
        // the wifi flap 29 minutes later opened a fresh candidate anchored
        // at 8:23 PM - exactly the card the maintainer saw. The class guard
        // keeps the anchor: only the activity that resumed may close the
        // candidate.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.bitget", "com.upex.exchange.splash.SplashActivity"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.bitget", "com.upex.exchange.splash.SplashActivity"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 3_000L, "com.bitget", "com.upex.exchange.splash.SplashActivity"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 3_500L, "com.bitget", "com.upex.exchange.main.MainActivity"),
            // The stale splash stop, 1.5s late. Must be ignored.
            RawEvent(EventCode.ACTIVITY_STOPPED, 5_000L, "com.bitget", "com.upex.exchange.splash.SplashActivity"),
            // The wifi flap, 29 minutes later: pause + resume within grace.
            RawEvent(EventCode.ACTIVITY_PAUSED, 1_800_000L, "com.bitget", "com.upex.exchange.main.MainActivity"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_800_150L, "com.bitget", "com.upex.exchange.main.MainActivity"),
            // The user comes back and switches to TikTok.
            RawEvent(EventCode.ACTIVITY_RESUMED, 4_000_000L, "com.tiktok", "com.ss.android.ugc.aweme.main.MainActivity")
        )
        val spans = replayUnattendedIntervals(events, 0L, 5_000_000L)
        assertEquals(
            "the night is ONE span anchored at the app open, not the flap",
            listOf(GhostSpan("com.bitget", 2_000L, 4_000_000L), GhostSpan("com.tiktok", 4_000_000L, 5_000_000L)),
            spans
        )
    }

    @Test
    fun `a pause of the resumed activity itself still closes the candidate`() {
        // The class guard must not swallow REAL closes: the activity that
        // resumed is the one that pauses when the user leaves the app.
        val events = listOf(
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.a", "MainActivity"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.a", "MainActivity"),
            RawEvent(EventCode.ACTIVITY_PAUSED, 10_000L, "com.a", "MainActivity"),
            RawEvent(EventCode.ACTIVITY_RESUMED, 40_000L, "com.b", "MainActivity")
        )
        val spans = replayUnattendedIntervals(events, 0L, 60_000L)
        assertEquals(
            "a real close of the resumed activity ends the span at the pause; com.b's own untouched tail is its own span",
            listOf(GhostSpan("com.a", 2_000L, 10_000L), GhostSpan("com.b", 40_000L, 60_000L)),
            spans
        )
    }

    @Test
    fun `no minute is claimed on both sides of a lock handover`() {
        // The full night, realistically: the user unlocks, browses with
        // touches, stops touching, the phone locks, and the app re-resumes
        // BEHIND the lock (a lock-screen media notification waking it).
        // The unattended replay ends at the last touch to lock; the ghost
        // replay owns the behind-lock stretch from its resume. Neither
        // claims the other's minute.
        val events = listOf(
            RawEvent(EventCode.KEYGUARD_HIDDEN, 0L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.tiktok"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.tiktok"),
            RawEvent(EventCode.KEYGUARD_SHOWN, 5_000L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 5_500L, "com.tiktok"),
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 8_000L, "")
        )
        val unattended = replayUnattendedIntervals(events, 0L, 10_000L)
        val ghosts = replayGhostIntervals(events, 0L, 10_000L)
        assertEquals(listOf(GhostSpan("com.tiktok", 2_000L, 5_000L)), unattended)
        assertEquals(listOf(GhostSpan("com.tiktok", 5_500L, 8_000L)), ghosts)
    }

    @Test
    fun `a resume behind a lock opens no unattended candidate`() {
        // The exact double-claim the lock guard exists for (caught by this
        // suite, 2026-10-21): without tracking the locked state, a lock
        // screen media resume opened a stillness clock here and both
        // projections claimed the same minutes.
        val events = listOf(
            RawEvent(EventCode.KEYGUARD_SHOWN, 0L, ""),
            RawEvent(EventCode.ACTIVITY_RESUMED, 1_000L, "com.music"),
            RawEvent(EventCode.USER_INTERACTION, 2_000L, "com.android.systemui"),
            RawEvent(EventCode.SCREEN_NON_INTERACTIVE, 5_000L, "")
        )
        val unattended = replayUnattendedIntervals(events, 0L, 10_000L)
        assertEquals("the locked stretch belongs to the lock-screen projection alone", emptyList<GhostSpan>(), unattended)
    }
}
