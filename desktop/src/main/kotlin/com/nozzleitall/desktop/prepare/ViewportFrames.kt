package com.nozzleitall.desktop.prepare

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** Captured immutable-by-ownership scene; no Compose state is read by the renderer. */
internal data class FrameScene(val prepared:List<Pair<PlateObject,DisplayMesh>>,val bedW:Float,val bedD:Float,val bed:Color,val line:Color,val background:Color=Color.Transparent)
internal data class FrameRequest(val scene:FrameScene,val camera:CameraPose,val width:Int,val height:Int,val retry:Int=0) {
    fun sameSource(other:FrameRequest) = scene==other.scene && width==other.width && height==other.height
    fun projector() = Projector(width.toFloat(),height.toFloat(),camera,scene.bedW/2,scene.bedD/2)
}
internal data class FrameTicket(val sequence:Long,val requestedAt:Long,val request:FrameRequest)
internal class ObsoleteFrame:RuntimeException(null,null,false,false)
private val frameDispatcher=Dispatchers.Default.limitedParallelism(1)

/** Main-owned control, one in-flight + one conflated pending frame. Camera-only
 * supersession must NOT cancel/reject every frame (that would starve continuous
 * orbit). Completed frames may present for the same scene/size; changed source
 * results never publish. Final settled camera is rendered from the pending slot.
 * One additional conflated immutable result can wait while Main is drawing.
 * T is immutable after return; publish transfers ownership to Main, all rejected
 * or cancellation-lost results close here, including prompt dispatcher cancellation.
 */
internal class LatestFramePump<T:AutoCloseable>(scope:CoroutineScope,
    private val render:(FrameRequest,()->Unit)->T,
    private val publish:(FrameTicket,T,Long)->Unit,
    private val failed:(FrameTicket)->Unit = {},
    private val dispose:()->Unit = {}) {
    private val requests=Channel<FrameTicket>(Channel.CONFLATED)
    private val latest=AtomicReference<FrameTicket?>()
    private var sequence=0L
    private class Completed<T:AutoCloseable>(val ticket:FrameTicket,val value:T,val nanos:Long):AutoCloseable {
        override fun close()=value.close()
    }
    // One waiting immutable result, never an unbounded queue. Replaced or
    // cancellation-undelivered results have never reached UI drawing and close
    // here. Signal separately: a channel receiver can otherwise retain an old
    // image inside its queued Main continuation, defeating result conflation.
    private val pending=AtomicReference<Completed<T>?>()
    private val results=Channel<Unit>(Channel.CONFLATED)
    private val publisher=scope.launch(Dispatchers.Main.immediate) {
        try {
            for(signal in results) {
                val result=pending.getAndSet(null) ?: continue
                var owned:T?=result.value
                try {
                    if(latest.get()?.request?.sameSource(result.ticket.request)==true) {
                        publish(result.ticket,result.value,result.nanos)
                        owned=null
                        frameDiagnostic("published",result.ticket)
                    } else frameDiagnostic("discarded",result.ticket)
                } finally { owned?.close() }
            }
        } finally { results.cancel();pending.getAndSet(null)?.close() }
    }
    private val job=scope.launch(frameDispatcher) {
        try {
            for(ticket in requests) {
                var owned:T?=null
                var renderNanos=0L
                try {
                    val context=coroutineContext
                    val check={ context.ensureActive();if(latest.get()?.request?.sameSource(ticket.request)!=true)throw ObsoleteFrame() }
                    check();frameDiagnostic("started",ticket)
                    val start=System.nanoTime()
                    owned=render(ticket.request,check)
                    renderNanos=System.nanoTime()-start
                    check()
                    val completed=Completed(ticket,owned!!,renderNanos)
                    owned=null // atomic slot/publisher owns it now.
                    pending.getAndSet(completed)?.close()
                    if(results.trySend(Unit).isFailure)pending.getAndSet(null)?.close()
                } catch (_:ObsoleteFrame) { frameDiagnostic("discarded",ticket) }
                catch(e:CancellationException) { throw e }
                catch (_:Exception) {
                    withContext(Dispatchers.Main.immediate) { if(latest.get()?.request==ticket.request)failed(ticket) }
                    frameDiagnostic("failed",ticket)
                } finally { owned?.close() }
            }
        } finally {
            results.close()
            withContext(NonCancellable+frameDispatcher) { dispose() }
        }
    }
    fun offer(request:FrameRequest) {
        if(latest.get()?.request==request)return
        val ticket=FrameTicket(++sequence,System.nanoTime(),request)
        latest.set(ticket);requests.trySend(ticket);frameDiagnostic("requested",ticket)
    }
    fun invalidate() { latest.set(null);requests.tryReceive() }
    /** Nonblocking Main teardown. The pump exclusively owns its backend; no
     * caller may close it separately. Its finally disposes after render/joins,
     * including noncooperative work. Blocking Main here would defeat responsiveness.
     */
    fun close() { latest.set(null);requests.close();job.cancel();publisher.cancel();results.cancel();pending.getAndSet(null)?.close() }
}

