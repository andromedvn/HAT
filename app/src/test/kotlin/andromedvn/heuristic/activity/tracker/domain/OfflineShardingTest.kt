package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineShardingTest {

    @Test
    fun `raw offline sum double counts time that was also app usage`() {
        val offline = listOf(0L to 1_000L)
        val app = listOf(400L to 600L)

        val rawSum = totalDuration(offline)
        val shardedSum = totalDuration(subtractIntervals(offline, mergeIntervals(app)))

        assertEquals("raw sum counts the whole offline session", 1_000L, rawSum)
        assertEquals("sharded sum removes the 200ms the app actually owned", 800L, shardedSum)
        assertTrue("these disagree, which is the bug", rawSum != shardedSum)
    }

    @Test
    fun `sharding is correct for partial overlap at the start`() {
        val offline = listOf(0L to 1_000L)
        val app = listOf(0L to 300L)
        assertEquals(700L, totalDuration(subtractIntervals(offline, mergeIntervals(app))))
    }

    @Test
    fun `sharding is correct for partial overlap at the end`() {
        val offline = listOf(0L to 1_000L)
        val app = listOf(700L to 2_000L)
        assertEquals(700L, totalDuration(subtractIntervals(offline, mergeIntervals(app))))
    }

    @Test
    fun `fully covered offline time is removed entirely`() {
        val offline = listOf(0L to 1_000L)
        val app = listOf(0L to 2_000L)
        assertEquals(0L, totalDuration(subtractIntervals(offline, mergeIntervals(app))))
    }

    @Test
    fun `app time spanning two offline sessions sharded once each`() {
        val offline = listOf(0L to 1_000L, 2_000L to 3_000L)
        val app = listOf(500L to 2_500L)
        val sharded = subtractIntervals(offline, mergeIntervals(app))
        assertEquals(1_000L, totalDuration(sharded))
    }

    @Test
    fun `sharding never lets app and offline exceed the day together`() {
        // The invariant the dashboard depends on: app + offline <= window.
        val window = 86_400_000L
        val app = listOf(0L to 30_000_000L, 40_000_000L to 60_000_000L)
        val offline = listOf(0L to 86_400_000L) // a user who labelled the whole day

        val appTotal = totalDuration(mergeIntervals(app))
        val offlineTotal = totalDuration(subtractIntervals(offline, mergeIntervals(app)))
        assertTrue(
            "app + sharded offline must fit the day: $appTotal + $offlineTotal",
            appTotal + offlineTotal <= window
        )
    }
}
