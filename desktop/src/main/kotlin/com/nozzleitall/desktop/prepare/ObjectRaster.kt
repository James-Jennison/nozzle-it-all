package com.nozzleitall.desktop.prepare

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.*

/** Lighting depends on the exact normal, not position or material. Share that
 * calculation even on STL triangle soup, without welding geometry/crease normals. */
internal class LightingGroups(d:DisplayMesh) {
    val ids=IntArray(d.mesh.triangles.size)
    val corners:IntArray
    init {
        var capacity=1
        while(capacity<ids.size.toLong()*2) {
            require(capacity<=Int.MAX_VALUE/2) { "Display mesh is too large." }
            capacity=capacity shl 1
        }
        val table=IntArray(capacity);val representatives=IntArray(ids.size);var count=0
        for(c in ids.indices) {
            val x=d.normals[c*3].toRawBits();val y=d.normals[c*3+1].toRawBits();val z=d.normals[c*3+2].toRawBits()
            var hash=x*73856093 xor y*19349663 xor z*83492791
            hash=hash xor (hash ushr 16);var slot=hash and (capacity-1)
            while(table[slot]!=0) {
                val group=table[slot]-1;val previous=representatives[group]*3
                if(d.normals[previous].toRawBits()==x && d.normals[previous+1].toRawBits()==y && d.normals[previous+2].toRawBits()==z)break
                slot=(slot+1) and (capacity-1)
            }
            if(table[slot]==0) { representatives[count]=c;table[slot]=++count }
            ids[c]=table[slot]-1
        }
        corners=representatives.copyOf(count)
    }
}

/** One scratch owner per depth surface/viewport, not per mesh/global cache or triangle. */
internal class ObjectRasterScratch {
    var world=FloatArray(0);var screen=FloatArray(0)
    var normal=FloatArray(0);var light=FloatArray(0)
    val triangle=FloatArray(9);val colors=IntArray(3);val face=FloatArray(3);val flatLight=FloatArray(3)
    var litProjection:Projector?=null;var litMesh:DisplayMesh?=null
    var ex=0f;var ey=0f;var ez=0f;var rotation=0f
    fun vertices(count:Int) { if(world.size<count*3) { world=FloatArray(count*3);screen=FloatArray(count*3) } }
    fun lights(count:Int) { if(normal.size<count*3) { normal=FloatArray(count*3);light=FloatArray(count*6) } }
}

private fun unit(x:Float,y:Float,z:Float):FloatArray { val n=sqrt(x*x+y*y+z*z);return floatArrayOf(x/n,y/n,z/n) }
private val key=unit(-0.45f,-0.60f,0.66f)
private val fill=unit(0.70f,0.05f,0.45f)
private val half=unit(key[0],key[1],key[2]+1f)
private fun lighting(x:Float,y:Float,z:Float,out:FloatArray,at:Int) {
    val diffuse=max(0f,x*key[0]+y*key[1]+z*key[2]);val soft=max(0f,x*fill[0]+y*fill[1]+z*fill[2])
    val spec=max(0f,x*half[0]+y*half[1]+z*half[2]).let { s -> s*s*s*s*s*s*s*s*s*s*s*s*s*s*s*s }
    val rim=(1f-max(0f,z)).let { r -> r*r*r }
    out[at]=0.30f+0.62f*diffuse+0.20f*soft;out[at+1]=spec;out[at+2]=rim
}
private fun material(c:Color):Color {
    val lum=0.2126f*c.red+0.7152f*c.green+0.0722f*c.blue
    return if(lum<0.18f) lerpColor(c,Color(0.34f,0.36f,0.40f),0.55f*(1f-lum/0.18f)) else c
}

/** Conservative projected-box rejection. A box crossing the projector's clamp
 * plane is never rejected: its piecewise-projective extrema can lie on an edge. */
internal fun outsideViewport(proj:Projector,o:PlateObject,b:FloatArray,shadow:Boolean=false):Boolean {
    val cr=cos(Math.toRadians(o.rotZ.toDouble())).toFloat();val sr=sin(Math.toRadians(o.rotZ.toDouble())).toFloat()
    val mx=(b[0]+b[3])/2;val my=(b[1]+b[4])/2;val zBase=if(o.ez>=0f)b[2] else b[5]
    val p=FloatArray(3);var minX=Float.POSITIVE_INFINITY;var maxX=Float.NEGATIVE_INFINITY
    var minY=Float.POSITIVE_INFINITY;var maxY=Float.NEGATIVE_INFINITY;var clamped=0
    val count=if(shadow)4 else 8
    for(k in 0 until count) {
        val x=(b[if(k and 1==0)0 else 3]-mx)*o.ex;val y=(b[if(k and 2==0)1 else 4]-my)*o.ey
        val z=if(shadow)0f else (b[if(k and 4==0)2 else 5]-zBase)*o.ez
        proj.p(o.x+x*cr-y*sr,o.y+x*sr+y*cr,z,p,0)
        if(p.any { !it.isFinite() }) return false
        minX=min(minX,p[0]);maxX=max(maxX,p[0]);minY=min(minY,p[1]);maxY=max(maxY,p[1])
        if(-p[2]>=proj.eye*.8f)clamped++
    }
    if(clamped!=0 && clamped!=count)return false
    return maxX < -2f || minX > proj.w+2f || maxY < -2f || minY > proj.h+2f
}

