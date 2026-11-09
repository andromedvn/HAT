package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3 (hidden apps) and F4 (totalAppExecution vs trueDeviceScreenTime) were
 * flagged as possible bugs. Both are documented behaviours, not defects. These
 * tests pin the current semantics so a later "fix" cannot silently change what
 * the dashboard shows.
 */
class AppAccountingTest {

    private fun perAppSum(intervalsByPkg: Map<String, List<Interval>>): Long =
        intervalsByPkg.values.sumOf { totalDuration(mergeIntervals(it)) }

    private fun mergedDeviceTotal(intervalsByPkg: Map<String, List<Interval>>): Long =
        totalDuration(mergeIntervals(intervalsByPkg.values.flatten()))

    @Test
    fun `overlapping packages make the per-app sum exceed the merged device total`() {
        // Split-screen: two packages genuinely foreground at once.
        val split = mapOf(
            "com.a" to listOf(0L to 10_000L),
            "com.b" to listOf(0L to 10_000L)
        )
        val device = mergedDeviceTotal(split)
        val perApp = perAppSum(split)
        assertEquals(10_000L, device)
        assertEquals(20_000L, perApp)
        assertTrue(
            "per-app sum legitimately exceeds the merged total; clamping would hide it",
            perApp > device
        )
    }

    @Test
    fun `disjoint packages keep the two totals equal`() {
        val sequential = mapOf(
            "com.a" to listOf(0L to 5_000L),
            "com.b" to listOf(5_000L to 10_000L)
        )
        assertEquals(mergedDeviceTotal(sequential), perAppSum(sequential))
    }

    @Test
    fun `hidden app time is erased from both buckets by design`() {
        // This is the documented reading of "hidden": the user asked for it to
        // be invisible, so it is not credited to app usage and not credited to
        // offline either. scanForGaps flags the resulting gap instead.
        val all = mapOf(
            "com.visible" to listOf(0L to 5_000L),
            "com.hidden" to listOf(5_000L to 10_000L)
        )
        val hidden = setOf("com.hidden")
        val visibleOnly = all.filterKeys { it !in hidden }

        val appTotal = perAppSum(visibleOnly)
        val offlineRaw = listOf(0L to 10_000L)
        val offlineSharded = totalDuration(subtractIntervals(offlineRaw, mergeIntervals(all.values.flatten())))

        assertEquals("hidden time is not app usage", 5_000L, appTotal)
        assertEquals("hidden time is not credited to offline either", 0L, offlineSharded)
    }

    @Test
    fun `visible app time survives sharding untouched`() {
        val all = mapOf("com.a" to listOf(0L to 5_000L))
        val offline = listOf(0L to 10_000L)
        val sharded = totalDuration(subtractIntervals(offline, mergeIntervals(all.values.flatten())))
        assertEquals("the unlabelled remainder is the offline figure", 5_000L, sharded)
    }
}
