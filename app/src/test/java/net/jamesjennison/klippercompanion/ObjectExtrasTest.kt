package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class ObjectExtrasTest {
    @Test fun paintStrokesRoundTripAndDropMalformedRecords() {
        val strokes = listOf(PaintStroke(PaintKind.SUPPORT_ENFORCER, floatArrayOf(1f, 2f, 3f), floatArrayOf(0f, 0f, -1f), 2.5f), PaintStroke(PaintKind.SEAM_BLOCKER, floatArrayOf(-4.5f, 0f, 9f), floatArrayOf(0f, 1f, 0f), 1f))
        assertEquals(strokes, PaintCodec.decode(PaintCodec.encode(strokes)))
        assertTrue(PaintCodec.decode(null).isEmpty()); assertTrue(PaintCodec.decode("").isEmpty())
        val mixed = PaintCodec.encode(strokes.take(1)) + ";9,1,2,3,4,5,6,1;0,1,2,3,4,5,6,-1;0,NaN,2,3,4,5,6,1;junk;0,1,2"
        assertEquals(1, PaintCodec.decode(mixed).size)
    }
    @Test fun materialStrokesCarryTheirToolThroughTheCodec() {
        val strokes = listOf(PaintStroke(PaintKind.MATERIAL, floatArrayOf(1f, 2f, 3f), floatArrayOf(0f, 0f, -1f), 2f, tool = 3), PaintStroke(PaintKind.SUPPORT_BLOCKER, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, -1f), 1f))
        val text = PaintCodec.encode(strokes)
        assertTrue(text.startsWith("13,")) // 10 + tool 3
        assertEquals(strokes, PaintCodec.decode(text))
        assertEquals(3, PaintCodec.decode(text)[0].tool)
        assertTrue("a bare code 10 has no tool", PaintCodec.decode("10,0,0,0,0,0,-1,1").isEmpty())
        assertTrue("a tool beyond the maximum is dropped", PaintCodec.decode("${PaintKind.MATERIAL_BASE + PaintKind.MAX_TOOL + 1},0,0,0,0,0,-1,1").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { PaintStroke(PaintKind.MATERIAL, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, -1f), 1f) }
    }
    @Test fun paintIsBounded() { assertEquals(PaintCodec.MAX_STROKES, PaintCodec.decode(List(PaintCodec.MAX_STROKES + 50) { "0,0,0,0,0,0,-1,1" }.joinToString(";")).size) }

    @Test fun volumesRoundTripAndSanitizeModifierOverrides() {
        val v = listOf(
            ShapeVolume(VolumeKind.MODIFIER, VolumeShape.BOX, floatArrayOf(1f, 2f, 3f), floatArrayOf(10f, 10f, 5f), mapOf("sparse_infill_density" to "80%", "wall_loops" to "4")),
            ShapeVolume(VolumeKind.SUPPORT_BLOCKER, VolumeShape.CYLINDER, floatArrayOf(0f, 0f, 0f), floatArrayOf(6f, 6f, 20f)),
        )
        val decoded = VolumeCodec.decode(VolumeCodec.encode(v))
        assertEquals(v[1], decoded[1]); assertEquals(VolumeKind.MODIFIER, decoded[0].kind)
        assertEquals(mapOf("wall_loops" to "4", "sparse_infill_density" to "80%"), decoded[0].overrides)
        assertEquals(mapOf("sparse_infill_density" to "40%"), VolumeCodec.sanitizeModifier(mapOf("sparse_infill_density" to "40", "bogus" to "1")))
        assertTrue(VolumeCodec.sanitizeModifier(mapOf("sparse_infill_density" to "400")).isEmpty())
    }
    @Test fun volumesRejectBadShapesSizesAndUnknownKeys() {
        val bad = "modifier:box:0,0,0:0,5,5:;modifier:cone:0,0,0:5,5,5:;blocker:box:0,0:5,5,5:;blocker:sphere:0,0,0:5,5,5:;modifier:box:0,0,0:5,5,5:machine_start_gcode=M112|wall_loops=3"
        val d = VolumeCodec.decode(bad)
        assertEquals(2, d.size); assertEquals(VolumeKind.SUPPORT_BLOCKER, d[0].kind)
        assertEquals(mapOf("wall_loops" to "3"), d[1].overrides)
    }
    @Test fun blockerVolumesNeverCarryOverrides() {
        assertTrue(VolumeCodec.decode("blocker:box:0,0,0:5,5,5:wall_loops=3").single().overrides.isEmpty())
    }
}
