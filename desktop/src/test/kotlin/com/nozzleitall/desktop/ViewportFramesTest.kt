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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.random.Random

class ViewportFramesTest {
    private fun request()=FrameRequest(FrameScene(emptyList(),256f,256f,Color.DarkGray,Color.Gray),CameraPose(-35f,55f,480f,Offset.Zero),320,240)
    private class Resource:AutoCloseable { var closes=0;override fun close() { closes++ } }
    private fun await(latch:CountDownLatch)=assertTrue(latch.await(10,TimeUnit.SECONDS))

    @Test(timeout=10000) fun optInMotionProbeOnlyChangesCameraAndRunsMarkersOnMain()=runBlocking {
        val camera=ViewCamera();val before=camera.snapshot();val events=ArrayList<String>()
        viewportMotionProbe(camera,steps=2,periodNanos=1_000_000,pauseMillis=0) { event,mode,_->
            assertTrue(SwingUtilities.isEventDispatchThread());events.add("$event/$mode")
        }
        assertEquals(listOf("begin/orbit","end/orbit","begin/zoom","end/zoom"),events)
        assertNotEquals(before,camera.snapshot());assertTrue(camera.distance.isFinite())
    }

    @Test(timeout=20000) fun publicationDoesNotWaitForOwnersFrameScheduledDispatcher()=runBlocking {
        val frameScheduled=object:CoroutineDispatcher() {
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable) {
                error("Frame pump must not queue publication through the owner's frame dispatcher")
            }
        }
        val owner=CoroutineScope(SupervisorJob()+frameScheduled)
        val ready=CompletableDeferred<Resource>();val disposed=CompletableDeferred<Unit>()
        val pump=LatestFramePump(owner,render={ _,_->
            assertFalse(SwingUtilities.isEventDispatchThread());Resource()
        },publish={ _,resource,_->assertTrue(SwingUtilities.isEventDispatchThread());ready.complete(resource) },
            dispose={ assertFalse(SwingUtilities.isEventDispatchThread());disposed.complete(Unit) })
        try {
            withContext(Dispatchers.Main) { pump.offer(request()) }
            withTimeout(5000) { ready.await() }.close()
            owner.cancel() // Explicit dispatcher must still inherit owner cancellation.
            withTimeout(5000) { disposed.await() }
        } finally { withContext(Dispatchers.Main) { pump.close() };owner.cancel() }
    }

    @Test(timeout=20000) fun cameraFramesDoNotStarveAndPendingRequestsAreConflated()=runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val start=CountDownLatch(1);val release=CountDownLatch(1);val releaseSecond=CountDownLatch(1)
        val results=Channel<Pair<FrameTicket,Resource>>(Channel.UNLIMITED)
        var calls=0
        val pump=LatestFramePump(owner,render={ _,_->
            assertFalse(SwingUtilities.isEventDispatchThread())
            if(++calls==1) { start.countDown();check(release.await(10,TimeUnit.SECONDS)) }
            else if(calls==2)check(releaseSecond.await(10,TimeUnit.SECONDS))
            Resource()
        },publish={ ticket,resource,_->assertTrue(SwingUtilities.isEventDispatchThread());results.trySend(ticket to resource) })
        try {
            val first=request()
            withContext(Dispatchers.Main) { pump.offer(first) };await(start)
            withTimeout(3000) { withContext(Dispatchers.Main) {
                for(i in 1..100)pump.offer(first.copy(camera=first.camera.copy(yaw=i.toFloat())))
            } }
            release.countDown()
            val a=withTimeout(5000) { results.receive() };releaseSecond.countDown()
            val b=withTimeout(5000) { results.receive() }
            assertEquals(1L,a.first.sequence);assertEquals(101L,b.first.sequence)
            assertEquals(100f,b.first.request.camera.yaw);assertEquals(2,calls)
            a.second.close();b.second.close()
        } finally { release.countDown();releaseSecond.countDown();withContext(Dispatchers.Main) { pump.close() };owner.cancel() }
    }

    @Test(timeout=20000) fun workerContinuesWhileMainIsBusyAndClosesReplacedWaitingResult()=runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val firstRendered=CountDownLatch(1);val firstDropped=CountDownLatch(1)
        val shown=CompletableDeferred<FrameTicket>();val disposed=CompletableDeferred<Unit>()
        val first=AutoCloseable { firstDropped.countDown() };var calls=0
        val pump=LatestFramePump<AutoCloseable>(owner,render={ _,_->
            if(++calls==1) { firstRendered.countDown();first } else AutoCloseable {}
        },publish={ ticket,value,_->value.close();shown.complete(ticket) },dispose={ disposed.complete(Unit) })
        try {
            withContext(Dispatchers.Main) {
                val a=request();pump.offer(a);await(firstRendered)
                pump.offer(a.copy(camera=a.camera.copy(yaw=30f)))
                // Deliberately hold Main: a worker that round-trips through Main
                // between frames cannot reach the second render or this close.
                await(firstDropped)
            }
            assertEquals(2L,withTimeout(5000) { shown.await() }.sequence)
            withContext(Dispatchers.Main) { pump.close() }
            withTimeout(5000) { disposed.await() };assertEquals(2,calls)
        } finally { withContext(Dispatchers.Main) { pump.close() };owner.cancel() }
    }

    @Test(timeout=20000) fun obsoleteNonCooperativeSourceResultIsClosedNotPublished()=runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val start=CountDownLatch(1);val release=CountDownLatch(1)
        val first=Resource();val results=Channel<Pair<FrameTicket,Resource>>(Channel.UNLIMITED)
        var calls=0
        val pump=LatestFramePump(owner,render={ _,_->
            if(++calls==1) { start.countDown();check(release.await(10,TimeUnit.SECONDS));first } else Resource()
        },publish={ t,r,_->results.trySend(t to r) })
        try {
            val old=request()
            withContext(Dispatchers.Main) { pump.offer(old) };await(start)
            withContext(Dispatchers.Main) { pump.offer(old.copy(width=640,scene=old.scene.copy(bed=Color.Blue))) }
            release.countDown()
            val published=withTimeout(5000) { results.receive() }
            assertEquals(2L,published.first.sequence);assertEquals(1,first.closes)
            assertTrue(results.tryReceive().isFailure);published.second.close()
        } finally { release.countDown();withContext(Dispatchers.Main) { pump.close() };owner.cancel() }
    }

    @Test(timeout=20000) fun cancellationLostResultAndWorkerBackendAreClosedOnDisposal()=runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val start=CountDownLatch(1);val release=CountDownLatch(1);val disposed=CompletableDeferred<Unit>()
        val resource=Resource();var published=false
        val pump=LatestFramePump(owner,render={ _,_->start.countDown();check(release.await(10,TimeUnit.SECONDS));resource },
            publish={ _,_,_->published=true },dispose={ assertFalse(SwingUtilities.isEventDispatchThread());disposed.complete(Unit) })
        try {
            withContext(Dispatchers.Main) { pump.offer(request()) };await(start)
            withContext(Dispatchers.Main) { pump.close() };release.countDown()
            withTimeout(5000) { disposed.await() }
            assertFalse(published);assertEquals(1,resource.closes)
        } finally { release.countDown();owner.cancel() }
    }

    @Test(timeout=20000) fun ordinaryFrameFailureCanRetryWithoutPoisoningThePump()=runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val failed=CompletableDeferred<Unit>();val ready=CompletableDeferred<Resource>();var calls=0
        val pump=LatestFramePump(owner,render={ _,_->if(++calls==1)throw IllegalArgumentException("fixture") else Resource() },
            publish={ _,r,_->ready.complete(r) },failed={ assertTrue(SwingUtilities.isEventDispatchThread());failed.complete(Unit) })
        try {
            withContext(Dispatchers.Main) { pump.offer(request()) }
            withTimeout(5000) { failed.await() }
            withContext(Dispatchers.Main) { pump.offer(request().copy(retry=1)) }
            withTimeout(5000) { ready.await() }.close();assertEquals(2,calls)
        } finally { withContext(Dispatchers.Main) { pump.close() };owner.cancel() }
    }

    @Test fun cameraIsCapturedAndOnlyCameraChangesMayReuseAFrameSource() {
        val camera=ViewCamera();val snapshot=camera.snapshot();camera.orbit(100f,100f)
        assertNotEquals(snapshot,camera.snapshot())
        val a=request();assertTrue(a.sameSource(a.copy(camera=snapshot)))
        assertFalse(a.sameSource(a.copy(width=a.width+1)))
        assertFalse(a.sameSource(a.copy(scene=a.scene.copy(bed=Color.Red))))
    }

    private fun grid(n:Int=50):Mesh {
        val v=FloatArray((n+1)*(n+1)*3) { i->when(i%3){0->(i/3%(n+1)).toFloat();1->(i/3/(n+1)).toFloat();else->0f} }
        val t=IntArray(n*n*6)
        for(y in 0 until n)for(x in 0 until n) { val a=y*(n+1)+x;val k=(y*n+x)*6;t[k]=a;t[k+1]=a+1;t[k+2]=a+n+1;t[k+3]=a+1;t[k+4]=a+n+2;t[k+5]=a+n+1 }
        return Mesh(v,t)
    }
    private fun obj(mesh:Mesh,color:Color=Color.Gray,selected:Boolean=false,x:Float=128f)=PlateObject(1,"fixture",mesh,color,x,128f,23f,1f,selected,sx=-1f,sy=1.2f,sz=.8f)

    @Test fun zoomAndPanReuseColorsButOrientationMaterialSelectionAndPoseInvalidate() {
        val mesh=grid();val d=displayMesh(mesh);val o=obj(mesh);val cache=DepthSurfaceCache();val camera=ViewCamera()
        fun frame(objectToDraw:PlateObject=o) { val s=cache.frame(320f,240f);rasterObject(Projector(320f,240f,camera,128f,128f),objectToDraw,d,s) }
        frame();val scratch=cache.frame(320f,240f).objectScratch;assertEquals(1,scratch.shadingPasses)
        camera.distance=500f;camera.pan=Offset(12f,15f);frame();assertEquals(1,scratch.shadingPasses)
        camera.orbit(3f,2f);frame();assertEquals(2,scratch.shadingPasses)
        frame(obj(mesh,color=Color.Red));assertEquals(3,scratch.shadingPasses)
        frame(obj(mesh,color=Color.Red,selected=true));assertEquals(4,scratch.shadingPasses)
        frame(obj(mesh,color=Color.Red,selected=true,x=130f));assertEquals(5,scratch.shadingPasses)
    }

    @Test fun cancelledPartialColorOverwriteCannotHitThePreviousShadingKey() {
        val mesh=grid();val d=displayMesh(mesh);val o=obj(mesh);val camera=ViewCamera();val surface=DepthSurface(320f,240f)
        rasterObject(Projector(320f,240f,camera,128f,128f),o,d,surface)
        val pose=surface.objectScratch.pose(o,d,{})
        val original=pose.colors.copyOf();val oldCamera=camera.snapshot();camera.orbit(40f,20f)
        try {
            rasterObject(Projector(320f,240f,camera,128f,128f),o,d,surface) {
                // Observe actual writes, not a checkpoint count that could fire
                // before the color loop on a differently sized fixture.
                if(pose.shadingKey==null && !original.contentEquals(pose.colors))throw ObsoleteFrame()
            }
            fail("expected cancellation during color overwrite")
        } catch(_:ObsoleteFrame) { }
        assertNull(pose.shadingKey)
        assertFalse(original.contentEquals(pose.colors))
        surface.clear();rasterObject(Projector(320f,240f,oldCamera,128f,128f),o,d,surface)
        assertArrayEquals(original,pose.colors)
    }

    @Test fun poseCacheIsByteBoundedAndDropsInactiveSceneReferences() {
        val mesh=grid(100);val d=displayMesh(mesh);val scratch=ObjectRasterScratch()
        repeat(140) { scratch.pose(obj(mesh,x=it.toFloat()),d,{});assertTrue(scratch.retainedBytes<=ObjectRasterScratch.POSE_CACHE_BYTES) }
        assertTrue(scratch.retainedBytes<=ObjectRasterScratch.POSE_CACHE_BYTES)
        val kept=obj(mesh,x=139f);val bytes=scratch.pose(kept,d,{}).bytes
        scratch.retain(listOf(kept to d));assertEquals(bytes,scratch.retainedBytes)
        scratch.retain(emptyList());assertEquals(0L,scratch.retainedBytes)
    }

    @Test fun multipleObjectsReuseTheirOwnColorsAcrossFramesAndResize() {
        val a=grid(12);val b=grid(15)
        for(i in 0 until b.vertexCount)b.vertices[i*3+2]=(i%7).toFloat()
        val scene=listOf(obj(a,color=Color.Red,x=115f) to displayMesh(a),obj(b,color=Color.Blue,x=140f) to displayMesh(b))
        val cache=DepthSurfaceCache();var scratch:ObjectRasterScratch?=null
        for((width,height) in listOf(320 to 240,320 to 240,400 to 280,320 to 240)) {
            val surface=cache.frame(width.toFloat(),height.toFloat())
            if(scratch==null)scratch=surface.objectScratch else assertSame(scratch,surface.objectScratch)
            val reference=ReferenceDepthSurface(width.toFloat(),height.toFloat())
            val proj=Projector(width.toFloat(),height.toFloat(),request().camera,128f,128f)
            val canvas=Canvas(ImageBitmap(width,height))
            CanvasDrawScope().draw(Density(1f),LayoutDirection.Ltr,canvas,Size(width.toFloat(),height.toFloat())) {
                for((o,d) in scene) { rasterObject(proj,o,d,surface);referenceObject(proj,o,d,reference) }
            }
            assertArrayEquals(reference.pixels,surface.pixels)
            assertEquals(2,surface.objectScratch.shadingPasses)
        }
        cache.release();assertNotSame(scratch,cache.frame(320f,240f).objectScratch)
    }

    @Test(timeout=20000) fun interruptionStillJoinsWorkersAndRestoresOwnerInterrupt() {
        org.junit.Assume.assumeTrue(RasterWorkers.count>1)
        val started=CountDownLatch(RasterWorkers.count-1);val joining=CountDownLatch(1);val release=CountDownLatch(1)
        val finished=java.util.concurrent.atomic.AtomicInteger()
        val ownerThread=java.util.concurrent.atomic.AtomicReference<Thread>()
        val owner=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result=owner.submit<Boolean> {
                ownerThread.set(Thread.currentThread())
                try {
                    RasterWorkers.run { worker ->
                        if(worker==0) { await(started);joining.countDown() }
                        else { assertTrue(Thread.currentThread().isDaemon);started.countDown();await(release);finished.incrementAndGet() }
                    }
                    false
                } catch(_:InterruptedException) { Thread.currentThread().isInterrupted }
                finally { Thread.interrupted() }
            }
            await(joining);ownerThread.get().interrupt();assertFalse(result.isDone)
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(ownerThread.get().isInterrupted && System.nanoTime()<deadline)Thread.yield()
            assertFalse("Future.get must consume interruption before workers release",ownerThread.get().isInterrupted)
            release.countDown();assertTrue(result.get(10,TimeUnit.SECONDS))
            assertEquals(RasterWorkers.count-1,finished.get())
        } finally { release.countDown();owner.shutdownNow() }
    }

    @Test fun workerCompositeMatchesFrozenFullQualityPixelsAndSnapshotsSurviveReuseResizeClose() {
        val mesh=grid(10);val d=displayMesh(mesh);val o=obj(mesh)
        val first=request().copy(scene=request().scene.copy(prepared=listOf(o to d)))
        val rasterizer=FrameRasterizer(useGraphics=false)
        fun pixels(image:org.jetbrains.skia.Image)=IntArray(image.width*image.height).also { image.toComposeImageBitmap().readPixels(it) }
        val image=rasterizer.render(first,{})
        try {
            val actual=pixels(image)
            val bitmap=ImageBitmap(first.width,first.height);val canvas=Canvas(bitmap);val old=ReferenceDepthSurface(first.width.toFloat(),first.height.toFloat())
            CanvasDrawScope().draw(Density(1f),LayoutDirection.Ltr,canvas,Size(first.width.toFloat(),first.height.toFloat())) {
                val proj=first.projector();drawBed(proj,256f,256f,first.scene.bed,first.scene.line)
                referenceShadow(proj,o,d);referenceObject(proj,o,d,old)
                val info=org.jetbrains.skia.ImageInfo(old.width,old.height,org.jetbrains.skia.ColorType.BGRA_8888,org.jetbrains.skia.ColorAlphaType.PREMUL)
                org.jetbrains.skia.Image.makeRaster(info,old.pixels,old.width*4).use { drawIntoCanvas { c->c.nativeCanvas.drawImageRect(it,org.jetbrains.skia.Rect.makeWH(size.width,size.height)) } }
            }
            assertArrayEquals(IntArray(first.width*first.height).also { bitmap.readPixels(it) },actual)
            rasterizer.render(first.copy(camera=first.camera.copy(yaw=12f)),{}).use { assertArrayEquals(actual,pixels(image)) }
            rasterizer.render(first.copy(width=160,height=120),{}).use { assertEquals(160,it.width) }
            rasterizer.close();assertArrayEquals(actual,pixels(image))
        } finally { image.close();rasterizer.close() }
    }

    @Test fun disjointBandsMatchFrozenSerialCoverageDepthColorAndTies() {
        val random=Random(8327);val count=240
        val triangles=IntArray(count*3) { it };val mesh=Mesh(FloatArray(count*9),triangles)
        val d=DisplayMesh(mesh,FloatArray(count*9),mesh.bounds())
        for((width,height) in listOf(1 to 1,17 to 3,64 to 49)) {
            val actual=DepthSurface(width.toFloat(),height.toFloat());val expected=ReferenceDepthSurface(width.toFloat(),height.toFloat())
            val screen=FloatArray(count*9) { i->if(i%3==2)(random.nextInt(3)-1)*5f else random.nextFloat()*240-80 }
            val colors=IntArray(count*3) { random.nextInt() or 0xff000000.toInt() }
            repeat(16) { i->val at=i*9;screen[at]=-100f;screen[at+1]=-100f;screen[at+3]=200f;screen[at+4]=200f;screen[at+6]=200f;screen[at+7]=200f+i*.0001f }
            val proj=Projector(width.toFloat(),height.toFloat(),request().camera.copy(distance=20f/1.8f),128f,128f)
            for(i in 0 until count)expected.triangle(screen,i*9,colors,i*3,proj.eye)
            actual.bands.drawTiled(proj,d,screen,colors,actual,{})
            assertArrayEquals("bands ${width}x$height",expected.pixels,actual.pixels)
            assertTrue(actual.bands.indexCapacity<=RasterBands.INDEX_BUDGET)
        }
    }

    @Test(timeout=20000) fun rasterFailureJoinsEveryStartedWorkerBeforeReturningScratchOwnership() {
        org.junit.Assume.assumeTrue(RasterWorkers.count>1)
        val started=CountDownLatch(RasterWorkers.count-1);val release=CountDownLatch(1)
        val finished=java.util.concurrent.atomic.AtomicInteger()
        val owner=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result=owner.submit<Boolean> {
                try { RasterWorkers.run { worker->
                    if(worker==0)throw IllegalArgumentException("fixture")
                    started.countDown();check(release.await(10,TimeUnit.SECONDS));finished.incrementAndGet()
                };false } catch(_:IllegalArgumentException) { true }
            }
            await(started);assertFalse(result.isDone)
            release.countDown();assertTrue(result.get(10,TimeUnit.SECONDS));assertEquals(RasterWorkers.count-1,finished.get())
        } finally { release.countDown();owner.shutdownNow() }
    }

    @Test fun excessiveTileDuplicationFallsBackBeforeAllocatingOverBudget() {
        val count=11000;val mesh=Mesh(FloatArray(count*9),IntArray(count*3) { it })
        val d=DisplayMesh(mesh,FloatArray(count*9),mesh.bounds());val surface=DepthSurface(64f,64f)
        val triangle=floatArrayOf(-100f,-100f,0f,200f,-100f,0f,0f,200f,0f)
        val screen=FloatArray(count*9) { triangle[it%9] };val colors=IntArray(count*3) { 0xffaabbcc.toInt() }
        surface.bands.drawTiled(request().projector(),d,screen,colors,surface,{})
        val expected=ReferenceDepthSurface(64f,64f)
        repeat(count) { expected.triangle(screen,it*9,colors,it*3,request().projector().eye) }
        assertArrayEquals(expected.pixels,surface.pixels)
        assertEquals(1,surface.bands.budgetFallbacks)
        assertTrue(surface.bands.indexCapacity<=RasterBands.INDEX_BUDGET)
    }

    @Test fun rasterPolicyBoundaryPreservesFrozenPixels() {
        val mesh=Mesh(FloatArray(27),IntArray(9) { it });val d=DisplayMesh(mesh,FloatArray(27),mesh.bounds())
        val screen=floatArrayOf(-20f,-20f,0f,1700f,20f,0f,300f,1700f,0f,
            -20f,-20f,0f,1700f,20f,0f,300f,1700f,0f, 0f,0f,-1f,1600f,1500f,-1f,1600f,1500.001f,-1f)
        val colors=IntArray(9) { (0xff000000L or (it*1234567L)).toInt() }
        for(height in listOf(1562,1563)) {
            val actual=DepthSurface(1600f,height.toFloat());val expected=ReferenceDepthSurface(1600f,height.toFloat())
            val proj=Projector(1600f,height.toFloat(),request().camera,128f,128f)
            actual.bands.draw(proj,d,screen,colors,actual,{})
            repeat(3) { expected.triangle(screen,it*9,colors,it*3,proj.eye) }
            assertArrayEquals("policy boundary height=$height",expected.pixels,actual.pixels)
        }
    }
}
