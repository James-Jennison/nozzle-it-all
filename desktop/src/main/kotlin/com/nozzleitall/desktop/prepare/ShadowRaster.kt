package com.nozzleitall.desktop.prepare

import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.*

/** A few primitive buffers, keyed only by sample count, owned by one viewport.
 * No Mesh references and no unbounded accumulation across imported models. */
internal class ShadowScratch {
    class Buffers(count:Int) { val positions=FloatArray(count*2);val colors=IntArray(count){0xff000000.toInt()} }
    private val sizes=object:LinkedHashMap<Int,Buffers>(4,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Int,Buffers>?)=size>4
    }
    fun get(count:Int)=sizes.getOrPut(count){Buffers(count)}
}

internal fun DrawScope.rasterShadow(proj:Projector,o:PlateObject,d:DisplayMesh,scratch:ShadowScratch) {
    if(outsideViewport(proj,o,d.bounds,shadow=true))return
    val v=d.mesh.vertices;val t=d.mesh.triangles;val b=d.bounds
    val mx=(b[0]+b[3])/2;val my=(b[1]+b[4])/2
    val cr=cos(Math.toRadians(o.rotZ.toDouble())).toFloat();val sr=sin(Math.toRadians(o.rotZ.toDouble())).toFloat()
    val step=max(1,d.tris.size/20_000);val samples=(d.tris.size+step-1)/step
    val buffers=scratch.get(samples*3);val tmp=FloatArray(3);var out=0;var k=0
    var left=Float.POSITIVE_INFINITY;var top=Float.POSITIVE_INFINITY
    var right=Float.NEGATIVE_INFINITY;var bottom=Float.NEGATIVE_INFINITY
    while(k<d.tris.size) {
        val tri=d.tris[k]
        for(c in 0..2) {
            val vi=t[tri*3+c]*3;val x=(v[vi]-mx)*o.ex;val y=(v[vi+1]-my)*o.ey
            proj.p(o.x+x*cr-y*sr,o.y+x*sr+y*cr,0f,tmp,0)
            buffers.positions[out++]=tmp[0];buffers.positions[out++]=tmp[1]
            left=min(left,tmp[0]);right=max(right,tmp[0]);top=min(top,tmp[1]);bottom=max(bottom,tmp[1])
        }
        k+=step
    }
    drawIntoCanvas { canvas ->
        // The shadow only touches its projected footprint. Avoid allocating and
        // compositing a full viewport-sized transparency layer per object.
        // Two physical pixels retain antialiased edge coverage.
        val bounds=if(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
            androidx.compose.ui.geometry.Rect(max(0f,floor(left)-2f),max(0f,floor(top)-2f),min(size.width,ceil(right)+2f),min(size.height,ceil(bottom)+2f))
        else androidx.compose.ui.geometry.Rect(0f,0f,size.width,size.height)
        if(bounds.isEmpty)return@drawIntoCanvas
        canvas.saveLayer(bounds,Paint().apply { alpha=.28f })
        try {
            org.jetbrains.skia.Paint().use { paint ->
                paint.isAntiAlias=true
                canvas.nativeCanvas.drawVertices(org.jetbrains.skia.VertexMode.TRIANGLES,buffers.positions,buffers.colors,buffers.positions,null,org.jetbrains.skia.BlendMode.DST,paint)
            }
        } finally { canvas.restore() }
    }
}
