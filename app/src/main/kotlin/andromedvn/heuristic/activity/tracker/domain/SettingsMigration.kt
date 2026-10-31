package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.UserSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One-time settings migrations from earlier formats (2026-10-21, extended
 * 2026-10-23). Each migration is checked independently, so a settings file
 * can need one, both, or neither, and a migration written today still runs
 * on a file that already received an earlier one.
 *
 * Migration 1, the gap threshold (2026-10-21). Old settings JSON has no
 * `detailLevel` key: the three retired engine knobs stored their own numbers.
 * New code reads exactly one number, the Gap Minimum, and silently defaulting
 * every upgrading user to 5 minutes was the one real regression the branch
 * audit found. This migration carries the stored GAP threshold across, because
 * gaps are the category the value always governed most visibly. The old idle
 * trigger is deliberately NOT carried: idle spans now answer to the new
 * Idle Minimum dial, which starts at 15 minutes for everyone.
 *
 * Migration 2, retiring sync Off (2026-10-23, the owner's call). "Off" is
 * gone from the Background History Sync dial: the archive worker is the only
 * thing keeping history alive before Android deletes it, and an off switch
 * was a silent death sentence for the user's own past. Anyone who chose Off
 * gets the 12 hour default. Runs on ANY settings file, new format included:
 * choosing Off predates the split and must not survive it.
 *
 * Returns null when no migration is needed, so the caller persists once and
 * never again. Pure and JVM-testable: no Android types, one string in,
 * settings out or null.
 */
object SettingsMigration {

    private val json = Json { ignoreUnknownKeys = true }

    fun migrate(raw: String): UserSettings? = try {
        val obj = json.parseToJsonElement(raw).jsonObject
        val settings = json.decodeFromString<UserSettings>(raw)
        var result: UserSettings? = null

        if (!obj.containsKey("detailLevel")) {
            val legacyGap = obj["minGapThresholdMins"]?.jsonPrimitive?.intOrNull ?: 5
            // Presets map to presets; a custom value rides along as a custom
            // Gap Minimum; a broken legacy value (zero, negative, or past the
            // dialog's own ceiling) becomes the default preset, never a zero
            // threshold and never a silent clamp.
            val (level, custom) = when {
                legacyGap == 1 -> DetailLevel.ONE_MIN to -1
                legacyGap == 5 -> DetailLevel.FIVE_MIN to -1
                legacyGap == 15 -> DetailLevel.FIFTEEN_MIN to -1
                legacyGap in 1..DetailLevel.CUSTOM_MAX_MINUTES -> DetailLevel.CUSTOM to legacyGap
                else -> DetailLevel.FIVE_MIN to -1
            }
            result = settings.copy(detailLevel = level, customDetailMinutes = custom)
        }

        if ((obj["archiveSyncIntervalHours"]?.jsonPrimitive?.intOrNull ?: 12) <= 0) {
            result = (result ?: settings).copy(archiveSyncIntervalHours = 12)
        }

        result
    } catch (e: Exception) {
        // A JSON the serializer cannot read at all is the caller's problem
        // (its fallback already yields defaults); a half-readable one is
        // treated the same way. Never migrate into a half-built state.
        null
    }
}
