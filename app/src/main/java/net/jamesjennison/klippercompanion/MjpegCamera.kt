package net.jamesjennison.klippercompanion

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal class CameraFrameGate {
    private var lastDelivered: Long? = null
    fun accept(now: Long): Boolean {
        if(lastDelivered?.let { now-it<66_666_667L } == true) return false
        lastDelivered=now
        return true
    }
}

/** Bounded JPEG extraction from an MJPEG multipart stream. No HTML is executed. */
fun nextJpeg(input: InputStream, maximum: Int = 2_000_000): ByteArray {
    var previous = -1;var scanned = 0
    while(true) {
        val byte=input.read();if(byte<0) throw ApiFailure("Camera stream ended.")
        if(++scanned > maximum) throw ApiFailure("No JPEG frame within supported limit.")
        if(previous==255 && byte==216) break
        previous=byte
    }
    val frame=ByteArrayOutputStream().apply { write(255);write(216) };previous=216
    while(frame.size() < maximum) {
        val byte=input.read();if(byte<0) throw ApiFailure("Incomplete camera frame.")
        frame.write(byte);if(previous==255 && byte==217) return frame.toByteArray();previous=byte
    }
    throw ApiFailure("Camera frame exceeds supported limit.")
}
@Composable fun MjpegCamera(address: String, camera: Camera) {
    val endpoint=remember(address,camera.stream) { runCatching { Moonraker.cameraUrl(address,camera.stream) }.getOrNull() }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,e -> if(e==Lifecycle.Event.ON_START) active=true else if(e==Lifecycle.Event.ON_STOP) active=false }
        lifecycle.addObserver(observer);onDispose { lifecycle.removeObserver(observer) }
    }
    var bitmap by remember(endpoint,active) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var label by remember(endpoint,active) { mutableStateOf("Connecting MJPEG video…") }
    val client=remember { OkHttpClient.Builder().connectTimeout(4,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build() }
    DisposableEffect(endpoint,active) { onDispose { client.dispatcher.cancelAll();client.connectionPool.evictAll() } }
    LaunchedEffect(endpoint,active) {
        if(endpoint==null) { label="Unsupported camera address.";return@LaunchedEffect }
        if(!active) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            while(isActive) {
                try {
                    cameraResponse(client, address, camera.stream).use { response ->
                        if(!response.isSuccessful || response.header("Content-Type")?.startsWith("multipart/x-mixed-replace",true)!=true) throw ApiFailure("Camera is not an MJPEG stream.")
                        val input=response.body?.byteStream()?.buffered() ?: throw ApiFailure("No camera stream.")
                        var frames=0;var since=System.nanoTime();val frameGate=CameraFrameGate()
                        while(isActive) {
                            val bytes=nextJpeg(input);ensureActive()
                            // Keep consuming the stream to avoid accumulating latency, but bound decoding/rendering.
                            if(!frameGate.accept(System.nanoTime())) continue
                            val opts=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
                            if(opts.outWidth !in 1..8192 || opts.outHeight !in 1..8192) throw ApiFailure("Unsupported camera resolution.")
                            opts.inJustDecodeBounds=false;opts.inSampleSize=maxOf(1,maxOf(opts.outWidth,opts.outHeight)/1024)
                            val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts) ?: throw ApiFailure("Invalid camera frame.")
                            frames++;val now=System.nanoTime();val fps=if(now-since>=1_000_000_000) (frames*1_000_000_000.0/(now-since)).toInt() else null
                            withContext(Dispatchers.Main) { bitmap=decoded;if(fps!=null) label="Live MJPEG • $fps fps" }
                            if(fps!=null) { frames=0;since=now }
                        }
                    }
                } catch(e: CancellationException) { throw e } catch(_: Exception) {
                    withContext(Dispatchers.Main) { bitmap=null;label="MJPEG unavailable. Reconnecting…" };delay(3000)
                }
            }
        }
    }
    bitmap?.let { Image(it.asImageBitmap(),"Live printer camera",Modifier.fillMaxWidth().aspectRatio(16f/9f)) }
        ?: Spacer(Modifier.fillMaxWidth().aspectRatio(16f/9f))
    Text(label, style = MaterialTheme.typography.bodySmall)
}
