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
    var screen=FloatArray(0)
    var normal=FloatArray(0);var light=FloatArray(0)
    val triangle=FloatArray(9);val colors=IntArray(3);val face=FloatArray(3);val flatLight=FloatArray(3)
    // Geometry depends on pose, not camera; shading depends on orientation, not
    // zoom/pan. Retain only a byte-bounded LRU, pruned to active objects by the worker.
    private val poses=LinkedHashMap<RasterPoseKey,RasterPose>(8,.75f,true)
    private var poseBytes=0L
    internal val retainedBytes get()=poseBytes
    internal var shadingPasses=0;private set
    fun vertices(count:Int) { if(screen.size<count*3)screen=FloatArray(count*3) }
    fun lights(count:Int) { if(normal.size<count*3) { normal=FloatArray(count*3);light=FloatArray(count*6) } }
    fun pose(o:PlateObject,d:DisplayMesh,check:()->Unit):RasterPose {
        val key=RasterPoseKey(d,o.x,o.y,o.rotZ,o.ex,o.ey,o.ez)
        poses[key]?.let { return it }
        val result=RasterPose(key,check)
        if(result.bytes<=POSE_CACHE_BYTES) {
            while(poseBytes+result.bytes>POSE_CACHE_BYTES && poses.isNotEmpty()) {
                val first=poses.entries.iterator();val entry=first.next();poseBytes-=entry.value.bytes;first.remove()
            }
            poses[key]=result;poseBytes+=result.bytes
            check(poseBytes<=POSE_CACHE_BYTES)
        }
        return result
    }
    fun retain(objects:List<Pair<PlateObject,DisplayMesh>>) {
        val keys=objects.map { (o,d)->RasterPoseKey(d,o.x,o.y,o.rotZ,o.ex,o.ey,o.ez) }.toSet()
        val iterator=poses.entries.iterator()
        while(iterator.hasNext()) { val entry=iterator.next();if(entry.key !in keys) { poseBytes-=entry.value.bytes;iterator.remove() } }
    }
    fun shaded() { shadingPasses++ }
    companion object { const val POSE_CACHE_BYTES=64L*1024*1024 }
}

internal data class RasterPoseKey(val display:DisplayMesh,val x:Float,val y:Float,val rotation:Float,val ex:Float,val ey:Float,val ez:Float)
internal class RasterPose(val key:RasterPoseKey,check:()->Unit) {
    val world=FloatArray(key.display.mesh.vertices.size)
    val faces=FloatArray(key.display.mesh.triangles.size)
    val colors=IntArray(key.display.mesh.triangles.size)
    var shadingKey:RasterShadingKey?=null
    val bytes=(world.size.toLong()+faces.size+colors.size)*4
    init {
        val d=key.display;val v=d.mesh.vertices;val t=d.mesh.triangles;val b=d.bounds
        val mx=(b[0]+b[3])/2;val my=(b[1]+b[4])/2;val zBase=if(key.ez>=0f)b[2] else b[5]
        val cr=cos(Math.toRadians(key.rotation.toDouble())).toFloat();val sr=sin(Math.toRadians(key.rotation.toDouble())).toFloat()
        for(i in 0 until d.mesh.vertexCount) {
            if(i and 1023==0)check()
            val at=i*3;val x=(v[at]-mx)*key.ex;val y=(v[at+1]-my)*key.ey
            world[at]=key.x+x*cr-y*sr;world[at+1]=key.y+x*sr+y*cr;world[at+2]=(v[at+2]-zBase)*key.ez
        }
        for(tri in d.tris) {
            if(tri and 1023==0)check()
            val a=t[tri*3]*3;val bb=t[tri*3+1]*3;val c=t[tri*3+2]*3
            val ux=world[bb]-world[a];val uy=world[bb+1]-world[a+1];val uz=world[bb+2]-world[a+2]
            val qx=world[c]-world[a];val qy=world[c+1]-world[a+1];val qz=world[c+2]-world[a+2]
            val nx=uy*qz-uz*qy;val ny=uz*qx-ux*qz;val nz=ux*qy-uy*qx
            val n=sqrt(nx*nx+ny*ny+nz*nz).takeIf { it>0 } ?: 1f
            faces[tri*3]=nx/n;faces[tri*3+1]=ny/n;faces[tri*3+2]=nz/n
        }
    }
}
internal data class RasterShadingKey(val orientation:Pair<Float,Float>,val color:Color,val selected:Boolean,val palette:List<Color?>)

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

internal fun rasterObject(proj:Projector,o:PlateObject,d:DisplayMesh,surface:DepthSurface,check:()->Unit = {}) {
    if(outsideViewport(proj,o,d.bounds))return
    val s=surface.objectScratch;val t=d.mesh.triangles
    val pose=s.pose(o,d,check)
    s.vertices(d.mesh.vertexCount)
    for(i in 0 until d.mesh.vertexCount) {
        if(i and 1023==0)check()
        val at=i*3
        proj.p(pose.world[at],pose.world[at+1],pose.world[at+2],s.screen,at)
    }
    val shadingKey=RasterShadingKey(proj.orientation,o.color,o.selected,o.paintColors)
    if(pose.shadingKey!=shadingKey) {
        pose.shadingKey=null // Never reuse a former key after a cancelled partial overwrite.
        shadePose(proj,o,d,s,pose,check)
        // Commit only a fully computed set: cancelled/failed partial colors never hit.
        pose.shadingKey=shadingKey;s.shaded()
    }
    // Cache hits read only per-pose colors, never another object's lighting scratch.
    surface.bands.draw(proj,d,s.screen,pose.colors,surface,check)
}

private fun shadePose(proj:Projector,o:PlateObject,d:DisplayMesh,s:ObjectRasterScratch,pose:RasterPose,check:()->Unit) {
    val cr=cos(Math.toRadians(o.rotZ.toDouble())).toFloat();val sr=sin(Math.toRadians(o.rotZ.toDouble())).toFloat()
    val groups=d.lightingGroups;s.lights(groups.corners.size)
        for(g in groups.corners.indices) {
            if(g and 1023==0)check()
            val corner=groups.corners[g];if(corner<0)continue
            val at=corner*3;var x=d.normals[at]/o.ex;var y=d.normals[at+1]/o.ey;var z=d.normals[at+2]/o.ez
            val n=sqrt(x*x+y*y+z*z).takeIf { it>0 } ?: 1f;x/=n;y/=n;z/=n
            proj.dir(x*cr-y*sr,x*sr+y*cr,z,s.normal,g*3)
            val nx=s.normal[g*3];val ny=s.normal[g*3+1];val nz=s.normal[g*3+2]
            lighting(nx,ny,nz,s.light,g*6);lighting(-nx,-ny,-nz,s.light,g*6+3)
        }
    fun shade(c:Color)=material(if(o.selected)lerpColor(c,Color.White,.18f) else c)
    val own=shade(o.color);val palette=o.paintColors.map { it?.let(::shade) ?: own }
    for(tri in d.tris) {
        if(tri and 1023==0)check()
        proj.dir(pose.faces[tri*3],pose.faces[tri*3+1],pose.faces[tri*3+2],s.face)
        val flip=if(s.face[2]<0f)-1f else 1f;var flat=false
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
            pose.colors[tri*3+k]=0xff000000.toInt() or (r8 shl 16) or (g8 shl 8) or b8
        }
    }
}
