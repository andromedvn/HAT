package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fourth time category: Screen-Off Activity.
 *
 * Found in the user's own archive (2026-10-16): single apps "running" for 199
 * and 142 minutes straight between 01:00 and 06:00 while the user was asleep.
 * No human uses an app for three uninterrupted hours on a dark screen; those
 * spans are background re-binds (music mode, lock-screen apps, always-on
 * displays), and counting them as screen time made "Total Screen Time" a lie
 * the user could see with their own eyes.
 *
 * The split is deterministic, not heuristic:
 *
 *  - resume while the screen is PROVABLY dark  -> Screen-Off Activity
 *  - resume while the screen is on but locked   -> ghost candidate (ambiguous)
 *  - anything else                             -> usage
 *
 * No minute may land in two buckets. Every fixture below is drawn from the
 * real patterns the archive showed, not invented edge-case lore.
 */
class ScreenOffReplayTest {

    private fun resume(at: Long, pkg: String) = RawEvent(EventCode.ACTIVITY_RESUMED, at, pkg)
    private fun pause(at: Long, pkg: String) = RawEvent(EventCode.ACTIVITY_PAUSED, at, pkg)
    private fun screenOff(at: Long) = RawEvent(EventCode.SCREEN_NON_INTERACTIVE, at, "")
    private fun screenOn(at: Long) = RawEvent(EventCode.SCREEN_INTERACTIVE, at, "")
    private fun unlock(at: Long) = RawEvent(EventCode.KEYGUARD_HIDDEN, at, "")
    private fun lock(at: Long) = RawEvent(EventCode.KEYGUARD_SHOWN, at, "")

    @Test
    fun `the overnight tiktok pattern is screen-off activity not usage`() {
        // The user's actual data: screen dark at 23:00, an app re-binds at
        // 02:00, the screen comes on at 07:00 when the user wakes. Three hours
        // of "phone usage" while the user slept.
        val events = listOf(
            screenOff(0L),
            resume(3_600_000L * 2, "com.zhiliaoapp.musically"),
            screenOn(3_600_000L * 7)
        )
        val usage = replayUsageIntervals(events, 0L, 3_600_000L * 12)
        val screenOffSpans = replayScreenOffIntervals(events, 0L, 3_600_000L * 12)
        val ghosts = replayGhostIntervals(events, 0L, 3_600_000L * 12)

        assertTrue("no usage is counted for the dark hours", usage.isEmpty())
        assertEquals(
            "the whole dark stretch is one screen-off span",
            mapOf("com.zhiliaoapp.musically" to listOf(7_200_000L to 25_200_000L)),
            screenOffSpans
        )
        assertTrue("and no ghost claim either", ghosts.isEmpty())
    }

    @Test
    fun `unlocking with the app still resumed converts it to usage from the unlock`() {
        // Screen on at 07:00, user unlocks at 07:05 with the app still in the
        // foreground: the dark span ended at 07:00, and only the visible time
        // afterwards counts as usage.
        val events = listOf(
            screenOff(0L),
            resume(10_000L, "com.music"),
            screenOn(20_000L),
            unlock(25_000L),
            pause(40_000L, "com.music")
        )
        val usage = replayUsageIntervals(events, 0L, 60_000L)
        val spans = replayScreenOffIntervals(events, 0L, 60_000L)
        assertEquals(
            "usage starts at the unlock, the first visible moment",
            mapOf("com.music" to listOf(25_000L to 40_000L)),
            usage
        )
        assertEquals(
            "the dark span ended when the screen woke",
            mapOf("com.music" to listOf(10_000L to 20_000L)),
            spans
        )
    }

    @Test
    fun `music re-binding after a screen-off is screen-off activity`() {
        // Real listening: the user played music, pressed power, and the player
        // re-bound while the screen was dark. The audio was real; the screen
        // was not. The user can label the span "Music" when asked.
        val events = listOf(
            resume(0L, "com.spotify"),
            screenOff(5_000L),          // closes the bright usage session
            resume(6_000L, "com.spotify"), // the player's background re-bind
            pause(66_000L, "com.spotify")
        )
        val usage = replayUsageIntervals(events, 0L, 70_000L)
        val spans = replayScreenOffIntervals(events, 0L, 70_000L)
        assertEquals("the bright minute is still usage", mapOf("com.spotify" to listOf(0L to 5_000L)), usage)
        assertEquals("the dark hour is screen-off activity", mapOf("com.spotify" to listOf(6_000L to 66_000L)), spans)
    }

    @Test
    fun `a package switch in the dark splits the span at the switch`() {
        val events = listOf(
            screenOff(0L),
            resume(1_000L, "com.systemui"),
            resume(3_000L, "com.hoffnung"),
            screenOn(5_000L)
        )
        assertEquals(
            mapOf(
                "com.systemui" to listOf(1_000L to 3_000L),
                "com.hoffnung" to listOf(3_000L to 5_000L)
            ),
            replayScreenOffIntervals(events, 0L, 10_000L)
        )
    }

    @Test
    fun `a same-package re-resume in the dark keeps the original start`() {
        val events = listOf(
            screenOff(0L),
            resume(1_000L, "com.music"),
            resume(3_000L, "com.music"),
            screenOn(5_000L)
        )
        assertEquals(
            "one continuous span, not two",
            mapOf("com.music" to listOf(1_000L to 5_000L)),
            replayScreenOffIntervals(events, 0L, 10_000L)
        )
    }

