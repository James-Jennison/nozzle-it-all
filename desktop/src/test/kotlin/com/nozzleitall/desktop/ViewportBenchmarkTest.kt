package com.nozzleitall.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.Mesh
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.*

/** Opt-in, deterministic CPU/offscreen-Skia benchmark of the production plate passes.
 * Run only on GTHost: NIA_VIEWPORT_BENCHMARK=1 ./gradlew :desktop:test --tests '*ViewportBenchmarkTest'.
 * Not display-refresh FPS: excludes Compose layout/input dispatch, GPU/display compositor and slicing.
 */
class ViewportBenchmarkTest {
    private fun enabled() = assumeTrue("opt-in viewport benchmark", System.getenv("NIA_VIEWPORT_BENCHMARK") == "1")
    private val bean get() = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    private fun measure(name: String, details: JSONObject = JSONObject(), warm: Int = 3, samples: Int = 9, work: () -> Unit) {
        repeat(warm) { work() }
        val times = DoubleArray(samples); val allocations = LongArray(samples)
        val thread = Thread.currentThread().id; val meter = bean
        repeat(samples) { i ->
            val allocated = meter?.getThreadAllocatedBytes(thread) ?: 0L
            val start = System.nanoTime(); work(); times[i] = (System.nanoTime()-start)/1e6
            allocations[i] = (meter?.getThreadAllocatedBytes(thread) ?: 0L)-allocated
        }
        times.sort(); allocations.sort()
        println("NIA_VIEWPORT_BENCHMARK "+details.put("case",name)
            .put("label",System.getenv("NIA_BENCH_LABEL") ?: "unlabelled")
            .put("warmup",warm).put("samples",samples).put("median_ms",times[samples/2])
            .put("p95_ms",times[(ceil(samples*.95).toInt()-1).coerceIn(0,samples-1)])
            .put("max_ms",times.last()).put("median_thread_allocated_bytes",allocations[samples/2]))
    }

    /** Closed surface with exactly150,000 triangles, ordinary low-valence adjacency. */
    private fun torus(): Mesh {
        val rows = 250; val columns = 300
        val v = FloatArray(rows*columns*3); val t = IntArray(rows*columns*6)
        for (r in 0 until rows) for (c in 0 until columns) {
            val u = r*2*PI/rows; val a = c*2*PI/columns; val i=(r*columns+c)*3
            v[i]=((32+12*cos(a))*cos(u)).toFloat();v[i+1]=((32+12*cos(a))*sin(u)).toFloat();v[i+2]=(12+12*sin(a)).toFloat()
            val k=(r*columns+c)*6; val n=((r+1)%rows)*columns; val next=(c+1)%columns
            t[k]=r*columns+c;t[k+1]=n+c;t[k+2]=n+next
            t[k+3]=r*columns+c;t[k+4]=n+next;t[k+5]=r*columns+next
        }
        return Mesh(v,t)
    }
    private fun fan(count: Int, creased:Boolean=false): Mesh {
        val v=FloatArray((count+1)*3);val t=IntArray(count*3)
        for (i in 0 until count) { val a=i*2*PI/count;v[(i+1)*3]=(100*cos(a)).toFloat();v[(i+1)*3+1]=(100*sin(a)).toFloat();t[i*3]=0;t[i*3+1]=i+1;t[i*3+2]=(i+1)%count+1 }
        if(creased)for(i in 0 until count)v[(i+1)*3+2]=(22*sin(7*i*2*PI/count)).toFloat()
        return Mesh(v,t)
    }

    @Test(timeout = 240000) fun highValencePreparation() {
        enabled()
        for (creased in listOf(false,true))for (n in listOf(4_000,16_000,64_000,150_000)) {
            val m=fan(n,creased)
            measure(if(creased)"creased-fan-corner-normals" else "planar-fan-corner-normals",JSONObject().put("triangles",n).put("max_vertex_valence",n),warm=0,samples=1) {
                val normals=cornerNormals(m);check(normals.size==n*9);check(normals.all { it.isFinite() })
            }
        }
    }

    @Test fun releasedMeshesAreNotRetainedByDisplayCache() {
        enabled()
        val mesh=torus();val refs=ArrayList<java.lang.ref.WeakReference<Mesh>>()
        repeat(8) { val copy=Mesh(mesh.vertices,mesh.triangles);displayMesh(copy);refs.add(java.lang.ref.WeakReference(copy)) }
        repeat(3) { System.gc();Thread.sleep(100) }
        // Observational GC metric, not a timing-dependent correctness assertion.
        println("NIA_VIEWPORT_BENCHMARK "+JSONObject().put("case","released-display-mesh-cache").put("label",System.getenv("NIA_BENCH_LABEL") ?: "unlabelled").put("meshes",8).put("observed_retained_after_gc",refs.count { it.get()!=null }))
    }

