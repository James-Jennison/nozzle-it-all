package com.nozzleitall.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.Mesh
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
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

    @Test fun frameCompositeRetainsBedAndFallbackRemainsAvailable() {
        val req=request(listOf(item(1,triangle(),Color.Red)))
        FrameRasterizer(useGraphics=false).use { it.render(req,{}).use { assertEquals(320,it.width) } }
        FrameRasterizer().use { it.render(req,{}).use { image->assertTrue(pixels(image).all { p->p ushr 24==255 }) } }
    }
}
