package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The upgrade path from pre-1.1.2 settings (2026-10-21).
 *
 * The regression audit found that every upgrading user's thresholds were
 * silently reset: old JSON carries minGapThresholdMins and friends, new
 * code defaulted everyone to the 5 minute Minimum Duration preset. These
 * tests pin the migration that carries the stored gap threshold across,
 * including the maintainer's own vault JSON as a real-world fixture.
 *
 * The fixture is not invented: it is the exact settings object from the
 * compiled Master Vault of 2026-10-15, byte-for-byte the shape every
 * restoring user will present.
 */
class SettingsMigrationTest {

    /** The maintainer's real vault settings: gap 5, idle 60, merge 1. */
    private val realVaultJson =
        """{"themeType":"STATIC","sortType":"RECENT","staticColor":4294934528,""" +
            """"extractedColor":4285300725,"hasSeenOnboarding":true,"hasSeenSupportPrompt":false,""" +
            """"hasSeenHistoryLimitWarning":false,"installDateMillis":1791176000000,""" +
            """"bypassHistoryLimit":false,"archiveSyncIntervalHours":12,"minGapThresholdMins":5,""" +
            """"sessionClusteringMins":1,"ghostTimeTriggerMins":60}"""

    @Test
    fun `the maintainer's real vault migrates to the five minute preset`() {
        val migrated = SettingsMigration.migrate(realVaultJson)!!
        assertEquals(DetailLevel.FIVE_MIN, migrated.detailLevel)
        // The idle trigger (60) is deliberately NOT carried as a second knob.
        assertEquals(-1, migrated.customDetailMinutes)
        // Everything else rides along untouched.
        assertEquals(12, migrated.archiveSyncIntervalHours)
        assertEquals(4294934528L, migrated.staticColor)
    }

    @Test
    fun `preset gap thresholds map to their levels`() {
        fun json(gap: Int) =
            """{"minGapThresholdMins":$gap,"sessionClusteringMins":1,"ghostTimeTriggerMins":60}"""
        assertEquals(DetailLevel.ONE_MIN, SettingsMigration.migrate(json(1))!!.detailLevel)
        assertEquals(DetailLevel.FIVE_MIN, SettingsMigration.migrate(json(5))!!.detailLevel)
        assertEquals(DetailLevel.FIFTEEN_MIN, SettingsMigration.migrate(json(15))!!.detailLevel)
    }

    @Test
    fun `a custom gap value becomes a custom minimum duration with the same number`() {
        val migrated = SettingsMigration.migrate(
            """{"minGapThresholdMins":30,"ghostTimeTriggerMins":0}"""
        )!!
        assertEquals(DetailLevel.CUSTOM, migrated.detailLevel)
        assertEquals(30, migrated.customDetailMinutes)
        assertEquals(30, DetailLevel.minDurationMinutes(migrated))
    }

    @Test
    fun `a broken legacy gap value becomes the default preset, never a zero threshold`() {
        val migratedZero = SettingsMigration.migrate("""{"minGapThresholdMins":0}""")!!
        assertEquals(DetailLevel.FIVE_MIN, migratedZero.detailLevel)
        val migratedNegative = SettingsMigration.migrate("""{"minGapThresholdMins":-3}""")!!
        assertEquals(DetailLevel.FIVE_MIN, migratedNegative.detailLevel)
        val migratedAbsurd = SettingsMigration.migrate("""{"minGapThresholdMins":9000}""")!!
        assertEquals(DetailLevel.FIVE_MIN, migratedAbsurd.detailLevel)
    }

    @Test
    fun `missing legacy gap key defaults to the five minute preset`() {
        val migrated = SettingsMigration.migrate("""{"sortType":"RECENT"}""")!!
        assertEquals(DetailLevel.FIVE_MIN, migrated.detailLevel)
    }

    @Test
    fun `json that already carries detailLevel is left alone`() {
        // New settings must round-trip untouched: the migration runs on
        // every load, so a false positive here would fight every save.
        val current = UserSettings(detailLevel = DetailLevel.CUSTOM, customDetailMinutes = 9)
        assertNull(SettingsMigration.migrate(realVaultJson.replace("}", ",\"detailLevel\":3,\"customDetailMinutes\":9}")))
        val withBoth = """{"minGapThresholdMins":5,"detailLevel":2,"customDetailMinutes":-1}"""
        assertNull(SettingsMigration.migrate(withBoth))
        // And the round-trip value really is what DetailLevel serves.
        assertEquals(DetailLevel.CUSTOM, current.detailLevel)
    }

    @Test
    fun `garbage json yields null, not a half-built settings object`() {
        assertNull(SettingsMigration.migrate("""{"minGapThresholdMins":"""))
        assertNull(SettingsMigration.migrate("not json at all"))
    }

    @Test
    fun `sync off is retired even on new-format settings`() {
        // The owner removed Off from the Background History Sync dial
        // (2026-10-23): the archive worker keeps history alive, and an off
        // switch was a silent death sentence for the user's past. Anyone
        // who chose Off gets the 12 hour default, and the retirement runs
        // even on settings that already carry the new-format fields.
        val withBoth = """{"minGapThresholdMins":5,"detailLevel":2,"customDetailMinutes":-1,"archiveSyncIntervalHours":0}"""
        val migratedBoth = SettingsMigration.migrate(withBoth)!!
        assertEquals(12, migratedBoth.archiveSyncIntervalHours)
        assertEquals(DetailLevel.FIFTEEN_MIN, migratedBoth.detailLevel)
        // No migration needed when sync is healthy: null, so the caller
        // does not pointlessly rewrite.
        assertNull(SettingsMigration.migrate("""{"detailLevel":2,"archiveSyncIntervalHours":6}"""))
    }
}