internal fun rasterObject(proj:Projector,o:PlateObject,d:DisplayMesh,surface:DepthSurface) {
    if(outsideViewport(proj,o,d.bounds))return
    val s=surface.objectScratch;val v=d.mesh.vertices;val t=d.mesh.triangles;val b=d.bounds
    val mx=(b[0]+b[3])/2;val my=(b[1]+b[4])/2;val zBase=if(o.ez>=0f)b[2] else b[5]
    val cr=cos(Math.toRadians(o.rotZ.toDouble())).toFloat();val sr=sin(Math.toRadians(o.rotZ.toDouble())).toFloat()
    s.vertices(d.mesh.vertexCount)
    for(i in 0 until d.mesh.vertexCount) {
        val at=i*3;val x=(v[at]-mx)*o.ex;val y=(v[at+1]-my)*o.ey
        s.world[at]=o.x+x*cr-y*sr;s.world[at+1]=o.y+x*sr+y*cr;s.world[at+2]=(v[at+2]-zBase)*o.ez
        proj.p(s.world[at],s.world[at+1],s.world[at+2],s.screen,at)
    }
    val groups=d.lightingGroups;s.lights(groups.corners.size)
    if(s.litProjection!==proj || s.litMesh!==d || s.ex!=o.ex || s.ey!=o.ey || s.ez!=o.ez || s.rotation!=o.rotZ) {
        for(g in groups.corners.indices) {
            val corner=groups.corners[g];if(corner<0)continue
            val at=corner*3;var x=d.normals[at]/o.ex;var y=d.normals[at+1]/o.ey;var z=d.normals[at+2]/o.ez
            val n=sqrt(x*x+y*y+z*z).takeIf { it>0 } ?: 1f;x/=n;y/=n;z/=n
            proj.dir(x*cr-y*sr,x*sr+y*cr,z,s.normal,g*3)
            val nx=s.normal[g*3];val ny=s.normal[g*3+1];val nz=s.normal[g*3+2]
            lighting(nx,ny,nz,s.light,g*6);lighting(-nx,-ny,-nz,s.light,g*6+3)
        }
        s.litProjection=proj;s.litMesh=d;s.ex=o.ex;s.ey=o.ey;s.ez=o.ez;s.rotation=o.rotZ
    }
    fun shade(c:Color)=material(if(o.selected)lerpColor(c,Color.White,.18f) else c)
    val own=shade(o.color);val palette=o.paintColors.map { it?.let(::shade) ?: own }
    for(tri in d.tris) {
        val a=t[tri*3]*3;val bb=t[tri*3+1]*3;val c=t[tri*3+2]*3
        for(k in 0..2) { val at=when(k){0->a;1->bb;else->c};for(axis in 0..2)s.triangle[k*3+axis]=s.screen[at+axis] }
        val sc=s.triangle
        if(maxOf(sc[0],sc[3],sc[6]) < -1 || minOf(sc[0],sc[3],sc[6]) > proj.w+1 || maxOf(sc[1],sc[4],sc[7]) < -1 || minOf(sc[1],sc[4],sc[7]) > proj.h+1)continue
        val ux=s.world[bb]-s.world[a];val uy=s.world[bb+1]-s.world[a+1];val uz=s.world[bb+2]-s.world[a+2]
        val qx=s.world[c]-s.world[a];val qy=s.world[c+1]-s.world[a+1];val qz=s.world[c+2]-s.world[a+2]
        var nx=uy*qz-uz*qy;var ny=uz*qx-ux*qz;var nz=ux*qy-uy*qx
        val n=sqrt(nx*nx+ny*ny+nz*nz).takeIf { it>0 } ?: 1f;nx/=n;ny/=n;nz/=n
        proj.dir(nx,ny,nz,s.face);val flip=if(s.face[2]<0f)-1f else 1f;var flat=false
        val base=d.states?.get(tri)?.let { if(it==0)own else palette.getOrNull(it) ?: own } ?: own
        val red=base.red;val green=base.green;val blue=base.blue
        for(k in 0..2) {
            val g=groups.ids[tri*3+k];val at=g*3
            val dot=s.normal[at]*s.face[0]+s.normal[at+1]*s.face[1]+s.normal[at+2]*s.face[2]
            val light:FloatArray;val pos:Int
            if(dot*dot>.58f && dot>0) { light=s.light;pos=g*6+if(flip>0)0 else 3 }
            else { if(!flat) { lighting(s.face[0]*flip,s.face[1]*flip,s.face[2]*flip,s.flatLight,0);flat=true };light=s.flatLight;pos=0 }
            val l=light[pos];val spec=light[pos+1];val rim=light[pos+2]
            // The old Color(r,g,b).toArgb() always constructs opaque sRGB here.
            // Pack that exact 8-bit rounding directly instead of the general
            // wide-gamut Color constructor/conversion for every triangle corner.
            val r8=((red*l+.22f*spec+.10f*rim).coerceIn(0f,1f)*255f+.5f).toInt()
            val g8=((green*l+.22f*spec+.10f*rim).coerceIn(0f,1f)*255f+.5f).toInt()
            val b8=((blue*l+.22f*spec+.12f*rim).coerceIn(0f,1f)*255f+.5f).toInt()
            s.colors[k]=0xff000000.toInt() or (r8 shl 16) or (g8 shl 8) or b8
        }
        surface.triangle(s.triangle,0,s.colors,0,proj.eye)
    }
}
