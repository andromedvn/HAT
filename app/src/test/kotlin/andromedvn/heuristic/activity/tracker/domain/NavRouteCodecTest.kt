package andromedvn.heuristic.activity.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the "/" crash (2026-10-15).
 *
 * A label title is free user text and rides as the {id} segment of the
 * `details/{type}/{id}` route. The dashboard caller once passed it raw, so a
 * real label named "Work/Study" produced a 4-segment route that matched no
 * destination and crashed the app on tap. The user's own vault contained that
 * exact label, so this is not a hypothetical.
 *
 * NavRouteCodec mirrors android.net.Uri.encode's allow-list exactly
 * (alphanumerics plus `-_!~*'()`), so its output round-trips through the
 * Uri.decode that MainActivity applies. Two behaviours are pinned here that
 * android.net.Uri guarantees and a naive encoder would get wrong:
 *
 *  - `/` must be escaped: one title is one path segment, always.
 *  - `+` must be escaped as %2B, because Uri.decode converts a literal `+`
 *    into a space; a raw `+` would silently rename the label on arrival.
 *
 * Pure JVM: no android.net.Uri here, so the assertions check the invariants
 * and a decode mirror of our own encoder.
 */
class NavRouteCodecTest {

    /** Minimal mirror of Uri.decode for round-trip checks: %XX to bytes, rebuilt as UTF-8. */
    private fun decodeMirror(s: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 3 <= s.length) {
                bytes.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                // The encoder emits only ASCII safe chars outside %XX escapes.
                bytes.write(c.code)
                i++
            }
        }
        return bytes.toString("UTF-8")
    }

    @Test
    fun `the crash title produces a valid three segment route`() {
        val route = NavRouteCodec.offlineDetailsRoute("Work/Study")
        val segments = route.split('/')
        assertEquals("details", segments[0])
        assertEquals("offline", segments[1])
        assertEquals("the whole title is exactly one segment", "Work%2FStudy", segments[2])
        assertEquals(3, segments.size)
    }

    @Test
    fun `every encoded title keeps the route at three segments`() {
        val matrix = listOf(
            "Sleep", "Work/Study", "work/studu", "a/b/c/d/e", "/", "//", "why?",
            "100% done", "C++ time", "question?", "hash#", "amp&ersand", "colon:time",
            "back\\slash", "star*wars", "single'quote", "double\"quote", "<angle>",
            "space here", "tab\tinside", "emoji 🌙 sleep", "ünïcödé",
            "", " ", "0", "null", "THE WORST / TITLE / EVER / 2026"
        )
        for (title in matrix) {
            val route = NavRouteCodec.offlineDetailsRoute(title)
            val segments = route.split('/')
            assertEquals("route must have exactly 3 segments for: \"$title\"", 3, segments.size)
            assertEquals("details", segments[0])
            assertEquals("offline", segments[1])
            assertFalse("segment must not be unescaped for: \"$title\"", segments[2] == title && title.contains('/'))
            assertEquals("round-trip must restore: \"$title\"", title, decodeMirror(segments[2]))
        }
    }

    @Test
    fun `plus is escaped because Uri decode turns plus into space`() {
        // The space must travel as %20 too: spaces are not in the safe set.
        assertEquals("C%2B%2B%20time", NavRouteCodec.encodeSegment("C++ time"))
    }

    @Test
    fun `safe characters pass through untouched`() {
        assertEquals("Sleep_2026-final!", NavRouteCodec.encodeSegment("Sleep_2026-final!"))
        assertEquals("tilde~star*quote'paren()dot-.az09", NavRouteCodec.encodeSegment("tilde~star*quote'paren()dot-.az09"))
    }

    @Test
    fun `multi-byte characters encode as utf-8 percent triplets and round-trip`() {
        val encoded = NavRouteCodec.encodeSegment("🌙")
        // U+1F319 is four UTF-8 bytes, so twelve %XX characters.
        assertEquals(12, encoded.length)
        assertTrue(encoded.matches(Regex("(%[0-9A-F]{2}){4}")))
        assertEquals("🌙", decodeMirror(encoded))
    }

    @Test
    fun `a very long title stays one segment`() {
        val long = "x".repeat(10_000) + "/" + "y".repeat(10_000)
        val route = NavRouteCodec.offlineDetailsRoute(long)
        assertEquals(3, route.split('/').size)
    }

    @Test
    fun `app package routes stay three segments too`() {
        val route = NavRouteCodec.appDetailsRoute("andromedvn.heuristic.activity.tracker")
        assertEquals("details/app/andromedvn.heuristic.activity.tracker", route)
        assertEquals(3, route.split('/').size)
    }
}
