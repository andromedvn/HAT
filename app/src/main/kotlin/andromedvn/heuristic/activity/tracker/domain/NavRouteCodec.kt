package andromedvn.heuristic.activity.tracker.domain

/**
 * Percent-encoder for navigation path segments built from free user text.
 *
 * WHY THIS EXISTS (crash, root-caused 2026-10-15): a label title is free text
 * and is used as the `{id}` argument of the `details/{type}/{id}` route. The
 * destination decodes with `android.net.Uri.decode`, and two of three callers
 * encoded with `android.net.Uri.encode` - but the dashboard caller passed the
 * raw title. A title like "Work/Study" (real user data) produced
 * `details/offline/Work/Study`: four path segments where the route pattern
 * expects three, Navigation Compose found no matching destination, and the
 * app crashed with IllegalArgumentException on a list-item tap.
 *
 * This is the single, JVM-testable implementation all callers share. It keeps
 * exactly the bytes `android.net.Uri.encode` keeps - alphanumerics and
 * `-_!~*'()` - and percent-encodes everything else as UTF-8, uppercase hex.
 * Note `+` is NOT in the safe set: `Uri.decode` converts a literal `+` to a
 * space, so a `+` in a title must travel as `%2B` or it round-trips wrong.
 */
object NavRouteCodec {

    private val SAFE: BooleanArray = BooleanArray(128).also { safe ->
        for (c in '0'..'9') safe[c.code] = true
        for (c in 'A'..'Z') safe[c.code] = true
        for (c in 'a'..'z') safe[c.code] = true
        // Mirrors android.net.Uri.encode's passthrough set (plus '.'); '.' matters
        // so package names read unescaped in routes, as they always have.
        for (c in "-._!~*'()") safe[c.code] = true
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    /** Percent-encode [segment] so it survives as exactly one path segment. */
    fun encodeSegment(segment: String): String {
        val sb = StringBuilder(segment.length + 16)
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c < 128 && SAFE[c]) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    /** The `details` route for an offline label, safe for any title. */
    fun offlineDetailsRoute(title: String): String =
        "details/offline/" + encodeSegment(title)

    /** The `details` route for an app package (safe by Android's own rules, encoded anyway). */
    fun appDetailsRoute(packageName: String): String =
        "details/app/" + encodeSegment(packageName)
}
