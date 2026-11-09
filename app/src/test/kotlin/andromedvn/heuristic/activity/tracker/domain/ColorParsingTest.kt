package andromedvn.heuristic.activity.tracker.domain

import andromedvn.heuristic.activity.tracker.ui.theme.formatHexColor
import andromedvn.heuristic.activity.tracker.ui.theme.parseHexColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorParsingTest {

    @Test
    fun `six digits get an opaque alpha channel`() {
        assertEquals(0xFFFF8000L, parseHexColor("FF8000"))
    }

    @Test
    fun `leading hash is optional`() {
        assertEquals(0xFFFF8000L, parseHexColor("#FF8000"))
    }

    @Test
    fun `eight digits are respected verbatim`() {
        assertEquals(0x80FF8000L, parseHexColor("80FF8000"))
    }

    @Test
    fun `parsing is case insensitive`() {
        assertEquals(parseHexColor("#ff8000"), parseHexColor("#FF8000"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(0xFFFF8000L, parseHexColor("  #FF8000  "))
    }

    @Test
    fun `wrong length is rejected`() {
        assertNull(parseHexColor("#FFF"))
        assertNull(parseHexColor("#FF80000"))
    }

    @Test
    fun `non hex characters are rejected`() {
        assertNull(parseHexColor("#GGGGGG"))
        assertNull(parseHexColor("#FF 800"))
    }

    @Test
    fun `empty input is rejected`() {
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#"))
    }

    @Test
    fun `round trip preserves the colour`() {
        assertEquals("#FF8000", formatHexColor(parseHexColor("#FF8000")!!))
        assertEquals("#3498DB", formatHexColor(parseHexColor("3498DB")!!))
    }

    @Test
    fun `format always emits six upper case digits with a hash`() {
        assertEquals("#FF8000", formatHexColor(0xFFFF8000L))
        assertEquals("#000000", formatHexColor(0xFF000000L))
    }

    @Test
    fun `format discards the alpha channel`() {
        assertEquals("#FF8000", formatHexColor(0x80FF8000L))
    }
}
