package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class MeshEditTest {
    // Axis-aligned box [0,x]×[0,y]×[0,z] with outward CCW triangles.
    private fun box(x: Float, y: Float, z: Float): TriMesh {
        val p = { a: Int, b: Int, c: Int -> floatArrayOf(if (a == 1) x else 0f, if (b == 1) y else 0f, if (c == 1) z else 0f) }
        fun quad(q: List<FloatArray>) = q[0] + q[1] + q[2] + q[0] + q[2] + q[3]
        return TriMesh(
            quad(listOf(p(0,0,0), p(0,1,0), p(1,1,0), p(1,0,0))) + quad(listOf(p(0,0,1), p(1,0,1), p(1,1,1), p(0,1,1))) +
            quad(listOf(p(0,0,0), p(1,0,0), p(1,0,1), p(0,0,1))) + quad(listOf(p(1,0,0), p(1,1,0), p(1,1,1), p(1,0,1))) +
            quad(listOf(p(1,1,0), p(0,1,0), p(0,1,1), p(1,1,1))) + quad(listOf(p(0,1,0), p(0,0,0), p(0,0,1), p(0,1,1))),
        )
    }
    private fun size(m: TriMesh) = Triple(m.maxAlong(0) - m.minAlong(0), m.maxAlong(1) - m.minAlong(1), m.maxAlong(2) - m.minAlong(2))
    private fun volume(m: TriMesh): Double { var s = 0.0; for (t in 0 until m.triangleCount) { val b = t * 9; val v = m.v
        s += (v[b] * (v[b+4]*v[b+8]-v[b+5]*v[b+7]) - v[b+1] * (v[b+3]*v[b+8]-v[b+5]*v[b+6]) + v[b+2] * (v[b+3]*v[b+7]-v[b+4]*v[b+6])) / 6.0 }; return s }

    @Test fun boxFixtureIsAClosedOutwardSolid() { assertEquals(2 * 3 * 4.0, volume(box(2f, 3f, 4f)), 1e-4) }

    @Test fun mirrorKeepsSizeAndPositionButFlipsChirality() {
        val wedge = TriMesh(box(4f, 2f, 2f).v.map { it }.toFloatArray().also { a -> var i = 0; while (i < a.size) { if (a[i + 2] > 1f && a[i] < 1f) a[i] = 2f; i += 3 } })
        val m = MeshEdit.mirror(wedge, 0)
        assertEquals(size(wedge).first, size(m).first, 1e-4f)
        assertEquals(volume(wedge), volume(m), 1e-3) // still an outward (positive-volume) solid
        assertNotEquals(wedge.v.toList(), m.v.toList())
        val twice = MeshEdit.mirror(m, 0)
        assertArrayEquals(wedge.v.sortedArray(), twice.v.sortedArray(), 1e-4f)
    }

    @Test fun layingATallFaceFlatRotatesTheBoxOntoIt() {
        val m = box(2f, 10f, 10f) // the 10x10 face on -X ... normal (-1,0,0)
        val laid = MeshEdit.layOnFace(m, floatArrayOf(-1f, 0f, 0f))
        val (sx, sy, sz) = size(laid)
        assertEquals(2f, sz, 1e-3f); assertEquals(10f, sx, 1e-3f); assertEquals(10f, sy, 1e-3f)
        assertEquals(0f, laid.minAlong(2), 1e-4f); assertEquals(volume(m), volume(laid), 1e-2)
    }

    @Test fun layingAFaceAlreadyDownIsANoOpAndOppositeIsHandled() {
        val m = box(3f, 4f, 5f)
        assertArrayEquals(m.v, MeshEdit.layOnFace(m, floatArrayOf(0f, 0f, -1f)).v, 1e-5f)
        val flipped = MeshEdit.layOnFace(m, floatArrayOf(0f, 0f, 1f)) // top face down = upside down
        assertEquals(5f, size(flipped).third, 1e-3f); assertEquals(0f, flipped.minAlong(2), 1e-4f); assertEquals(volume(m), volume(flipped), 1e-2)
    }

    @Test fun autoOrientStandsAFlatSlabBackDownOnItsBigFace() {
        val standing = box(2f, 10f, 10f)
        val o = MeshEdit.autoOrient(standing)!!
        val laid = MeshEdit.applyOrientation(standing, o)
        assertEquals(2f, size(laid).third, 1e-3f)
        assertTrue(o.score < o.currentScore)
    }

    @Test fun autoOrientLeavesAnAlreadyGoodPartAlone() { assertNull(MeshEdit.autoOrient(box(10f, 10f, 2f))) }

    @Test fun autoOrientRemovesOverhangsFromAWedgeStandingOnItsTip() {
        // Triangular prism (roof) with the apex DOWN: two big 45°+ overhang faces. Best is to lay a face flat.
        val h = 10f; val w = 10f; val l = 20f
        fun tri(a: FloatArray, b: FloatArray, c: FloatArray) = a + b + c
        val a0 = floatArrayOf(0f, 0f, 0f); val a1 = floatArrayOf(w, 0f, h); val a2 = floatArrayOf(-w, 0f, h)  // apex down profile at y=0
        val b0 = floatArrayOf(0f, l, 0f); val b1 = floatArrayOf(w, l, h); val b2 = floatArrayOf(-w, l, h)
        val prism = TriMesh(tri(a0, a2, a1) + tri(b0, b1, b2) + tri(a0, b0, b2) + tri(a0, b2, a2) + tri(a0, a1, b1) + tri(a0, b1, b0) + tri(a1, a2, b2) + tri(a1, b2, b1))
        val o = MeshEdit.autoOrient(prism)
        assertNotNull(o); assertTrue(o!!.score < o.currentScore)
    }

    @Test fun rayHitFindsTheNearestFaceAndIgnoresMisses() {
        val m = box(10f, 10f, 10f)
        val hit = MeshEdit.rayHit(m, floatArrayOf(5f, 5f, 50f), floatArrayOf(0f, 0f, -1f))!!
        assertEquals(10f, hit.point[2], 1e-3f); assertEquals(40f, hit.distance, 1e-3f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), MeshEdit.triangleNormal(m, hit.triangle), 1e-5f)
        assertNull(MeshEdit.rayHit(m, floatArrayOf(50f, 50f, 50f), floatArrayOf(0f, 0f, -1f)))
        assertNull(MeshEdit.rayHit(m, floatArrayOf(5f, 5f, 50f), floatArrayOf(0f, 0f, 1f))) // pointing away
        assertEquals(50f, MeshEdit.distance(floatArrayOf(0f, 0f, 0f), floatArrayOf(30f, 40f, 0f)), 1e-4f)
    }

    @Test fun placedFrameRoundTripsRaysThroughOffsetRotationAndScale() {
        val t = ModelTransform(offsetXMm = 30f, offsetYMm = -12f, rotationZDeg = 37f, scale = 2f)
        val frame = PlacedFrame(t, floatArrayOf(5f, 5f, 0f))
        val local = floatArrayOf(7f, 3f, 4f)
        val world = frame.toWorld(local)
        // a ray straight down through the world point must hit the local point when mapped back
        val (o, d) = frame.rayToLocal(floatArrayOf(world[0], world[1], world[2] + 100f), floatArrayOf(0f, 0f, -1f))
        val s = (o[2] - local[2]) / -d[2]
        assertEquals(local[0], o[0] + d[0] * s, 1e-3f); assertEquals(local[1], o[1] + d[1] * s, 1e-3f)
        assertEquals(2f, MeshEdit.distance(frame.toWorld(floatArrayOf(0f, 0f, 0f)), frame.toWorld(floatArrayOf(1f, 0f, 0f))), 1e-4f) // scale applies to measured distances
    }

    @Test fun binaryStlHasTheExpectedSizeAndHeader() {
        val out = java.io.ByteArrayOutputStream(); MeshEdit.writeBinaryStl(box(1f, 1f, 1f), out)
        assertEquals(84 + 50 * 12, out.size())
        assertEquals(12, java.nio.ByteBuffer.wrap(out.toByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(80))
    }
}
