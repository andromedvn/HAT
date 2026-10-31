package andromedvn.heuristic.activity.tracker.domain

import java.util.Calendar

/**
 * Pure local-day arithmetic for the places that used to assume a day is
 * exactly 86_400_000 ms.
 *
 * Three production walks made that assumption with three different arithmetics
 * (see DstClockChangeTest): a fixed `+ 86_400_000L` seed in
 * [andromedvn.heuristic.activity.tracker.data.ActivityRepository.syncArchive],
 * a fixed `+ i * 3_600_000L` offset in the dashboard's gap-chart walk, and a
 * hardcoded 24-bucket loop in [HeuristicEngine.buildChartPoints]. On a DST
 * boundary the fixed offsets and the Calendar-based advances disagree: a
 * spring-forward day is 23 real hours and a fall-back day is 25, so the fixed
 * forms skipped the last hour of every fall-back day, drifted the archive
 * anchor off local midnight, and mislabelled the transition buckets.
 *
 * Everything here is a function of the device's default timezone only - no
 * clock, no Context - so the JVM test suite can pin it under
 * `TimeZone.setDefault(...)` exactly the way DstClockChangeTest already does.
 */

/**
 * One wall-clock hour bucket of a local day.
 *
 * [startMillis]/[endMillis] are absolute epoch bounds (one real hour apart,
 * even across a transition). [startHour]/[endHour] are the wall-clock hours at
 * those bounds: on a fall-back day two consecutive buckets read
 * `startHour = 1`, and on a spring-forward day the 01:00 bucket has
 * `endHour = 3` because 02:00 never happens.
 */
data class HourBucket(
    val startMillis: Long,
    val endMillis: Long,
    val startHour: Int,
    val endHour: Int
)

/**
 * The wall-clock hour buckets of the local day that starts at [dayStart]
 * (a local midnight): 24 on a normal day, 23 on a spring-forward day, 25 on a
 * fall-back day. The buckets tile the day exactly - the last one ends at the
 * next local midnight - so no hour is charted twice or dropped.
 */
fun hourBucketsOfDay(dayStart: Long): List<HourBucket> {
    val start = Calendar.getInstance().apply { timeInMillis = dayStart }
    val dayEnd = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    val out = mutableListOf<HourBucket>()
    val cursor = start.clone() as Calendar
    while (cursor.timeInMillis < dayEnd) {
        val bucketStart = cursor.timeInMillis
        val startHour = cursor.get(Calendar.HOUR_OF_DAY)
        cursor.add(Calendar.HOUR_OF_DAY, 1)
        out.add(HourBucket(bucketStart, cursor.timeInMillis, startHour, cursor.get(Calendar.HOUR_OF_DAY)))
    }
    return out
}

/**
 * The local midnight that follows [millis], on the device's calendar.
 *
 * `millis + 86_400_000L` is WRONG once per DST boundary: on the day after a
 * spring-forward it lands at 01:00 local, and after a fall-back at 23:00 the
 * previous evening. Callers that walk day-by-day (`syncArchive`) must use
 * this instead, or every window after the boundary is anchored to the wrong
 * wall-clock second for as long as the walk continues.
 */
fun nextLocalMidnight(millis: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
