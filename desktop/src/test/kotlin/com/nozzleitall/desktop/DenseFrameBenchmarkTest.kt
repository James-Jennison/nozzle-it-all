package com.nozzleitall.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.Mesh
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.*

/** Baseline-compatible benchmark: production full-quality passes, no UI input or
 * display-FPS claim. Run only on GTHost; compare identical warm/sample workloads. */
class DenseFrameBenchmarkTest {
    private fun torus():Mesh {
        val rows=250;val columns=300;val v=FloatArray(rows*columns*3);val t=IntArray(rows*columns*6)
        for(r in 0 until rows)for(c in 0 until columns) {
            val u=r*2*PI/rows;val a=c*2*PI/columns;val i=(r*columns+c)*3
            v[i]=((32+12*cos(a))*cos(u)).toFloat();v[i+1]=((32+12*cos(a))*sin(u)).toFloat();v[i+2]=(12+12*sin(a)).toFloat()
            val k=(r*columns+c)*6;val n=((r+1)%rows)*columns;val next=(c+1)%columns
            t[k]=r*columns+c;t[k+1]=n+c;t[k+2]=n+next;t[k+3]=r*columns+c;t[k+4]=n+next;t[k+5]=r*columns+next
        }
        return Mesh(v,t)
    }
    @Test(timeout=240000) fun denseOrbitAndZoom() {
        assumeTrue("opt-in dense frame benchmark",System.getenv("NIA_VIEWPORT_BENCHMARK")=="1")
        val mesh=torus();val d=displayMesh(mesh)
        for(count in listOf(3,8))for((width,height) in listOf(1920 to 1080,3840 to 2160))for(mode in listOf("orbit","zoom-pan")) {
            val camera=ViewCamera().apply { fitBed(256f,256f) }
            val objects=(0 until count).map { i->PlateObject(i,"fixture",mesh,Color(0xffb09070),48f+(i%3)*80,80f+(i/3)*60,i*37f,1f,false,sy=.8f+(i%3)*.2f) }
            val bitmap=ImageBitmap(width,height);val canvas=Canvas(bitmap);val cache=DepthSurfaceCache();val scope=CanvasDrawScope()
            fun frame(step:Int) {
                if(mode=="orbit")camera.orbit(2f,1f)
                else { camera.distance=480f+step*1.5f;camera.pan=Offset(step*.2f,step*.1f) }
                scope.draw(Density(1f),LayoutDirection.Ltr,canvas,Size(width.toFloat(),height.toFloat())) {
                    val proj=Projector(size.width,size.height,camera,128f,128f)
                    drawBed(proj,256f,256f,Color(0xff20262a),Color(0xff3c474e))
                    objects.forEach { drawShadow(proj,it,d,cache.shadows) }
                    val surface=cache.frame(size.width,size.height)
                    objects.forEach { drawObject(proj,it,d,surface) }
                    val info=org.jetbrains.skia.ImageInfo(surface.width,surface.height,org.jetbrains.skia.ColorType.BGRA_8888,org.jetbrains.skia.ColorAlphaType.PREMUL)
                    org.jetbrains.skia.Image.makeRaster(info,surface.pixels,surface.width*4).use { image->
                        drawIntoCanvas { it.nativeCanvas.drawImageRect(image,org.jetbrains.skia.Rect.makeWH(size.width,size.height)) }
                    }
                }
            }
            repeat(5) { frame(it) }
            val times=DoubleArray(11) { i->val start=System.nanoTime();frame(i+5);(System.nanoTime()-start)/1e6 }.sorted()
            println("NIA_DENSE_BENCH "+JSONObject().put("label",System.getenv("NIA_BENCH_LABEL") ?: "unlabelled").put("mode",mode).put("objects",count).put("triangles",count*150000).put("width",width).put("height",height).put("warmup",5).put("samples",11).put("median_ms",times[5]).put("p95_ms",times[10]))
        }
    }

    /** Same-JVM, alternating-order control isolates the exact tiling policy from
     * pose caching and cross-process/JIT/load variation in the historical baseline. */
    @Test(timeout=240000) fun pairedRasterPolicy() {
        assumeTrue("opt-in dense frame benchmark",System.getenv("NIA_VIEWPORT_BENCHMARK")=="1")
        val mesh=torus();val d=displayMesh(mesh)
        for(count in listOf(3,8))for(mode in listOf("orbit","zoom-pan")) {
            val camera=ViewCamera().apply { fitBed(256f,256f) }
            val objects=(0 until count).map { i->PlateObject(i,"fixture",mesh,Color(0xffb09070),48f+(i%3)*80,80f+(i/3)*60,i*37f,1f,false,sy=.8f+(i%3)*.2f) }
            val caches=listOf(DepthSurfaceCache(false),DepthSurfaceCache(true))
            val canvas=Canvas(ImageBitmap(3840,2160));val scope=CanvasDrawScope()
            fun frame(which:Int) {
                val cache=caches[which]
                scope.draw(Density(1f),LayoutDirection.Ltr,canvas,Size(3840f,2160f)) {
                    val proj=Projector(size.width,size.height,camera,128f,128f)
                    drawBed(proj,256f,256f,Color(0xff20262a),Color(0xff3c474e))
                    objects.forEach { drawShadow(proj,it,d,cache.shadows) }
                    val surface=cache.frame(size.width,size.height);objects.forEach { drawObject(proj,it,d,surface) }
                    val info=org.jetbrains.skia.ImageInfo(surface.width,surface.height,org.jetbrains.skia.ColorType.BGRA_8888,org.jetbrains.skia.ColorAlphaType.PREMUL)
                    org.jetbrains.skia.Image.makeRaster(info,surface.pixels,surface.width*4).use { image->drawIntoCanvas { it.nativeCanvas.drawImageRect(image,org.jetbrains.skia.Rect.makeWH(size.width,size.height)) } }
                }
            }
            val times=Array(2) { DoubleArray(9) }
            repeat(14) { step->
                if(mode=="orbit")camera.orbit(2f,1f) else { camera.distance=480f+step*1.5f;camera.pan=Offset(step*.2f,step*.1f) }
                for(which in if(step%2==0)listOf(0,1) else listOf(1,0)) {
                    val start=System.nanoTime();frame(which)
                    if(step>=5)times[which][step-5]=(System.nanoTime()-start)/1e6
                }
            }
            for(which in 0..1) {
                val sorted=times[which].sorted()
                println("NIA_DENSE_POLICY "+JSONObject().put("renderer",if(which==0)"cached-serial" else "cached-policy").put("mode",mode).put("objects",count).put("triangles",count*150000).put("width",3840).put("height",2160).put("warmup",5).put("samples",9).put("median_ms",sorted[4]).put("p95_ms",sorted[8]))
            }
        }
    }
}
