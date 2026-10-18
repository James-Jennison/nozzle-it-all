package com.nozzleitall.desktop.prepare

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

/** Pure numeric workers, at most4 total per frame, leaving a CPU for UI where
 * possible. No Skia, Compose state, scene caches or native handles on these threads.
 * The frame owner joins EVERY worker even on failure/cancellation before scratch
 * reuse, source mutation or surface disposal. Production frame owners serialize.
 */
internal object RasterWorkers {
    val count=min(4,(Runtime.getRuntime().availableProcessors()-1).coerceAtLeast(1))
    private val serial=AtomicInteger()
    private val pool=if(count>1)Executors.newFixedThreadPool(count-1) { work->
        Thread(work,"nia-raster-${serial.incrementAndGet()}").apply { isDaemon=true }
    } else null
    fun run(work:(Int)->Unit) {
        if(pool==null) { work(0);return }
        val jobs=ArrayList<java.util.concurrent.Future<*>>(count-1)
        var error:Throwable?=null
        var interrupted=false
        try {
            for(worker in 1 until count)jobs += pool.submit { work(worker) }
            work(0)
        } catch(e:Throwable) { error=e }
        for(job in jobs) {
            // Do not cancel a Future then assume it stopped: get() must account
            // for actual worker completion before any arrays/native owner reuse.
            var waiting=true
            while(waiting)try { job.get();waiting=false }
            catch(e:ExecutionException) { if(error==null)error=e.cause ?: e;waiting=false }
            catch(e:InterruptedException) { if(error==null)error=e;interrupted=true /* finish joining first */ }
        }
        if(interrupted)Thread.currentThread().interrupt()
        error?.let { throw it }
    }
}

/** Disjoint2D tiles of ONE z/color surface; each pixel retains original triangle
 * and object order. No depth merge/rounding approximation. A16x16 grid balances
 * objects with narrow vertical footprints better than whole-width row bands.
 * Index storage is hard capped at2M ints (8MB); over-budget binning falls back to
 * the identical serial rasterizer, not reduced geometry/quality. Reused buffers.
 */
internal class RasterBands(private val useTiles:Boolean=true) {
    private val side=16
    init { require(side in 1..16) { "Tile coordinates occupy four bits" } }
    private val count=side*side
    private val bins=Array(count) { IntArray(0) }
    private val sizes=IntArray(count)
    private var ranges=IntArray(0)
    private val triangles=Array(RasterWorkers.count) { FloatArray(9) }
    private val active=IntArray(count)
    private val next=AtomicInteger()
    internal val indexCapacity get()=bins.sumOf { it.size.toLong() }
    internal var budgetFallbacks=0;private set
    companion object { const val INDEX_BUDGET=2_000_000 }

    fun draw(proj:Projector,d:DisplayMesh,screen:FloatArray,colors:IntArray,surface:DepthSurface,check:()->Unit) {
        // Measurements show scheduling/binning overhead outweighs the pixel work
        // below this size. This selects an exact algorithm, never a detail level.
        if(!useTiles || surface.width.toLong()*surface.height<2_500_000L) {
            serial(proj,d,screen,colors,surface,check);return
        }
        drawTiled(proj,d,screen,colors,surface,check)
    }
    internal fun drawTiled(proj:Projector,d:DisplayMesh,screen:FloatArray,colors:IntArray,surface:DepthSurface,check:()->Unit) {
        if(ranges.size<d.tris.size)ranges=IntArray(d.tris.size)
        sizes.fill(0)
        val t=d.mesh.triangles
        val rows=(surface.height+side-1)/side;val columns=(surface.width+side-1)/side
        var needed=0L
        for(tri in d.tris) {
            if(tri and 1023==0)check()
            val a=t[tri*3]*3;val b=t[tri*3+1]*3;val c=t[tri*3+2]*3
            val minX=minOf(screen[a],screen[b],screen[c]);val maxX=maxOf(screen[a],screen[b],screen[c])
            val minY=minOf(screen[a+1],screen[b+1],screen[c+1]);val maxY=maxOf(screen[a+1],screen[b+1],screen[c+1])
            if(maxX < -1 || minX > proj.w+1 || maxY < -1 || minY > proj.h+1) { ranges[tri]=-1;continue }
            val first=max(0,floor(minY*surface.scale).toInt());val last=min(surface.height-1,ceil(maxY*surface.scale).toInt())
            val left=max(0,floor(minX*surface.scale).toInt());val right=min(surface.width-1,ceil(maxX*surface.scale).toInt())
            if(first>last || left>right) { ranges[tri]=-1;continue }
            val top=first/rows;val bottom=last/rows;val lo=left/columns;val hi=right/columns
            ranges[tri]=(lo shl 12) or (hi shl 8) or (top shl 4) or bottom
            needed+=(hi-lo+1)*(bottom-top+1)
            if(needed>INDEX_BUDGET) { budgetFallbacks++;serial(proj,d,screen,colors,surface,check);return }
            for(y in top..bottom)for(x in lo..hi)sizes[y*side+x]++
        }
        val capacities=IntArray(count) { i->if(bins[i].size<sizes[i])max(sizes[i],bins[i].size*3/2).coerceAtMost(d.tris.size) else bins[i].size }
        val trim=capacities.sumOf { it.toLong() }>INDEX_BUDGET
        var activeCount=0
        for(tile in 0 until count) {
            val capacity=if(trim)sizes[tile] else capacities[tile]
            if(bins[tile].size!=capacity)bins[tile]=IntArray(capacity)
            if(sizes[tile]>0)active[activeCount++]=tile
        }
        sizes.fill(0)
        for(tri in d.tris) {
            if(tri and 1023==0)check()
            val range=ranges[tri];if(range<0)continue
            for(y in ((range ushr 4) and 15)..(range and 15))for(x in ((range ushr 12) and 15)..((range ushr 8) and 15)) {
                val tile=y*side+x;bins[tile][sizes[tile]++]=tri
            }
        }
        next.set(0)
        RasterWorkers.run { worker ->
            val triangle=triangles[worker]
            while(true) {
                val work=next.getAndIncrement();if(work>=activeCount)break
                val tile=active[work];val x=tile%side;val y=tile/side
                for(index in 0 until sizes[tile]) {
                    if(index and 1023==0)check()
                    val tri=bins[tile][index]
                    for(k in 0..2) { val at=t[tri*3+k]*3;for(axis in 0..2)triangle[k*3+axis]=screen[at+axis] }
                    surface.triangle(triangle,0,colors,tri*3,proj.eye,check,y*rows,min(surface.height,(y+1)*rows),x*columns,min(surface.width,(x+1)*columns))
                }
            }
        }
    }
    private fun serial(proj:Projector,d:DisplayMesh,screen:FloatArray,colors:IntArray,surface:DepthSurface,check:()->Unit) {
        val t=d.mesh.triangles;val triangle=triangles[0]
        for(tri in d.tris) {
            if(tri and 1023==0)check()
            for(k in 0..2) { val at=t[tri*3+k]*3;for(axis in 0..2)triangle[k*3+axis]=screen[at+axis] }
            if(maxOf(triangle[0],triangle[3],triangle[6]) < -1 || minOf(triangle[0],triangle[3],triangle[6]) > proj.w+1 || maxOf(triangle[1],triangle[4],triangle[7]) < -1 || minOf(triangle[1],triangle[4],triangle[7]) > proj.h+1)continue
            surface.triangle(triangle,0,colors,tri*3,proj.eye,check)
        }
    }
}
