package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

// Phase 5 (Consumer Slicer Plan §16): "per-material toolpath coloring" - the real hex-to-RGB
// conversion SlicedPreview uses to color the extrusion path by the project's own chosen
// material, rather than always the fixed print-orange default.
class SlicedPreviewTest {
    @Test fun parsesARealSixDigitHexColor() {
        val rgb = parseHexColor("FF7043")
        assertNotNull(rgb)
        assertEquals(1f, rgb!![0], 1e-3f)
        assertEquals(0x70 / 255f, rgb[1], 1e-3f)
        assertEquals(0x43 / 255f, rgb[2], 1e-3f)
    }

    @Test fun acceptsALeadingHash() {
        val rgb = parseHexColor("#00FF00")
        assertNotNull(rgb)
        assertEquals(0f, rgb!![0], 1e-3f)
        assertEquals(1f, rgb[1], 1e-3f)
        assertEquals(0f, rgb[2], 1e-3f)
    }

    @Test fun nullOrMalformedInputProducesNull() {
        assertNull(parseHexColor(null))
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("not-a-color"))
        assertNull(parseHexColor("FFF")) // 3-digit shorthand not supported - a real 6-digit value only
        assertNull(parseHexColor("GGGGGG")) // not valid hex digits
    }
}
