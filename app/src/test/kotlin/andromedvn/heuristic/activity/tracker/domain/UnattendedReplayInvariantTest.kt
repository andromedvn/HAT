package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant fuzzer (2026-10-23): the vaccine born from the Bugget week.
 * Every replay bug so far, the wifi churn, the dialog-first order, the stale
 * splash stop, was an event ordering the hand-written tests never imagined.
 * This suite stops waiting for real nights to invent orders: it throws
 * hundreds of randomized event streams at the replays and asserts the rules
 * that must hold for EVERY stream. Any future ordering bug that violates a
 * boundary fails here before it reaches a phone.
 *
 * The invariants:
 *  1. Every span is inside the query window and has a positive length.
 *  2. No projection claims a minute another projection owns: unattended
 *     spans never overlap lock-screen spans or screen-off spans.
 *  3. Spans within one projection and one package never overlap each other.
 *  4. Churn of any kind, however interleaved, never produces a span that
 *     crosses a lock, a screen-off, or a shutdown.
 */
class UnattendedReplayInvariantTest {

    private val pkgs = listOf("com.a", "com.b", "com.settings")
    private val classes = listOf("MainActivity", "DetailActivity", "SplashActivity", "")

    private fun randomStream(random: kotlin.random.Random): List<RawEvent> {
        val count = 1 + random.nextInt(40)
        var t = 0L
        return (0 until count).map {
            t += random.nextLong(50_000)
            val type = when (random.nextInt(10)) {
                0 -> EventCode.ACTIVITY_RESUMED
                1 -> EventCode.ACTIVITY_PAUSED
                2 -> EventCode.ACTIVITY_STOPPED
                3 -> EventCode.USER_INTERACTION
                4 -> EventCode.SCREEN_INTERACTIVE
                5 -> EventCode.SCREEN_NON_INTERACTIVE
                6 -> EventCode.KEYGUARD_SHOWN
                7 -> EventCode.KEYGUARD_HIDDEN
                8 -> EventCode.DEVICE_SHUTDOWN
                else -> 1000  // OEM noise, ignored by design
            }
            RawEvent(type, t, pkgs[random.nextInt(pkgs.size)], classes[random.nextInt(classes.size)])
        }
    }

    private fun assertInvariants(events: List<RawEvent>, seedLabel: String) {
        val start = 0L
        val end = events.maxOf { it.timestamp } + 1_000_000L

        val unattended = replayUnattendedIntervals(events, start, end)
        val ghosts = replayGhostIntervals(events, start, end)
        val screenOff = replayScreenOffIntervals(events, start, end)

        for ((label, spans) in listOf("unattended" to unattended, "ghost" to ghosts)) {
            for (s in spans) {
                assertTrue("$seedLabel $label span outside window: $s", s.start >= start && s.end <= end)
                assertTrue("$seedLabel $label span not positive: $s", s.end > s.start)
            }
            // Per-package spans must not overlap themselves.
            spans.groupBy { it.packageName }.forEach { (pkg, list) ->
                val sorted = list.sortedBy { it.start }
                sorted.zipWithNext().forEach { (a, b) ->
                    assertTrue("$seedLabel $label self-overlap for $pkg: $a vs $b", a.end <= b.start)
                }
            }
        }

        fun overlap(a: GhostSpan, b: GhostSpan) = a.packageName == b.packageName && a.start < b.end && b.start < a.end

        // No minute claimed by two projections.
        for (u in unattended) {
            for (g in ghosts) assertTrue("$seedLabel unattended overlaps ghost: $u vs $g", !overlap(u, g))
            for (s in screenOff[u.packageName].orEmpty()) {
                assertTrue("$seedLabel unattended overlaps screen-off: $u vs $s", !(u.start < s.second && s.first < u.end))
            }
        }
        for (g in ghosts) {
            for (s in screenOff[g.packageName].orEmpty()) {
                assertTrue("$seedLabel ghost overlaps screen-off: $g vs $s", !(g.start < s.second && s.first < g.end))
            }
        }

        // No unattended span crosses a terminal boundary event of any kind.
        val boundaries = events.filter { it.type == EventCode.KEYGUARD_SHOWN || it.type == EventCode.SCREEN_NON_INTERACTIVE || it.type == EventCode.DEVICE_SHUTDOWN }.map { it.timestamp }.sorted()
        for (u in unattended) {
            for (b in boundaries) {
                assertTrue("$seedLabel unattended crosses a terminal boundary at $b: $u", !(u.start < b && b < u.end))
            }
        }
    }

    @Test
    fun `five hundred random streams keep every invariant`() {
        for (seed in 0 until 500) {
            assertInvariants(randomStream(kotlin.random.Random(seed)), "seed $seed:")
        }
    }
}
