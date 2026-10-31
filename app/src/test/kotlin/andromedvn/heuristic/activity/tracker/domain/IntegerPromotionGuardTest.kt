package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Repo-wide guard against the integer-promotion bug class found during the audit.
 *
 * Kotlin evaluates `minutes * 60 * 1000L` as `(minutes * 60)` in Int, THEN
 * widens to Long. Once the setting passes ~35,829 minutes the Int product
 * overflows and wraps, producing a threshold that is wildly wrong, for
 * `minGapThresholdMins` that silently reclassifies every gap as major or minor.
 *
 * The correct form is `minutes * 60L * 1000L`, or `minutes.toLong() * 60_000L`.
 *
 * This test reads the actual source tree, so a reintroduced `* 60 * 1000L`
 * anywhere in the app fails the build instead of shipping.
 *
 * The guard is spelling-based, not type-based: it flags any multiply by a
 * millisecond-scale constant whose literal lacks the `L` suffix (60_000,
 * 3_600_000, 86_400_000, and their bare-digit forms), plus the mixed
 * `* 60 * 1000L` chain. A Long receiver multiplied by a bare Int literal is
 * technically safe (`Long * Int` widens), but a literal that must be
 * long-suffixed to be correct is the spelling this codebase standardizes on;
 * if the scanner ever flags a line, add the `L` or `.toLong()` the receiver.
 */
class IntegerPromotionGuardTest {

    private fun sourceFiles(): List<File> {
        val main = File("src/main/kotlin")
        assertTrue("source tree not found at ${main.absolutePath}", main.isDirectory)
        return main.walkTopDown().filter { it.extension == "kt" }.toList()
    }

    private val hazards = listOf(
        Regex("""\*\s*60\s*\*\s*1000L"""),
        Regex("""\*\s*60_000(?![\dL])"""),
        Regex("""\*\s*60000(?![\dL])"""),
        Regex("""\*\s*3_600_000(?![\dL])"""),
        Regex("""\*\s*3600000(?![\dL])"""),
        Regex("""\*\s*86_400_000(?![\dL])"""),
        Regex("""\*\s*86400000(?![\dL])""")
    )

    @Test
    fun `no source file multiplies minutes as Int before widening to Long`() {
        val offenders = mutableListOf<String>()
        for (file in sourceFiles()) {
            file.readLines().forEachIndexed { i, line ->
                if (hazards.any { it.containsMatchIn(line) }) {
                    offenders.add("${file.name}:${i + 1}  ${line.trim()}")
                }
            }
        }
        assertEquals(
            "These lines evaluate as Int*Int and can overflow. Use '* 60L * 1000L'.\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `no source file divides a millisecond duration using float arithmetic`() {
        val offenders = mutableListOf<String>()
        // Matches "something.toFloat() / 1000" and similar precision-losing forms.
        val hazard = Regex("""toFloat\(\)\s*/|\d\.\d+f?\s*/\s*(1000|60|3600|60000|86400)""")
        for (file in sourceFiles()) {
            file.readLines().forEachIndexed { i, line ->
                if (hazard.containsMatchIn(line) && "format" !in line.lowercase()) {
                    offenders.add("${file.name}:${i + 1}  ${line.trim()}")
                }
            }
        }
        assertEquals(
            "Float division loses millisecond precision. Keep the arithmetic in Long.\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    /**
     * Guards the offline-total bug class, which was found in TWO places: the
     * dashboard widget and the exported daily summary. Both were summing raw
     * offline durations instead of sharded ones.
     *
     * The invariant every consumer must follow: an offline total is always
     * `getShardedOfflineIntervals(...).sumOf { second - first }`, never
     * `offlineItems.sumOf { it.durationInMillis }`.
     */
    @Test
    fun `offline totals are never summed raw from offline items`() {
        val offenders = mutableListOf<String>()
        val hazard = Regex("""offlineItems\s*\.\s*sumOf\s*\{\s*it\.durationInMillis\s*}""")
        for (file in sourceFiles()) {
            file.readLines().forEachIndexed { i, line ->
                if (hazard.containsMatchIn(line)) {
                    offenders.add("${file.name}:${i + 1}  ${line.trim()}")
                }
            }
        }
        assertEquals(
            "Raw offline sums double-count app overlap. Use getShardedOfflineIntervals() " +
                "and sum the sharded intervals instead.\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }
}
