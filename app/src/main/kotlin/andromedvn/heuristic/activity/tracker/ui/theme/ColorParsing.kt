package andromedvn.heuristic.activity.tracker.ui.theme

import java.util.Locale

/**
 * Parses a user-typed hex colour into a 0xAARRGGBB Long, or null if invalid.
 * Accepts 6 digits (RRGGBB, alpha forced opaque) or 8 digits (AARRGGBB),
 * with an optional leading '#', any case, and surrounding whitespace.
 *
 * Pure - no Android - so it is unit-testable on the JVM. See ColorParsingTest.
 */
fun parseHexColor(input: String): Long? {
    val cleaned = input.trim().removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    if (!cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    val value = cleaned.toLongOrNull(16) ?: return null
    return if (cleaned.length == 6) 0xFF000000L or value else value
}

/** Formats a stored 0xAARRGGBB Long back into the #RRGGBB the text field shows. */
fun formatHexColor(argb: Long): String = String.format(Locale.ROOT, "#%06X", 0xFFFFFFL and argb)

/**
 * Widens a Compose signed ARGB Int into the unsigned 32-bit Long that
 * UserSettings.staticColor / extractedColor store. A colour whose alpha is
 * below 0x80 comes back from toArgb() negative; without this it would not
 * survive a round trip through the settings store.
 */
fun argbToStoredLong(argb: Int): Long = argb.toLong() and 0xFFFFFFFFL
