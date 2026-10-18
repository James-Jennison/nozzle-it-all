package com.nozzleitall.desktop.prepare

import kotlin.math.*

/** Display-only, shared across all objects in a frame. Hidden triangles must be rejected
 * at each pixel, not sorted by their average depth (which fails on overlapping long faces).
 * Buffers are bounded to four million samples even on oversized/HiDPI viewports.
 */
internal class DepthSurface(viewWidth: Float, viewHeight: Float) {
    val scale = min(1f, sqrt(4_000_000.0 / (viewWidth.toDouble()*viewHeight).coerceAtLeast(1.0)).toFloat())
    val width = ceil(viewWidth*scale).toInt().coerceAtLeast(1)
    val height = ceil(viewHeight*scale).toInt().coerceAtLeast(1)
    val pixels = ByteArray(width*height*4) // BGRA, opaque where covered; transparent elsewhere.
    private val depths = FloatArray(width*height) { Float.POSITIVE_INFINITY }

    fun triangle(screen: FloatArray, at: Int, colors: IntArray, colorAt: Int, eye: Float) {
        val ax = screen[at]*scale; val ay = screen[at+1]*scale
        val bx = screen[at+3]*scale; val by = screen[at+4]*scale
        val cx = screen[at+6]*scale; val cy = screen[at+7]*scale
        val az = screen[at+2]; val bz = screen[at+5]; val cz = screen[at+8]
        if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite() || !cx.isFinite() || !cy.isFinite() || !az.isFinite() || !bz.isFinite() || !cz.isFinite()) return
        val area = (bx-ax)*(cy-ay)-(by-ay)*(cx-ax)
        if (abs(area) < 1e-8f) return
        val x0 = max(0,floor(minOf(ax,bx,cx)).toInt()); val x1 = min(width-1,ceil(maxOf(ax,bx,cx)).toInt())
        val y0 = max(0,floor(minOf(ay,by,cy)).toInt()); val y1 = min(height-1,ceil(maxOf(ay,by,cy)).toInt())
        if (x0 > x1 || y0 > y1) return
        // Projector clamps the perspective denominator at 20% of the eye distance.
        val qa = 1f/max(eye+az,eye*0.2f); val qb = 1f/max(eye+bz,eye*0.2f); val qc = 1f/max(eye+cz,eye*0.2f)
        val ca = colors[colorAt]; val cb = colors[colorAt+1]; val cc = colors[colorAt+2]
        for (y in y0..y1) for (x in x0..x1) {
            val px = x+0.5f; val py = y+0.5f
            val wa = ((bx-px)*(cy-py)-(by-py)*(cx-px))/area
            val wb = ((cx-px)*(ay-py)-(cy-py)*(ax-px))/area
            val wc = 1f-wa-wb
            if (wa < -0.000001f || wb < -0.000001f || wc < -0.000001f) continue
            val a = wa*qa; val b = wb*qb; val c = wc*qc; val q = a+b+c
            val z = (a*az+b*bz+c*cz)/q
            val index = y*width+x
            if (z >= depths[index]) continue
            depths[index] = z
            for (channel in 0..2) {
                val shift = channel*8
                pixels[index*4+channel] = (((a*((ca ushr shift) and 255)+b*((cb ushr shift) and 255)+c*((cc ushr shift) and 255))/q).roundToInt().coerceIn(0,255)).toByte()
            }
            pixels[index*4+3] = 255.toByte()
        }
    }
}