internal data class ViewportFrame(val ticket:FrameTicket,val image:Image,val renderNanos:Long):AutoCloseable {
    override fun close()=image.close()
}

/** Worker-confined raster buffers and Skia surface. Snapshots are immutable,
 * reference-counted images; they remain valid after surface reuse/resize/close.
 * Full physical viewport resolution and existing depth-sample cap are preserved.
 */
internal class FrameRasterizer(private val useGraphics:Boolean=true,private val compositeBed:Boolean=true):AutoCloseable {
    private var target:Surface?=null
    private var width=0;private var height=0
    private val depth=DepthSurfaceCache()
    private var graphics:GraphicsViewport?=null
    private var graphicsChecked=false
    fun render(request:FrameRequest,check:()->Unit):Image {
        check();require(request.width>0 && request.height>0)
        if(!graphicsChecked) {
            graphicsChecked=true
            if(useGraphics && System.getenv("NOZZLE_VIEWPORT_RENDERER")!="software")graphics=GraphicsViewport.createOrNull()
            if(System.getenv("NOZZLE_VIEWPORT_TIMING")=="1")println("NIA_GRAPHICS backend=${if(graphics!=null)"gles3" else "cpu"} renderer=${graphics?.renderer ?: "software"}")
        }
        val accelerated=try { graphics?.let { if(compositeBed)it.composite(request,check) else it.render(request,check) } }
        catch(e:ObsoleteFrame) { throw e }
        catch(e:CancellationException) { throw e }
        catch(_:IllegalStateException) { graphics?.close();graphics=null;null }
        if(accelerated!=null)return accelerated
        if(width!=request.width || height!=request.height) {
            target?.close();target=null
            width=request.width;height=request.height
            target=Surface.makeRasterN32Premul(width,height)
        }
        val target=target ?: Surface.makeRasterN32Premul(width,height).also { this.target=it }
        target.canvas.clear(0)
        val scene=request.scene;val proj=request.projector()
        CanvasDrawScope().draw(Density(1f),LayoutDirection.Ltr,target.canvas.asComposeCanvas(),Size(width.toFloat(),height.toFloat())) {
            drawBed(proj,scene.bedW,scene.bedD,scene.bed,scene.line)
            scene.prepared.forEach { (o,d)->check();rasterShadow(proj,o,d,depth.shadows,check) }
            if(scene.prepared.isNotEmpty()) {
                val surface=depth.frame(size.width,size.height)
                surface.objectScratch.retain(scene.prepared)
                scene.prepared.forEach { (o,d)->check();rasterObject(proj,o,d,surface,check) }
                check()
                val info=org.jetbrains.skia.ImageInfo(surface.width,surface.height,org.jetbrains.skia.ColorType.BGRA_8888,org.jetbrains.skia.ColorAlphaType.PREMUL)
                Image.makeRaster(info,surface.pixels,surface.width*4).use { image ->
                    drawIntoCanvas { it.nativeCanvas.drawImageRect(image,org.jetbrains.skia.Rect.makeWH(size.width,size.height)) }
                }
            } else depth.release()
        }
        check()
        return target.makeImageSnapshot()
    }
    override fun close() { graphics?.close();graphics=null;target?.close();target=null;depth.release() }
}

/** Explicit local metadata-only diagnostics. No model names/paths/content. */
internal fun frameDiagnostic(event:String,ticket:FrameTicket?=null,renderNanos:Long=0) {
    if(System.getenv("NOZZLE_VIEWPORT_TIMING")=="1")
        println("NIA_RENDER event=$event seq=${ticket?.sequence ?: 0} requested_ns=${ticket?.requestedAt ?: 0} time_ns=${System.nanoTime()} render_ns=$renderNanos width=${ticket?.request?.width ?: 0} height=${ticket?.request?.height ?: 0} objects=${ticket?.request?.scene?.prepared?.size ?: 0} ui=${javax.swing.SwingUtilities.isEventDispatchThread()}")
}