    @Test
    fun `a screen-on locked resume is still a ghost not screen-off activity`() {
        // The ambiguous case stays with the ghosts: lock screen shown, screen
        // still lit, an app resumes behind it. Not provably dark.
        val events = listOf(
            lock(0L),
            resume(1_000L, "com.a"),
            screenOff(5_000L)
        )
        assertTrue("no screen-off claim", replayScreenOffIntervals(events, 0L, 10_000L).isEmpty())
        assertEquals(
            "the ghost keeps its case",
            listOf(GhostSpan("com.a", 1_000L, 5_000L)),
            replayGhostIntervals(events, 0L, 10_000L)
        )
    }

    @Test
    fun `a shutdown ends the dark span it closes`() {
        val events = listOf(
            screenOff(0L),
            resume(1_000L, "com.a"),
            RawEvent(EventCode.DEVICE_SHUTDOWN, 4_000L, "")
        )
        assertEquals(
            mapOf("com.a" to listOf(1_000L to 4_000L)),
            replayScreenOffIntervals(events, 0L, 10_000L)
        )
        assertTrue(replayUsageIntervals(events, 0L, 10_000L).isEmpty())
    }

    @Test
    fun `no screen events means status quo usage`() {
        // A windowed stream that has not shown a screen event yet must behave
        // exactly as it always has: an ordinary session counts from its
        // resume. Every older test pins this.
        val events = listOf(
            resume(1_000L, "com.a"),
            pause(5_000L, "com.a")
        )
        assertEquals(mapOf("com.a" to listOf(1_000L to 5_000L)), replayUsageIntervals(events, 0L, 10_000L))
        assertTrue(replayScreenOffIntervals(events, 0L, 10_000L).isEmpty())
    }

    @Test
    fun `no minute lands in two buckets on a mixed day`() {
        // The union invariant: bright usage, a dark stretch, a locked pocket
        // check, one day. Every event lands in exactly one accounting.
        val events = listOf(
            unlock(0L),
            resume(1_000L, "com.a"),          // bright usage
            screenOff(10_000L),               // power press
            resume(11_000L, "com.a"),         // dark re-bind: screen-off span
            screenOn(20_000L),                // pocket check, still locked
            resume(21_000L, "com.b"),         // screen-on locked: ghost
            screenOff(30_000L),               // pocketed again
            resume(31_000L, "com.c"),         // dark again: screen-off span
            screenOn(40_000L),
            unlock(41_000L)                   // still-resumed com.c becomes visible usage
        )
        val usage = replayUsageIntervals(events, 0L, 60_000L)
        val spans = replayScreenOffIntervals(events, 0L, 60_000L)
        val ghosts = replayGhostIntervals(events, 0L, 60_000L)

        // com.a: [1k, 10k] usage, then dark until the pocket check; it was
        // never PAUSED, so it also accrues the one-second dark stretch before
        // com.c takes the foreground at 31k. A never-paused app genuinely is
        // screen-off activity through every dark stretch it survives.
        assertEquals(listOf(1_000L to 10_000L), usage["com.a"])
        assertEquals(listOf(11_000L to 20_000L, 30_000L to 31_000L), spans["com.a"])
        // com.b: the locked pocket-check stretch is a ghost, [21k, 30k]
        assertEquals(listOf(GhostSpan("com.b", 21_000L, 30_000L)), ghosts)
        // com.c: dark until 40k, converted to usage at the 41k unlock
        assertEquals(listOf(31_000L to 40_000L), spans["com.c"])
        assertEquals(listOf(41_000L to 60_000L), usage["com.c"])
    }

    @Test
    fun `the trailing dark span closes at the cap like every other replay`() {
        val events = listOf(
            screenOff(0L),
            resume(1_000L, "com.a")
        )
        assertEquals(
            mapOf("com.a" to listOf(1_000L to 50_000L)),
            replayScreenOffIntervals(events, 0L, 50_000L, cap = 50_000L)
        )
    }

    @Test
    fun `micro blips exist in the replay but the detail level filters them`() {
        // The real-device bug (2026-10-17): Android fires constant background
        // micro-resume/pause events while the screen is off. A 47ms blip is a
        // sync service breathing, not a human activity. The replay stays pure
        // and captures everything; the ENGINE's getScreenOffActivity applies
        // the Minimum Duration threshold (passed as minSpanMillis by the caller)
        // so the card list only shows real sessions. This test verifies the
        // replay captures everything faithfully; the filtering is the
        // caller's responsibility, pinned in DetailLevelTest.
        val blips = listOf(
            screenOff(0L),
            resume(10_000L, "com.sync"), pause(10_047L, "com.sync"),   // 47ms: noise
            resume(20_000L, "com.music"), pause(20_500L, "com.music"),  // 500ms: noise
            resume(30_000L, "com.tiktok"), screenOn(1_000_000L)         // 16m: real
        )
        val raw = replayScreenOffIntervals(blips, 0L, 2_000_000L)
        // The replay sees all three (it is a faithful record of the stream):
        assertTrue(raw.containsKey("com.sync"))
        assertTrue(raw.containsKey("com.music"))
        assertTrue(raw.containsKey("com.tiktok"))
        // At 5 min, only the real session survives:
        assertEquals(1, raw.values.flatten().count { it.second - it.first >= DetailLevel.minDurationMillis(UserSettings(detailLevel = DetailLevel.FIVE_MIN)) })
        // At 1 min, still only the real session (the blips are
        // milliseconds, far below even the most permissive threshold):
        assertEquals(1, raw.values.flatten().count { it.second - it.first >= DetailLevel.minDurationMillis(UserSettings(detailLevel = DetailLevel.ONE_MIN)) })
    }
}
