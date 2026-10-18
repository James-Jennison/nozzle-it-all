package com.nozzleitall.desktop.prepare

import androidx.compose.ui.graphics.Color
import java.io.File
import kotlin.math.*
import org.jetbrains.skia.*

/** Optional, packaged Linux graphics bridge; no downloaded/extracted native code.
 * A mismatched library is never loaded into the rendering path. Other platforms
 * and machines without EGL retain the tested software renderer, not a blank view.
 */
internal object NativeViewport {
    val available:Boolean = try {
        val explicit=System.getProperty("nozzle.viewport.library")
        val folder=System.getProperty("compose.application.resources.dir")
        val library=explicit?.let(::File) ?: folder?.let { File(it,"libnia_viewport.so") }
        if(library?.isFile==true) {
            System.load(library.absolutePath)
            val source=javaClass.getResourceAsStream("/nozzle-source-revision.txt")?.bufferedReader()?.use { it.readText().trim() }
            source!=null && sourceRevision()==source
        } else false
    } catch(_:LinkageError) { false } catch(_:SecurityException) { false }
    external fun sourceRevision():String
    external fun create():Long
    external fun describe(handle:Long):String
    external fun begin(handle:Long,width:Int,height:Int,camera:FloatArray)
    external fun upload(handle:Long,positions:FloatArray,indices:IntArray,normals:FloatArray,colors:FloatArray):Int
    external fun releaseMesh(handle:Long,mesh:Int)
    external fun draw(handle:Long,mesh:Int,pose:FloatArray,shadow:Boolean)
    external fun read(handle:Long,pixels:ByteArray)
    external fun end(handle:Long)
    external fun destroy(handle:Long)
}

private data class GraphicsKey(val display:DisplayMesh,val color:Color,val selected:Boolean,val palette:List<Color?>)
private data class GraphicsMesh(val id:Int,val bytes:Long)

/** One frame-worker owner. Buffers upload only on geometry/material changes;
 * camera, placement, nonuniform scale and mirror remain GPU uniforms. The256MiB
 * cap counts VBO bytes, not driver/texture memory or source mesh references.
 */
internal class GraphicsViewport private constructor(private var handle:Long):AutoCloseable {
    val renderer=NativeViewport.describe(handle)
    private val meshes=LinkedHashMap<GraphicsKey,GraphicsMesh>(8,.75f,true)
    private var retained=0L
    private var pixels=ByteArray(0)
    internal var uploads=0;private set
    internal val retainedBytes get()=retained
    companion object {
        const val BUFFER_BUDGET=256L*1024*1024
        fun createOrNull():GraphicsViewport? =
            if(!NativeViewport.available) null else try { GraphicsViewport(NativeViewport.create()) } catch(_:IllegalStateException) { null }
    }
    private fun key(o:PlateObject,d:DisplayMesh)=GraphicsKey(d,o.color,o.selected,o.paintColors)
    private fun material(c:Color,selected:Boolean):Color {
        val color=if(selected)lerpColor(c,Color.White,.18f) else c
        val lum=.2126f*color.red+.7152f*color.green+.0722f*color.blue
        return if(lum<.18f)lerpColor(color,Color(.34f,.36f,.40f),.55f*(1f-lum/.18f)) else color
    }
    fun render(request:FrameRequest,check:()->Unit):Image {
        check();check(handle!=0L)
        val c=request.camera;val yaw=Math.toRadians(c.yaw.toDouble());val pitch=Math.toRadians(c.pitch.toDouble())
        val camera=floatArrayOf(cos(yaw).toFloat(),sin(yaw).toFloat(),cos(pitch).toFloat(),sin(pitch).toFloat(),
            min(request.width,request.height)/c.distance*1.6f,c.distance*1.8f,c.pan.x,c.pan.y,request.scene.bedW/2,request.scene.bedD/2)
        try {
            NativeViewport.begin(handle,request.width,request.height,camera)
            val active=request.scene.prepared.map { (o,d)->key(o,d) }.toSet()
            val iterator=meshes.entries.iterator()
            while(iterator.hasNext()) {
                val entry=iterator.next()
                if(entry.key !in active) { NativeViewport.releaseMesh(handle,entry.value.id);retained-=entry.value.bytes;iterator.remove() }
            }
            // Two passes: union each object's shadow once using stencil, then
            // all opaque surfaces share a real per-fragment floating depth buffer.
            for(shadow in listOf(true,false)) for((o,d) in request.scene.prepared) {
                check()
                if(o.ex==0f || o.ey==0f || o.ez==0f)continue
                val key=key(o,d)
                val mesh=meshes[key] ?: run {
                    val bytes=d.mesh.triangles.size.toLong()*48
                    check(bytes<=BUFFER_BUDGET) { "Graphics mesh exceeds buffer budget" }
                    while(retained+bytes>BUFFER_BUDGET && meshes.isNotEmpty()) {
                        val entries=meshes.entries.iterator();val entry=entries.next()
                        NativeViewport.releaseMesh(handle,entry.value.id);retained-=entry.value.bytes;entries.remove()
                    }
                    val own=material(o.color,o.selected)
                    val palette=o.paintColors.map { it?.let { value->material(value,o.selected) } ?: own }
                    val colors=FloatArray(d.mesh.triangleCount*3)
                    for(tri in d.tris) {
                        if(tri and 1023==0)check()
                        val state=d.states?.get(tri) ?: 0
                        val color=if(state==0)own else palette.getOrNull(state) ?: own
                        colors[tri*3]=color.red;colors[tri*3+1]=color.green;colors[tri*3+2]=color.blue
                    }
                    check()
                    GraphicsMesh(NativeViewport.upload(handle,d.mesh.vertices,d.mesh.triangles,d.normals,colors),bytes).also {
                        meshes[key]=it;retained+=bytes;uploads++
                    }
                }
                val b=d.bounds
                val pose=floatArrayOf(o.x,o.y,Math.toRadians(o.rotZ.toDouble()).toFloat(),o.ex,o.ey,o.ez,
                    (b[0]+b[3])/2,(b[1]+b[4])/2,if(o.ez>=0)b[2] else b[5],0f)
                NativeViewport.draw(handle,mesh.id,pose,shadow)
            }
            check()
            val bytes=Math.multiplyExact(Math.multiplyExact(request.width,request.height),4)
            if(pixels.size!=bytes)pixels=ByteArray(bytes)
            NativeViewport.read(handle,pixels);check()
            return Image.makeRaster(ImageInfo(request.width,request.height,ColorType.BGRA_8888,ColorAlphaType.PREMUL),pixels,request.width*4)
        } finally { NativeViewport.end(handle) }
    }
    override fun close() {
        if(handle!=0L) { NativeViewport.destroy(handle);handle=0L }
        meshes.clear();retained=0;pixels=ByteArray(0)
    }
}
