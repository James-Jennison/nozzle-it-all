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

/** Disjoint rows of ONE shared z/color surface, preserving original triangle order
 * within every band and the sequential order of objects. No depth merge/rounding
 * approximation or painter-sort fallback. At most16 indices per source triangle;
 * buffers grow to the largest observed mesh, never per-frame arrays or size LRUs.
 */
internal class RasterBands {
    private val count=16
    private val bins=Array(count) { IntArray(0) }
    private val sizes=IntArray(count)
    private var ranges=IntArray(0)
    private val triangles=Array(RasterWorkers.count) { FloatArray(9) }
    internal val indexCapacity get()=bins.sumOf { it.size.toLong() }

    fun draw(proj:Projector,d:DisplayMesh,screen:FloatArray,colors:IntArray,surface:DepthSurface,check:()->Unit) {
        if(ranges.size<d.tris.size)ranges=IntArray(d.tris.size)
        sizes.fill(0)
        val t=d.mesh.triangles;val rows=(surface.height+count-1)/count
        for(tri in d.tris) {
            if(tri and 1023==0)check()
            val a=t[tri*3]*3;val b=t[tri*3+1]*3;val c=t[tri*3+2]*3
            val minX=minOf(screen[a],screen[b],screen[c]);val maxX=maxOf(screen[a],screen[b],screen[c])
            val minY=minOf(screen[a+1],screen[b+1],screen[c+1]);val maxY=maxOf(screen[a+1],screen[b+1],screen[c+1])
            if(maxX < -1 || minX > proj.w+1 || maxY < -1 || minY > proj.h+1) { ranges[tri]=-1;continue }
            val first=max(0,floor(minY*surface.scale).toInt());val last=min(surface.height-1,ceil(maxY*surface.scale).toInt())
            if(first>last) { ranges[tri]=-1;continue }
            val lo=first/rows;val hi=last/rows
            ranges[tri]=(lo shl 16) or hi
            for(band in lo..hi)sizes[band]++
        }
        for(band in 0 until count) {
            if(bins[band].size<sizes[band])bins[band]=IntArray(max(sizes[band],bins[band].size*3/2).coerceAtMost(d.tris.size))
        }
        sizes.fill(0)
        for(tri in d.tris) {
            if(tri and 1023==0)check()
            val range=ranges[tri];if(range<0)continue
            for(band in (range ushr 16)..(range and 0xffff))bins[band][sizes[band]++]=tri
        }
        RasterWorkers.run { worker ->
            val triangle=triangles[worker]
            var band=worker
            while(band<count) {
                for(index in 0 until sizes[band]) {
                    if(index and 1023==0)check()
                    val tri=bins[band][index]
                    for(k in 0..2) { val at=t[tri*3+k]*3;for(axis in 0..2)triangle[k*3+axis]=screen[at+axis] }
                    surface.triangle(triangle,0,colors,tri*3,proj.eye,check,band*rows,min(surface.height,(band+1)*rows))
                }
                band+=RasterWorkers.count
            }
        }
    }
}
