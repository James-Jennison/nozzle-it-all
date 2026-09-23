package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class OverlayBuildersTest {
    private fun disc(kind: PaintKind, n: FloatArray = floatArrayOf(0f, 0f, 1f)) = PaintDisc(kind, floatArrayOf(1f, 2f, 3f), n, 2f)

    @Test fun discsAreGroupedByKindWithOneFanPerDab() {
        val groups = OverlayBuilders.paintGroups(listOf(disc(PaintKind.SUPPORT_ENFORCER), disc(PaintKind.SUPPORT_ENFORCER), disc(PaintKind.SEAM_BLOCKER)))
        assertEquals(2, groups.size)
        val enf = groups.first { it.color.contentEquals(OverlayBuilders.paintColor(PaintKind.SUPPORT_ENFORCER)) }
        assertEquals(2 * 14 * 3 * 6, enf.vertices.size); assertFalse(enf.lines)
        assertTrue(OverlayBuilders.paintGroups(emptyList()).isEmpty())
    }

    @Test fun discVerticesLieOnTheSurfacePlaneWithinTheRadius() {
        val n = floatArrayOf(1f, 0f, 0f)
        val g = OverlayBuilders.paintGroups(listOf(disc(PaintKind.SUPPORT_BLOCKER, n))).single()
        var i = 0
        while (i < g.vertices.size) {
            assertEquals("all points sit just above the plane x=1", 1f + 0.06f, g.vertices[i], 1e-4f)
            val dy = g.vertices[i + 1] - 2f; val dz = g.vertices[i + 2] - 3f
            assertTrue(Math.hypot(dy.toDouble(), dz.toDouble()) <= 2.0001)
            assertArrayEquals(n, g.vertices.copyOfRange(i + 3, i + 6), 1e-6f)
            i += 6
        }
    }

    @Test fun regionOutlinesHaveTheExpectedLineCounts() {
        fun v(shape: VolumeShape) = ShapeVolume(VolumeKind.MODIFIER, shape, floatArrayOf(0f, 0f, 0f), floatArrayOf(10f, 20f, 30f))
        val box = OverlayBuilders.volumeOutline(v(VolumeShape.BOX), floatArrayOf(100f, 100f, 5f))
        assertTrue(box.lines); assertEquals(12 * 2 * 6, box.vertices.size)
        assertEquals(24 * 2 * 2 * 6 + 4 * 2 * 6, OverlayBuilders.volumeOutline(v(VolumeShape.CYLINDER), floatArrayOf(0f, 0f, 0f)).vertices.size)
        assertEquals(3 * 24 * 2 * 6, OverlayBuilders.volumeOutline(v(VolumeShape.SPHERE), floatArrayOf(0f, 0f, 0f)).vertices.size)
        // the box is centred where asked: x spans 95..105
        val xs = (0 until box.vertices.size step 6).map { box.vertices[it] }
        assertEquals(95f, xs.min(), 1e-4f); assertEquals(105f, xs.max(), 1e-4f)
    }

    @Test fun strokesLandOnTheMeshAndFaceTheViewer() {
        val box = TriMesh(floatArrayOf(0f,0f,0f, 0f,10f,0f, 10f,10f,0f,  0f,0f,0f, 10f,10f,0f, 10f,0f,0f,  0f,0f,10f, 10f,0f,10f, 10f,10f,10f,  0f,0f,10f, 10f,10f,10f, 0f,10f,10f))
        val stroke = PaintStroke(PaintKind.SUPPORT_ENFORCER, floatArrayOf(5f, 5f, 30f), floatArrayOf(0f, 0f, -1f), 2f)
        val discs = OverlayBuilders.discsFor(listOf(stroke, stroke.copy(origin = floatArrayOf(50f, 50f, 30f))), box, floatArrayOf(0f, 0f, 0f))
        assertEquals(1, discs.size)
        assertEquals(10f, discs[0].center[2], 1e-4f); assertEquals(1f, discs[0].normal[2], 1e-4f)
    }
}
