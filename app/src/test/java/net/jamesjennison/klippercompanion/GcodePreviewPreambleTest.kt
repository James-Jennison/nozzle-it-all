package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class GcodePreviewPreambleTest {
    private fun parse(g: String) = GcodePreview.parse(g.byteInputStream())
    private fun square(z: Double, x0: Double = 130.0, y0: Double = 130.0, s: Double = 10.0) =
        "G1 Z$z F600\nG1 X$x0 Y$y0\nG1 X${x0 + s} Y$y0 E1\nG1 X${x0 + s} Y${y0 + s} E1\nG1 X$x0 Y${y0 + s} E1\nG1 X$x0 Y$y0 E1\n"

    // The Snapmaker U1's own start gcode: a 100 mm prime line at the bed edge (y=1), before the first ;LAYER_CHANGE.
    private val start = "G90\nM83\nG0 X85 Y1 Z2 F18000\nG1 Z0.2\nG1 X185 E15 F360\nG1 Z1.5\n"

    @Test fun theStartupPrimeLineIsDroppedAndTheFramingCoversTheModelOnly() {
        val g = start + ";LAYER_CHANGE\n;Z:0.25\n" + square(0.25) + ";LAYER_CHANGE\n;Z:0.45\n" + square(0.45)
        val t = parse(g)
        assertTrue("no preamble segment survives", t.segments.none { it.preamble })
        assertEquals("only the model's two layers", 2, t.heights.size)
        assertEquals(0.25f, t.heights.first(), 1e-4f)
        assertEquals("layers are renumbered from 0", 0, t.segments.minOf { it.layer })
        assertEquals(1, t.segments.maxOf { it.layer })
        assertTrue("the y=1 prime line no longer stretches the bounds", t.segments.minOf { minOf(it.y1, it.y2) } >= 129.9f)
        assertTrue(t.segments.minOf { minOf(it.x1, it.x2) } >= 129.9f)
    }

    @Test fun aFileWithoutLayerMarkersKeepsEverything() {
        val t = parse(start + square(0.2) + square(0.4))
        assertTrue(t.segments.any { minOf(it.y1, it.y2) < 2f }) // the prime line is still there: nothing says it is not the model
        assertTrue("nothing is flagged", t.segments.none { it.preamble })
        assertEquals(2, t.heights.size) // 0.2 (the prime line and the first square share it) and 0.4
    }

    @Test fun aPrimeLineAtTheSameHeightAsLayerOneIsStillDropped() {
        val g = "G90\nM83\nG0 X85 Y1 Z0.2 F18000\nG1 X185 E15 F360\n;LAYER_CHANGE\n;Z:0.2\n" + square(0.2)
        val t = parse(g)
        assertEquals(listOf(0.2f), t.heights)
        assertTrue(t.segments.all { !it.preamble && it.layer == 0 })
        assertTrue(t.segments.minOf { minOf(it.y1, it.y2) } >= 129.9f)
    }

    @Test fun curaStyleLayerMarkersCountToo() {
        val t = parse(start + ";LAYER:0\n" + square(0.3))
        assertTrue(t.segments.none { it.preamble })
        assertTrue(t.segments.minOf { minOf(it.y1, it.y2) } >= 129.9f)
    }

    // Real output: OrcaSlicer's G-code for a 42 mm model on a Snapmaker U1 (start G-code with its 100 mm prime line at y=1, then three layers).
    @Test fun realSnapmakerU1OutputFramesTheModelNotThePrimeLine() {
        val t = javaClass.getResourceAsStream("/u1_start_and_three_layers.gcode")!!.use { GcodePreview.parse(it) }
        assertEquals("three model layers, not four (the prime line was a phantom layer)", 3, t.heights.size)
        assertEquals(0.25f, t.heights.first(), 1e-3f)
        val minY = t.segments.minOf { minOf(it.y1, it.y2) }; val maxY = t.segments.maxOf { maxOf(it.y1, it.y2) }
        val minX = t.segments.minOf { minOf(it.x1, it.x2) }; val maxX = t.segments.maxOf { maxOf(it.x1, it.x2) }
        assertTrue("bounds start near the model (~114 mm), not the bed edge (1 mm): minY=$minY", minY > 100f)
        assertTrue("model spans well under 60 mm: ${maxX - minX} x ${maxY - minY}", maxX - minX < 60f && maxY - minY < 60f)
    }
}
