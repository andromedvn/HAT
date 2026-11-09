package andromedvn.heuristic.activity.tracker.domain

/**
 * Membership test for "does [start, end) overlap anything already in here?",
 * built for the vault-restore merge.
 *
 * The merge previously asked SQLite this question once per imported row:
 *
 *     SELECT 1 FROM offline_activities
 *     WHERE timestamp < ? AND timestamp + duration > ?
 *
 * With ~23,000 rows already in the destination that is a full scan per
 * imported row - hundreds of millions of comparisons for one restore. This
 * index answers the identical predicate in O(log n) from memory, so the
 * destination table is read once instead of once per row.
 *
 * ## Exact semantics (do not "simplify" these)
 *
 * The predicate is reproduced literally: an existing interval overlaps iff
 * `existingStart < end && existingEnd > start`. Two consequences are load
 * bearing and are pinned by the unit tests:
 *
 *  - **Touching is not overlapping.** [0,100) and [100,200) do not overlap,
 *    because `100 > 100` is false. Half-open intervals throughout.
 *  - **A zero-length incoming interval is a point test.** [50,50) overlaps an
 *    existing [0,100) (0 < 50 and 100 > 50) but not one that merely touches it.
 *    `OfflineStorageEntity` durations are not guaranteed non-zero, so this is
 *    reachable in practice.
 *
 * ## Why a prefix maximum, and not just the nearest neighbour
 *
 * Sorted by start alone, the answer is *not* found by checking the last
 * interval whose start is below [end]. Example: existing [0,1000) and
 * [500,600); query [700,800). Both starts precede 800, but only the first
 * extends past 700. Checking the neighbour alone reports no overlap and
 * silently admits a row that the SQL would have rejected. Hence
 * `prefixMaxEnd`, the running maximum of ends - an O(1) lookup once the O(log
 * n) binary search has found the candidate prefix.
 *
 * ## Incremental use during a merge
 *
 * The merge adds rows as it goes, and each newly inserted row must be visible
 * to the *next* row's check - the SQL saw the table grow. Callers must
 * therefore [add] every interval they actually insert:
 *
 *     val index = IntervalOverlapIndex.build(existing)
 *     for (row in imported) {
 *         if (!index.overlapsAny(row.start, row.end)) {
 *             db.insert(row)
 *             index.add(row.start, row.end)
 *         }
 *     }
 *
 * Omitting the [add] is a correctness bug, not just a lost optimisation.
 *
 * Adds are buffered and folded into the sorted arrays every [FOLD_THRESHOLD]
 * insertions, which keeps [overlapsAny] at O(log n) + O(FOLD_THRESHOLD) while
 * making the fold O(n) amortised per insert. Pure Kotlin and Long millis
 * throughout: no clock, no Android, no Double.
 */
class IntervalOverlapIndex private constructor(
    private var sorted: SortedIntervals,
    private val pending: MutableList<Interval>
) {

    /** Total intervals known, including any still buffered in [pending]. */
    val size: Int get() = sorted.size + pending.size

    /**
     * True iff any known interval satisfies `existingStart < end &&
     * existingEnd > start` - the exact predicate the per-row SELECT used.
     */
    fun overlapsAny(start: Long, end: Long): Boolean {
        if (sorted.overlapsAny(start, end)) return true
        for (i in pending.indices) {
            val p = pending[i]
            if (p.first < end && p.second > start) return true
        }
        return false
    }

    /** Records an interval so subsequent [overlapsAny] calls can see it. */
    fun add(start: Long, end: Long) {
        pending.add(start to end)
        if (pending.size >= FOLD_THRESHOLD) fold()
    }

    /** Bulk [add]; folds once at the end rather than per element. */
    fun addAll(intervals: Iterable<Interval>) {
        for (interval in intervals) pending.add(interval)
        if (pending.isNotEmpty()) fold()
    }

    /** Merges buffered additions into the sorted arrays in a single O(n) pass. */
    private fun fold() {
        if (pending.isEmpty()) return
        val additions = pending.sortedBy { it.first }
        val n = sorted.size
        val m = additions.size
        val total = n + m
        val newStarts = LongArray(total)
        val newEnds = LongArray(total)
        var i = 0
        var j = 0
        var k = 0
        while (i < n && j < m) {
            if (sorted.starts[i] <= additions[j].first) {
                newStarts[k] = sorted.starts[i]; newEnds[k] = sorted.ends[i]; i++
            } else {
                newStarts[k] = additions[j].first; newEnds[k] = additions[j].second; j++
            }
            k++
        }
        while (i < n) { newStarts[k] = sorted.starts[i]; newEnds[k] = sorted.ends[i]; i++; k++ }
        while (j < m) { newStarts[k] = additions[j].first; newEnds[k] = additions[j].second; j++; k++ }
        sorted = SortedIntervals(newStarts, newEnds, total)
        pending.clear()
    }

    /**
     * Intervals sorted by start, plus the running maximum of ends.
     *
     * For a query [start, end) the candidates are exactly the prefix of
     * entries with `entryStart < end`; the greatest end in that prefix is
     * `prefixMaxEnd`, so the answer is a binary search plus one comparison.
     */
    private class SortedIntervals(
        val starts: LongArray,
        val ends: LongArray,
        val size: Int
    ) {
        val prefixMaxEnd = LongArray(size).also { prefix ->
            var running = Long.MIN_VALUE
            for (i in 0 until size) {
                if (ends[i] > running) running = ends[i]
                prefix[i] = running
            }
        }

        fun overlapsAny(start: Long, end: Long): Boolean {
            val candidates = countOfStartsBelow(end)
            if (candidates == 0) return false
            return prefixMaxEnd[candidates - 1] > start
        }

        /** Number of entries with `starts[i] < key`. */
        private fun countOfStartsBelow(key: Long): Int {
            var lo = 0
            var hi = size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] < key) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    companion object {
        /**
         * Buffer size that triggers a fold. Small enough that [overlapsAny]'s
         * linear scan of `pending` is bounded and cheap, large enough that the
         * O(n) fold is amortised across many inserts.
         */
        const val FOLD_THRESHOLD: Int = 64

        /** An index over [intervals]; typically the destination table's rows. */
        fun build(intervals: List<Interval>): IntervalOverlapIndex {
            if (intervals.isEmpty()) return IntervalOverlapIndex(SortedIntervals(LongArray(0), LongArray(0), 0), mutableListOf())
            val ordered = intervals.sortedBy { it.first }
            val starts = LongArray(ordered.size)
            val ends = LongArray(ordered.size)
            for (i in ordered.indices) {
                starts[i] = ordered[i].first
                ends[i] = ordered[i].second
            }
            return IntervalOverlapIndex(SortedIntervals(starts, ends, ordered.size), mutableListOf())
        }
    }
}
