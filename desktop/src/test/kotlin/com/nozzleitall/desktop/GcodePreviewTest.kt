package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.GcodePreview
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GcodePreviewTest {
    private fun preview(gcode: String): GcodePreview { val f = File.createTempFile("preview", ".gcode"); f.writeText(gcode); return try { GcodePreview.parse(f) } finally { f.delete() } }

    private val sample = """
        ;LAYER_CHANGE
        G1 Z0.2 F600
        ;TYPE:Outer wall
        G1 X0 Y0 F3000
        G1 X10 Y0 E1 F1800
        ;TYPE:Sparse infill
        G1 X10 Y10 E2 F6000
        T1
        ;TYPE:Wipe tower
        G1 X0 Y10 E3 F3600
        ;LAYER_CHANGE
        G1 Z0.4
        ;TYPE:Top surface
        G1 X0 Y0 E4
    """.trimIndent()

    @Test fun lineTypesAreReadFromTypeComments() {
        val p = preview(sample)
        val names = p.layers.flatMap { l -> l.kinds.map { GcodePreview.FEATURES.getOrNull(it.toInt())?.first } }
        assertEquals(listOf("Outer wall", "Sparse infill", "Prime tower", "Top surface"), names)
    }

    @Test fun speedsAreMillimetresPerSecond() {
        val p = preview(sample)
        assertEquals(listOf(30f, 100f, 60f), p.layers[0].speeds.toList())
        assertEquals(30f to 100f, p.speedRange)
    }

    @Test fun theToolsUsedAreListed() = assertEquals(listOf(0, 1), preview(sample).toolsUsed)

    @Test fun unknownNamesFallBackSensibly() {
        assertEquals(GcodePreview.featureIndex("Wipe tower"), GcodePreview.featureIndex("Prime tower"))
        assertEquals(GcodePreview.OTHER, GcodePreview.featureIndex("Something new"))
        assertEquals(GcodePreview.featureIndex("Bridge"), GcodePreview.featureIndex("Internal bridge"))
    }

    @Test fun theSpeedColourRunsFromBlueToRed() {
        val slow = GcodePreview.speedColour(0f); val fast = GcodePreview.speedColour(1f)
        assertTrue((slow and 0xFF) > (slow shr 16 and 0xFF)); assertTrue((fast shr 16 and 0xFF) > (fast and 0xFF))
    }
}
