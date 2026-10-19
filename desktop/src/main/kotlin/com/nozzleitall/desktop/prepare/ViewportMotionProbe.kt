package com.nozzleitall.desktop.prepare

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Explicit local acceptance probe, never enabled by default. Only the camera
 * changes; no model, profile, printer or external provider operation is invoked.
 * Real desktop pixels test presentation independently of OS input injection.
 */
internal suspend fun viewportMotionProbe(camera:ViewCamera,steps:Int=360,periodNanos:Long=1_000_000_000L/60,
    pauseMillis:Long=5000,marker:(String,String,Long)->Unit) = withContext(Dispatchers.Main.immediate) {
    require(steps>0 && periodNanos>0 && pauseMillis>=0)
    for(mode in listOf("orbit","zoom")) {
        if(mode=="zoom")delay(pauseMillis)
        val start=System.nanoTime();marker("begin",mode,start)
        for(i in 0 until steps) {
            coroutineContext.ensureActive()
            val remaining=start+i*periodNanos-System.nanoTime()
            if(remaining>0)delay((remaining/1_000_000).coerceAtLeast(1))
            if(mode=="orbit")camera.orbit(-2f,1.5f)
            else camera.distance=(camera.distance*(if((i/15)%2==0).9f else 1.1f)).coerceIn(80f,2500f)
        }
        marker("end",mode,System.nanoTime())
    }
}
