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
    val objectScratch=ObjectRasterScratch()
    val bands=RasterBands()

    fun clear() { pixels.fill(0); depths.fill(Float.POSITIVE_INFINITY) }

    fun triangle(screen: FloatArray, at: Int, colors: IntArray, colorAt: Int, eye: Float,check:()->Unit = {},rowStart:Int=0,rowEnd:Int=height) {
        val ax = screen[at]*scale; val ay = screen[at+1]*scale
        val bx = screen[at+3]*scale; val by = screen[at+4]*scale
        val cx = screen[at+6]*scale; val cy = screen[at+7]*scale
        val az = screen[at+2]; val bz = screen[at+5]; val cz = screen[at+8]
        if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite() || !cx.isFinite() || !cy.isFinite() || !az.isFinite() || !bz.isFinite() || !cz.isFinite()) return
        val area = (bx-ax)*(cy-ay)-(by-ay)*(cx-ax)
        if (!area.isFinite() || abs(area) < 1e-8f) return
        val x0 = max(0,floor(minOf(ax,bx,cx)).toInt()); val x1 = min(width-1,ceil(maxOf(ax,bx,cx)).toInt())
        val y0 = max(0,floor(minOf(ay,by,cy)).toInt()); val y1 = min(height-1,ceil(maxOf(ay,by,cy)).toInt())
        if (x0 > x1 || y0 > y1) return
        // Projector clamps the perspective denominator at 20% of the eye distance.
        val qa = 1f/max(eye+az,eye*0.2f); val qb = 1f/max(eye+bz,eye*0.2f); val qc = 1f/max(eye+cz,eye*0.2f)
        val ca = colors[colorAt]; val cb = colors[colorAt+1]; val cc = colors[colorAt+2]
        val clipSpans=(x1.toLong()-x0+1)*(y1.toLong()-y0+1)>64
        val da=if(clipSpans)(by.toDouble()-cy)/area else 0.0;val db=if(clipSpans)(cy.toDouble()-ay)/area else 0.0;val dc=-da-db
        val constantColor=ca==cb && cb==cc
        // Conservative span clipping only: the original float coverage/depth/color
        // equations still decide every candidate pixel. A cancellation margin and
        // one-pixel pad retain edge coverage even for long, very thin triangles.
        val guard=0.000001+0.000001*(x1.toDouble()-x0+2)*(y1.toDouble()-y0+2)/abs(area)
        // Keep FULL triangle bounds for span/guard math. Only iteration ownership
        // is clipped, so banded and serial coverage/depth/color remain identical.
        for (y in max(y0,rowStart)..min(y1,rowEnd-1)) {
            if(y and 31==0)check()
            var first=x0;var last=x1
            if(clipSpans) {
                val py=y+0.5f;val px0=x0+0.5f
                val wa0=((bx-px0)*(cy-py)-(by-py)*(cx-px0))/area
                val wb0=((cx-px0)*(ay-py)-(cy-py)*(ax-px0))/area
                val range=span(x0,x1,wa0,wb0,1f-wa0-wb0,da,db,dc,guard)
                if(range==Long.MIN_VALUE)continue
                first=(range ushr 32).toInt();last=range.toInt()
            }
            for (x in first..last) {
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
            if(constantColor) {
                pixels[index*4]=ca.toByte();pixels[index*4+1]=(ca ushr 8).toByte();pixels[index*4+2]=(ca ushr 16).toByte()
            } else for (channel in 0..2) {
                val shift = channel*8
                pixels[index*4+channel] = (((a*((ca ushr shift) and 255)+b*((cb ushr shift) and 255)+c*((cc ushr shift) and 255))/q).roundToInt().coerceIn(0,255)).toByte()
            }
            pixels[index*4+3] = 255.toByte()
            }
        }
    }

    private fun span(x0:Int,x1:Int,a:Float,b:Float,c:Float,da:Double,db:Double,dc:Double,guard:Double):Long {
        var lo=x0.toDouble();var hi=x1.toDouble()
        for(i in 0..2) {
            val value=when(i){0->a;1->b;else->c}.toDouble();val slope=when(i){0->da;1->db;else->dc}
            if(slope==0.0) { if(value < -guard) return Long.MIN_VALUE }
            else {
                val edge=x0+(-guard-value)/slope
                if(slope>0) lo=max(lo,edge) else hi=min(hi,edge)
            }
        }
        val left=max(x0,ceil(lo).toInt()-1);val right=min(x1,floor(hi).toInt()+1)
        return if(left>right) Long.MIN_VALUE else (left.toLong() shl 32) or (right.toLong() and 0xffffffffL)
    }
}

/** Owned by one viewport; reuse frame storage without retaining old-size buffers. */
internal class DepthSurfaceCache {
    val shadows=ShadowScratch()
    private var surface: DepthSurface? = null
    private var width = -1f
    private var height = -1f
    fun release() { surface=null;width=-1f;height=-1f }
    fun frame(viewWidth: Float, viewHeight: Float): DepthSurface {
        val current = surface?.takeIf { width == viewWidth && height == viewHeight }
        if (current != null) { current.clear(); return current }
        return DepthSurface(viewWidth, viewHeight).also {
            surface = it; width = viewWidth; height = viewHeight
        }
    }
}
