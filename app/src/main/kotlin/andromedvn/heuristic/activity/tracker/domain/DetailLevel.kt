package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.UserSettings

/**
 * The ONE setting that replaces the three engine thresholds users never
 * understood (2026-10-18), renamed 2026-10-20.
 *
 * Before this, the Settings screen had three separate sliders that all asked
 * the same question in different words: "How short can something be before
 * it's not worth showing you?" A gap, an idle session, and a screen-off span
 * are different categories of unaccounted time, but that question has ONE
 * answer per person. Three knobs for one question was complexity, and
 * complexity is the enemy of honesty.
 *
 * The first version of this setting was called "Detail Level" with options
 * "Everything / Balanced / Big Picture". Those names lied: they sounded like
 * they changed WHAT you see, but every option showed the same categories and
 * only moved one number. So the setting said what it did: Minimum Duration,
 * and the options were the numbers themselves. Custom let the user pick any
 * number of minutes (2026-10-20), because a preset that fits nobody is a
 * hidden threshold by another name.
 *
 * A week of real use split the one dial into two (2026-10-23, the owner's
 * call), and r9 removed the second half again: idle and lock-screen cards
 * are gone entirely (the app never guesses what happened while nobody was
 * looking), so Gap Minimum is the only dial left. It keeps the presets
 * above and gates gaps and the Screen-Off Activity list (fixed 15-minute
 * floor, the old Idle Minimum default).
 *
 * LEVELS (stored in UserSettings.detailLevel):
 *   0 = 1 min (the fresh default),  1 = 5 min,  2 = 15 min,  3 = Custom
 * Custom minutes live in UserSettings.customDetailMinutes and are only read
 * when the level is CUSTOM. -1 means "unset" and falls back to 5, so old
 * settings JSON without the field can never produce a broken threshold.
 */
object DetailLevel {

    const val ONE_MIN: Int = 0
    const val FIVE_MIN: Int = 1
    const val FIFTEEN_MIN: Int = 2
    const val CUSTOM: Int = 3

    val LABELS = listOf("1 min" to ONE_MIN, "5 min" to FIVE_MIN, "15 min" to FIFTEEN_MIN, "Custom" to CUSTOM)

    /** The minimum length of a GAP, in minutes (the Gap Minimum dial). */
    fun minDurationMinutes(settings: UserSettings): Int = when (settings.detailLevel) {
        ONE_MIN -> 1
        FIVE_MIN -> 5
        FIFTEEN_MIN -> 15
        CUSTOM -> if (settings.customDetailMinutes >= 1) settings.customDetailMinutes else 5
        else -> 5
    }

    /** The minimum length of a GAP, in milliseconds. */
    fun minDurationMillis(settings: UserSettings): Long = minDurationMinutes(settings) * 60_000L

    /**
     * The largest custom value the Settings dialog accepts, in minutes.
     * One day is the longest range HAT displays, so a minimum above 1440
     * would guarantee an empty app. A visible validation rule, not a hidden
     * clamp: the dialog tells the user the limit when they exceed it.
     */
    const val CUSTOM_MAX_MINUTES: Int = 1440

    /**
     * Session merge tolerance in minutes. NOT configurable: 1 minute is
     * correct for everyone. If you leave an app and come back within a
     * minute, it is one session. This is a grouping rule, not a visibility
     * filter, and it never needs user input.
     */
    const val SESSION_MERGE_MINS: Int = 1
}
