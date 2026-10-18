package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.DepthSurface
import com.nozzleitall.desktop.prepare.DepthSurfaceCache
import com.nozzleitall.desktop.prepare.Projector
import com.nozzleitall.desktop.prepare.ViewCamera
import org.junit.Assert.*
import org.junit.Test

class DepthSurfaceTest {
    @Test fun depthDenominatorMatchesActualProjectorOnBothSidesOfItsClamp() {
        val camera = ViewCamera().apply { distance = 100f; pitch = 0f; yaw = 0f }
        val projector = Projector(100f,100f,camera,0f,0f)
        for (toward in listOf(projector.eye*.79f,projector.eye*.81f)) {
            val screen = FloatArray(3)
            projector.p(1f,0f,toward,screen,0)
            assertEquals(-toward,screen[2],0f)
            val denominator = maxOf(projector.eye+screen[2],projector.eye*.2f)
            assertEquals(50f+1.6f*projector.eye/denominator,screen[0],0.0001f)
        }
    }
    @Test fun frameStorageIsReusedAndBothDepthAndColorAreCleared() {
        val cache = DepthSurfaceCache()
        val a = cache.frame(12f,12f)
        val triangle = floatArrayOf(0f,0f,0f,10f,0f,0f,0f,10f,0f)
        a.triangle(triangle,0,IntArray(3){red},0,100f)
        assertEquals(red,color(a,1,1))
        val b = cache.frame(12f,12f)
        assertSame(a,b); assertEquals(0,color(b,1,1))
        val farther = triangle.copyOf().also { for (i in 2 until it.size step 3) it[i] = 10f }
        b.triangle(farther,0,IntArray(3){blue},0,100f)
        assertEquals(blue,color(b,1,1))
        repeat(60) { assertSame(a,cache.frame(12f,12f)) }
        assertNotSame(a,cache.frame(24f,12f))
    }

    private val red = 0xffff0000.toInt(); private val blue = 0xff0000ff.toInt()
    private fun color(s: DepthSurface, x: Int, y: Int): Int {
        val at = (y*s.width+x)*4
        return ((s.pixels[at+3].toInt() and 255) shl 24) or ((s.pixels[at+2].toInt() and 255) shl 16) or
            ((s.pixels[at+1].toInt() and 255) shl 8) or (s.pixels[at].toInt() and 255)
    }
    @Test fun crossingTrianglesResolvePerPixelIndependentlyOfSubmissionOrder() {
        val sloped = floatArrayOf(2f,2f,0f, 15f,2f,8f, 2f,15f,0f)
        val flat = floatArrayOf(2f,2f,4f, 15f,2f,4f, 2f,15f,4f)
        val a = DepthSurface(20f,20f); val b = DepthSurface(20f,20f)
        a.triangle(sloped,0,IntArray(3){red},0,100f); a.triangle(flat,0,IntArray(3){blue},0,100f)
        b.triangle(flat,0,IntArray(3){blue},0,100f); b.triangle(sloped,0,IntArray(3){red},0,100f)
        assertArrayEquals(a.pixels,b.pixels)
        assertEquals(red,color(a,3,3)); assertEquals(blue,color(a,12,3)); assertEquals(0,color(a,19,19))
    }
    @Test fun windingDoesNotChangeCoverageAndAdjacentTrianglesHaveNoCracks() {
        val s = DepthSurface(16f,16f)
        s.triangle(floatArrayOf(0f,0f,1f,16f,0f,1f,16f,16f,1f),0,IntArray(3){red},0,100f)
        // Opposite winding; shares the diagonal exactly.
        s.triangle(floatArrayOf(0f,16f,1f,16f,16f,1f,0f,0f,1f),0,IntArray(3){red},0,100f)
        for (y in 0..15) for (x in 0..15) assertEquals(red,color(s,x,y))
    }
    @Test fun colorsArePerspectiveCorrectRatherThanAffine() {
        val s = DepthSurface(12f,12f)
        s.triangle(floatArrayOf(0f,0f,0f,10f,0f,10f,0f,10f,0f),0,intArrayOf(red,blue,red),0,10f)
        val c = color(s,4,0)
        // Screen barycentrics .5,.45,.05 -> perspective blue .225/.775.
        assertEquals(74,c and 255); assertEquals(181,(c ushr 16) and 255)
    }
    @Test fun clippingAndDegenerateOrNonfiniteTrianglesAreSafe() {
        val s = DepthSurface(8f,8f)
        for (v in listOf(floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f,0f),floatArrayOf(Float.NaN,0f,0f,1f,0f,0f,0f,1f,0f),floatArrayOf(-50f,-50f,1f,-10f,-50f,1f,-10f,-10f,1f)))
            s.triangle(v,0,IntArray(3){red},0,100f)
        assertTrue(s.pixels.all { it == 0.toByte() })
        s.triangle(floatArrayOf(-5f,-5f,1f,15f,-5f,1f,-5f,15f,1f),0,IntArray(3){red},0,100f)
        assertEquals(red,color(s,0,0))
    }
    @Test fun oversizedViewportHasBoundedRasterStorage() {
        val s = DepthSurface(16000f,10000f)
        assertTrue(s.width.toLong()*s.height < 4_010_000L); assertTrue(s.scale < 1f)
    }
}