    @Test(timeout = 240000) fun viewportFrames() {
        enabled()
        val mesh=torus()
        val display=displayMesh(mesh)
        val objects=listOf(48f,128f,208f).mapIndexed { i,x -> PlateObject(i,"fixture",mesh,Color(0xffb09070),x,128f,0f,1f,false) }
        for ((width,height) in listOf(1920 to 1080,3840 to 2160)) {
            val bitmap=ImageBitmap(width,height);val canvas=Canvas(bitmap);val scope=CanvasDrawScope()
            val cache=DepthSurfaceCache();val camera=ViewCamera().apply { fitBed(256f,256f) }
            val details={ JSONObject().put("width",width).put("height",height).put("objects",3).put("triangles_per_object",mesh.triangleCount) }
            fun frame(shadows: Boolean, present: Boolean) {
                scope.draw(Density(1f),LayoutDirection.Ltr,canvas,Size(width.toFloat(),height.toFloat())) {
                    val proj=Projector(size.width,size.height,camera,128f,128f)
                    drawBed(proj,256f,256f,Color(0xff20262a),Color(0xff3c474e))
                    if(shadows) objects.forEach { drawShadow(proj,it,display,cache.shadows) }
                    val surface=cache.frame(size.width,size.height)
                    objects.forEach { drawObject(proj,it,display,surface) }
                    if(present) {
                        val info=org.jetbrains.skia.ImageInfo(surface.width,surface.height,org.jetbrains.skia.ColorType.BGRA_8888,org.jetbrains.skia.ColorAlphaType.PREMUL)
                        org.jetbrains.skia.Image.makeRaster(info,surface.pixels,surface.width*4).use { image ->
                            drawIntoCanvas { it.nativeCanvas.drawImageRect(image,org.jetbrains.skia.Rect.makeWH(size.width,size.height)) }
                        }
                    }
                }
            }
            measure("three-objects-full-frame",details()) { camera.orbit(1f,0.5f);frame(true,true) }
            measure("three-objects-no-shadow-no-presentation",details()) { camera.orbit(1f,0.5f);frame(false,false) }
            camera.pan=Offset(100_000f,100_000f)
            measure("three-offscreen-objects-full-frame",details()) { frame(true,true) }
        }
    }

    @Test(timeout = 240000) fun rasterStress() {
        enabled()
        val cache=DepthSurfaceCache();val screen=floatArrayOf(0f,0f,0f,3840f,2160f,0f,3839f,2160f,0f);val colors=IntArray(3){0xffaabbcc.toInt()}
        measure("64-full-diagonal-slivers",JSONObject().put("width",3840).put("height",2160).put("triangles",64),warm=1,samples=3) {
            val surface=cache.frame(3840f,2160f)
            repeat(64) { screen[2]=it.toFloat();screen[5]=it.toFloat();screen[8]=it.toFloat();surface.triangle(screen,0,colors,0,1000f) }
        }
    }

    /** Paired same-JVM control, with different object transforms so lighting cannot
     * be shared merely because all three objects are identical instances. */
    @Test(timeout = 240000) fun pairedDistinctPoseFrames() {
        enabled()
        val mesh=torus();val d=displayMesh(mesh)
        val objects=(0..2).map { i -> PlateObject(i,"fixture",mesh,Color(0xffb09070),48f+i*80,128f,i*47f,1f,false,sy=.8f+i*.2f) }
        for(reference in listOf(true,false)) {
            val camera=ViewCamera().apply { fitBed(256f,256f) };val cache=DepthSurfaceCache();val old=ReferenceDepthSurface(3840f,2160f)
            val canvas=Canvas(ImageBitmap(3840,2160));val scope=CanvasDrawScope()
            measure("three-distinct-pose-full-frame",JSONObject().put("renderer",if(reference)"frozen-reference" else "optimized").put("width",3840).put("height",2160).put("objects",3).put("triangles_per_object",150000)) {
                camera.orbit(1f,.5f)
                scope.draw(Density(1f),LayoutDirection.Ltr,canvas,Size(3840f,2160f)) {
                    val proj=Projector(size.width,size.height,camera,128f,128f)
                    drawBed(proj,256f,256f,Color(0xff20262a),Color(0xff3c474e))
                    val pixels:ByteArray;val width:Int;val height:Int
                    if(reference) {
                        old.clear();objects.forEach { referenceShadow(proj,it,d);referenceObject(proj,it,d,old) }
                        pixels=old.pixels;width=old.width;height=old.height
                    } else {
                        objects.forEach { drawShadow(proj,it,d,cache.shadows) }
                        val surface=cache.frame(size.width,size.height);objects.forEach { drawObject(proj,it,d,surface) }
                        pixels=surface.pixels;width=surface.width;height=surface.height
                    }
                    val info=org.jetbrains.skia.ImageInfo(width,height,org.jetbrains.skia.ColorType.BGRA_8888,org.jetbrains.skia.ColorAlphaType.PREMUL)
                    org.jetbrains.skia.Image.makeRaster(info,pixels,width*4).use { image -> drawIntoCanvas { it.nativeCanvas.drawImageRect(image,org.jetbrains.skia.Rect.makeWH(size.width,size.height)) } }
                }
            }
        }
    }
}
