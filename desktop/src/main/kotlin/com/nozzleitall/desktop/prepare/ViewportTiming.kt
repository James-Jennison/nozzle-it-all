package com.nozzleitall.desktop.prepare

import org.json.JSONObject
import kotlin.math.ceil

internal fun viewportP95Index(count:Int)=(ceil(count*.95).toInt()-1).coerceIn(0,count-1)
internal fun viewportMedian(sorted:LongArray):Double =
    if(sorted.size%2==0)sorted[sorted.size/2-1]/2.0+sorted[sorted.size/2]/2.0 else sorted[sorted.size/2].toDouble()

/** Explicit local diagnostic only. Never enabled by default, persisted or sent
 * over the network; reports frame sizes/counts/times, not project/model content. */
internal class ViewportTiming {
    val enabled=System.getenv("NOZZLE_VIEWPORT_TIMING")=="1"
    private val samples=LongArray(30)
    private var count=0;private var width=0;private var height=0
    fun start()=if(enabled)System.nanoTime() else 0L
    fun finish(start:Long,w:Int,h:Int,objects:Int) {
        if(start==0L)return
        synchronized(this) {
        if(width!=w || height!=h) { count=0;width=w;height=h }
        samples[count++]=System.nanoTime()-start
        if(count==samples.size) {
            val sorted=samples.sortedArray()
            println("NIA_VIEWPORT_FRAME "+JSONObject().put("measurement","Canvas CPU pass, not display/compositor FPS")
                .put("width",w).put("height",h).put("objects",objects).put("samples",count)
                .put("median_ms",viewportMedian(sorted)/1e6).put("p95_ms",sorted[viewportP95Index(count)]/1e6).put("max_ms",sorted.last()/1e6))
            count=0
        }
        }
    }
}
