package andromedvn.heuristic.activity.tracker.domain

/**
 * Pure interval algebra for HAT.
 *
 * Deliberately free of Android imports so it can be unit-tested on the JVM.
 * Every function here is a pure `(intervals) -> intervals` mapping; no clock,
 * no locale, no Context.
 */

typealias Interval = Pair<Long, Long>

/** Merges overlapping and touching intervals into a minimal sorted set. */
fun mergeIntervals(intervals: List<Interval>): List<Interval> {
    if (intervals.isEmpty()) return emptyList()
    val sorted = intervals.sortedBy { it.first }
    val merged = mutableListOf<Interval>()
    var current = sorted[0]
    for (i in 1 until sorted.size) {
        val next = sorted[i]
        if (current.second >= next.first) {
            current = current.copy(second = maxOf(current.second, next.second))
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)
    return merged
}

/** Removes [remove] intervals from [source], preserving any surviving remainder. */
fun subtractIntervals(source: List<Interval>, remove: List<Interval>): List<Interval> {
    var current = source
    for (rem in remove) {
        val next = mutableListOf<Interval>()
        for (src in current) {
            if (src.second <= rem.first || src.first >= rem.second) next.add(src)
            else {
                if (src.first < rem.first) next.add(src.first to rem.first)
                if (src.second > rem.second) next.add(rem.second to src.second)
            }
        }
        current = next
    }
    return current
}

/** Sums the total covered duration of [intervals]. Does not merge first. */
fun totalDuration(intervals: List<Interval>): Long =
    intervals.sumOf { it.second - it.first }
