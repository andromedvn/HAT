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
 * overflows and wraps, producing a threshold that is wildly wrong — for
 * `minGapThresholdMins` that silently reclassifies every gap as major or minor.
 *
 * The correct form is `minutes * 60L * 1000L`.
 *
 * This test reads the actual source tree, so a reintroduced `* 60 * 1000L`
 * anywhere in the app fails the build instead of shipping.
 */
class IntegerPromotionGuardTest {

    private fun sourceFiles(): List<File> {
        val main = File("src/main/kotlin")
        assertTrue("source tree not found at ${main.absolutePath}", main.isDirectory)
        return main.walkTopDown().filter { it.extension == "kt" }.toList()
    }

    @Test
    fun `no source file multiplies minutes as Int before widening to Long`() {
        val offenders = mutableListOf<String>()
        val hazard = Regex("""\*\s*60\s*\*\s*1000L""")
        for (file in sourceFiles()) {
            file.readLines().forEachIndexed { i, line ->
                if (hazard.containsMatchIn(line)) {
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
}
