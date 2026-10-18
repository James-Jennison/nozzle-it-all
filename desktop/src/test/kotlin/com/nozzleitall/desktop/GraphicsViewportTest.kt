package com.nozzleitall.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.Mesh
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.*

class GraphicsViewportTest {
    private fun triangle(z:Float=0f)=Mesh(floatArrayOf(-30f,-25f,z,30f,-25f,z,0f,30f,z),intArrayOf(0,1,2))
    private fun item(id:Int,mesh:Mesh,color:Color)=PlateObject(id,"fixture",mesh,color,128f,128f,0f,1f,false)
    private fun request(objects:List<PlateObject>)=FrameRequest(FrameScene(objects.map { it to displayMesh(it.mesh) },256f,256f,Color.DarkGray,Color.Gray),CameraPose(0f,0f,300f,Offset.Zero),320,240)
    private fun pixels(image:org.jetbrains.skia.Image)=IntArray(image.width*image.height).also { image.toComposeImageBitmap().readPixels(it) }
    private fun graphics():GraphicsViewport {
        assumeTrue("packaged viewport library available",NativeViewport.available)
        return GraphicsViewport.createOrNull() ?: throw AssertionError("Native library present but EGL initialization failed")
    }

    @Test fun realDepthOccludesRegardlessOfObjectOrderAndMatchesProjection() {
        graphics().use { gpu->
            val low=triangle();val high=triangle(20f)
            // Different per-object z bases are grounded by the plate contract;
            // tilt the upper triangle so the centre actually lies above the bed.
            high.vertices[2]=0f
            val a=item(1,low,Color.Red);val b=item(2,high,Color.Blue)
            val one=gpu.render(request(listOf(a,b)),{}).use(::pixels)
            val two=gpu.render(request(listOf(b,a)),{}).use(::pixels)
            val middle=120*320+160
            assertEquals(one[middle],two[middle]);assertTrue((one[middle] and 255)>((one[middle] ushr 16) and 255))
            assertTrue(one.count { it ushr 24==255 }>500)
        }
    }

    @Test fun cameraPoseAndResizeReuseUploadedGeometryAndSnapshotsRemainImmutable() {
        graphics().use { gpu->
            val request=request(listOf(item(1,triangle(),Color.Red)))
            gpu.render(request,{}).use { first->
                val frozen=pixels(first);assertEquals(1,gpu.uploads)
                gpu.render(request.copy(camera=request.camera.copy(yaw=35f,pitch=25f,distance=400f)),{}).use { next->assertFalse(frozen.contentEquals(pixels(next))) }
                gpu.render(request.copy(width=640,height=480),{}).use { assertEquals(640,it.width) }
                assertEquals(1,gpu.uploads);assertArrayEquals(frozen,pixels(first))
                assertTrue(gpu.retainedBytes<=GraphicsViewport.BUFFER_BUDGET)
            }
        }
    }

    @Test fun materialSelectionSourceAndPaletteChangesInvalidateButPlacementDoesNot() {
        graphics().use { gpu->
            val mesh=triangle();val a=item(1,mesh,Color.Red);val req=request(listOf(a))
            gpu.render(req,{}).close();assertEquals(1,gpu.uploads)
            val moved=PlateObject(1,"fixture",mesh,Color.Red,132f,125f,25f,1.2f,false,sx=-1f)
            gpu.render(req.copy(scene=req.scene.copy(prepared=listOf(moved to req.scene.prepared[0].second))),{}).close();assertEquals(1,gpu.uploads)
            val changed=PlateObject(1,"fixture",mesh,Color.Blue,128f,128f,0f,1f,true)
            gpu.render(req.copy(scene=req.scene.copy(prepared=listOf(changed to req.scene.prepared[0].second))),{}).close();assertEquals(2,gpu.uploads)
            gpu.render(request(emptyList()),{}).close();assertEquals(0L,gpu.retainedBytes)
        }
    }

    @Test fun cancelledFrameUnbindsAndNextFrameRecoversIncludingDifferentThread() {
        val gpu=graphics();val request=request(listOf(item(1,triangle(),Color.Red)))
        try {
            var checks=0
            try { gpu.render(request) { if(++checks==3)throw ObsoleteFrame() };fail("cancel") }catch(_:ObsoleteFrame) {}
            val executor=Executors.newSingleThreadExecutor()
            try { executor.submit { gpu.render(request,{}).use { assertTrue(pixels(it).any { p->p ushr 24==255 }) } }.get() }
            finally { executor.shutdownNow() }
        } finally { gpu.close() }
    }

    @Test fun rejectedNativeInputThrowsAndContextRecovers() {
        graphics().use { gpu->
            val req=request(listOf(item(1,triangle(),Color.Red)))
            try { gpu.render(req.copy(width=16385),{});fail("oversized native framebuffer") }
            catch(_:IllegalStateException) {}
            gpu.render(req,{}).use { assertTrue(pixels(it).any { p->p ushr 24==255 }) }
        }
    }

