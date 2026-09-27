package com.nozzleitall.desktop.camera

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.printer.CameraEndpoint
import com.nozzleitall.printer.MjpegReader
import com.nozzleitall.printer.PrinterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** What a live camera view shows right now. */
class LiveCameraState(val camera: CameraEndpoint?) {
    var image by mutableStateOf<ImageBitmap?>(null)
    var problem by mutableStateOf<String?>(null)
    var live by mutableStateOf(false)
}

/**
 * A printer's camera, live, for as long as the caller is on screen: the camera's H.264 video through ffmpeg when it has
 * one (full frame rate), otherwise its MJPEG stream, otherwise stills refreshed every second or so. Reconnects on its
 * own. [width] is the decoded picture width; small previews use less. Leaving the screen closes the stream at once.
 */
@Composable
fun rememberLiveCamera(entry: PrinterEntry, width: Int = 1280): LiveCameraState {
    val camera = entry.cameras.value.firstOrNull()
    val state = remember(entry, camera) { LiveCameraState(camera) }
    // The open stream, so leaving closes it immediately (a blocking socket read doesn't notice cancellation).
    val openStream = remember(entry, camera) { java.util.concurrent.atomic.AtomicReference<java.io.Closeable?>(null) }
    val ffmpeg = remember { FfmpegVideo.locate() }
    DisposableEffect(entry, camera) { onDispose { openStream.getAndSet(null)?.let { runCatching { it.close() } } } }
    LaunchedEffect(entry, camera, width) {
        var videoWorks = true // a video attempt that produced no frame falls back to MJPEG from then on
        while (isActive && camera != null) {
            val s = entry.session
            if (s == null || entry.status.value.state == PrinterState.OFFLINE) { delay(2_000); continue }
            if (camera.liveUrl != null || (videoWorks && camera.videoUrl != null && ffmpeg != null)) {
                try {
                    withContext(Dispatchers.IO) {
                        val video = if (videoWorks && camera.videoUrl != null && ffmpeg != null) runCatching { FfmpegVideo.start(ffmpeg, s.videoStream(camera), width) }.getOrNull() else null
                        val mjpeg = if (video == null) s.liveStream(camera) else null
                        val source: java.io.Closeable = video ?: mjpeg!!
                        source.use {
                            openStream.set(source)
                            val frames = mjpeg?.let { MjpegReader(it) }
                            var shown = 0
                            while (isActive) {
                                val frame = (video?.next() ?: frames?.next()) ?: break
                                shown++
                                val decoded = runCatching { org.jetbrains.skia.Image.makeFromEncoded(frame).toComposeImageBitmap() }.getOrNull() ?: continue
                                state.image = decoded; state.live = true; state.problem = null
                            }
                            if (video != null && shown == 0 && camera.liveUrl != null) videoWorks = false
                        }
                    }
                } catch (e: Exception) { if (isActive) state.problem = e.message ?: "Camera unavailable." }
                finally { openStream.set(null); state.live = false }
                delay(2_000)
            } else {
                try {
                    val bytes = withContext(Dispatchers.IO) { s.snapshot(camera) }
                    state.image = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap(); state.problem = null
                } catch (e: Exception) { state.problem = e.message ?: "Camera unavailable." }
                delay(if (entry.status.value.state.isActiveJob) 1_000 else 3_000)
            }
        }
    }
    return state
}