    @Test fun frameCompositeRetainsBedAndFallbackRemainsAvailable() {
        val req=request(listOf(item(1,triangle(),Color.Red)))
        FrameRasterizer(useGraphics=false).use { it.render(req,{}).use { assertEquals(320,it.width) } }
        FrameRasterizer().use { it.render(req,{}).use { image->assertTrue(pixels(image).all { p->p ushr 24==255 }) } }
        FrameRasterizer(compositeBed=false).use { it.render(req,{}).use { image->
            val values=pixels(image);assertTrue(values.any { p->p ushr 24==255 });assertTrue(values.any { p->p ushr 24==0 })
        } }
    }

    private fun torus(rows:Int=24,columns:Int=32):Mesh {
        val v=FloatArray(rows*columns*3);val t=IntArray(rows*columns*6)
        for(r in 0 until rows)for(c in 0 until columns) {
            val u=r*2*PI/rows;val a=c*2*PI/columns;val at=(r*columns+c)*3
            v[at]=((32+12*cos(a))*cos(u)).toFloat();v[at+1]=((32+12*cos(a))*sin(u)).toFloat();v[at+2]=(12+12*sin(a)).toFloat()
            val k=(r*columns+c)*6;val n=((r+1)%rows)*columns;val next=(c+1)%columns
            t[k]=r*columns+c;t[k+1]=n+c;t[k+2]=n+next;t[k+3]=r*columns+c;t[k+4]=n+next;t[k+5]=r*columns+next
        }
        return Mesh(v,t)
    }

    @Test fun gpuLightingDepthAndMirrorsStayCloseToFrozenSoftwareQuality() {
        val mesh=torus();val d=displayMesh(mesh)
        graphics().use { gpu->
            for(pitch in listOf(0f,55f,180f,230f))for(mirror in listOf(1f,-1f))for(selected in listOf(false,true)) {
                val o=PlateObject(1,"fixture",mesh,Color(0xffb09070),128f,128f,37f,1f,selected,sx=mirror,sy=1.2f,sz=.7f)
                val req=request(emptyList()).copy(scene=request(emptyList()).scene.copy(prepared=listOf(o to d)),camera=request(emptyList()).camera.copy(yaw=-35f,pitch=pitch))
                val actual=gpu.render(req,{}).use(::pixels)
                val surface=DepthSurface(320f,240f);rasterObject(req.projector(),o,d,surface)
                val expected=IntArray(320*240) { i->val at=i*4;((surface.pixels[at+3].toInt() and 255) shl 24) or ((surface.pixels[at+2].toInt() and 255) shl 16) or ((surface.pixels[at+1].toInt() and 255) shl 8) or (surface.pixels[at].toInt() and 255) }
                var covered=0;var mismatch=0;var error=0L;var compared=0
                for(i in actual.indices) {
                    val a=actual[i] ushr 24==255;val e=expected[i] ushr 24==255
                    if(a||e) { covered++;if(a!=e)mismatch++ }
                    if(a&&e) { for(shift in listOf(0,8,16))error+=abs(((actual[i] ushr shift) and 255)-((expected[i] ushr shift) and 255));compared+=3 }
                }
                assertTrue("coverage $pitch/$mirror/$selected $mismatch/$covered",covered>100 && mismatch.toDouble()/covered<.03)
                assertTrue("lighting $pitch/$mirror/$selected ${error.toDouble()/compared}",compared>300 && error.toDouble()/compared<4.0)
            }
        }
    }

    @Test(timeout=240000) fun optInFullQualityGraphicsFrameBenchmark() {
        assumeTrue("opt-in GPU frame benchmark",System.getenv("NIA_VIEWPORT_BENCHMARK")=="1")
        val mesh=torus(250,300);val d=displayMesh(mesh)
        graphics().use { gpu->
            for(count in listOf(3,8))for(mode in listOf("orbit","zoom-pan")) {
                val objects=(0 until count).map { i->PlateObject(i,"fixture",mesh,Color(0xffb09070),48f+(i%3)*80,80f+(i/3)*60,i*37f,1f,false,sy=.8f+(i%3)*.2f) }
                var request=request(emptyList()).copy(width=3840,height=2160,scene=request(emptyList()).scene.copy(prepared=objects.map { it to d }))
                fun frame(step:Int) {
                    request=request.copy(camera=if(mode=="orbit")request.camera.copy(yaw=request.camera.yaw+.8f,pitch=request.camera.pitch+.4f) else request.camera.copy(distance=480f+step*1.5f,pan=Offset(step*.2f,step*.1f)))
                    gpu.composite(request,{}).close()
                }
                repeat(5) { frame(it) }
                val times=DoubleArray(21) { i->val start=System.nanoTime();frame(i+5);(System.nanoTime()-start)/1e6 }.sorted()
                println("NIA_GRAPHICS_BENCH "+JSONObject().put("renderer",gpu.renderer).put("mode",mode).put("triangles",count*150000).put("width",3840).put("height",2160).put("warmup",5).put("samples",21).put("median_ms",times[10]).put("p95_ms",times[19]))
            }
        }
    }
}
