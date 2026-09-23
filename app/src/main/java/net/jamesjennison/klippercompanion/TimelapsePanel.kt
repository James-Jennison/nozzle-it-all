package net.jamesjennison.klippercompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// canRender (Phase 7, §16): the caller's own transport-level capability
// (PrinterCapabilities.supportsTimelapseTrigger) - false by default, matching BedMeshPanel's own
// canCalibrate convention, so a caller that only wants read/playback (this panel's original
// purpose) gets exactly today's behavior.
@Composable fun TimelapsePanel(address: String, connected: Boolean, close: () -> Unit, factory: (String) -> TimelapseReader = { Moonraker(it) }, canRender: Boolean = false) {
    var clips by remember(address) { mutableStateOf<List<TimelapseClip>?>(null) }
    var note by remember(address) { mutableStateOf("Loading timelapses…") }
    var epoch by remember(address) { mutableIntStateOf(0) }
    var playing by remember(address) { mutableStateOf<TimelapseClip?>(null) }
    var downloadNote by remember(address) { mutableStateOf("") }
    var rendering by remember(address) { mutableStateOf(false) }
    var renderConfirm by remember(address) { mutableStateOf(false) }
    var renderNote by remember(address) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val contentResolver = LocalContext.current.contentResolver
    fun load() {
        if (!connected) { clips = null; note = "Disconnected. Connect to browse timelapses."; return }
        val ticket = ++epoch
        note = "Loading timelapses…"
        scope.launch {
            val reader = try { factory(address) } catch (_: Exception) { if (ticket == epoch) note = "Timelapse unavailable."; return@launch }
            try {
                val result = withContext(Dispatchers.IO) { reader.timelapses() }
                if (ticket != epoch) return@launch
                clips = result
                note = if (result.isEmpty()) "No timelapse videos found. Requires the moonraker-timelapse component to be installed and enabled." else ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == epoch) { clips = null; note = e.message ?: "Timelapse unavailable." } }
            finally { reader.close() }
        }
    }
    LaunchedEffect(address, connected, lifecycle) { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) load() }

    var pendingDownload by remember(address) { mutableStateOf<TimelapseClip?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val clip = pendingDownload; pendingDownload = null
        if (uri != null && clip != null) {
            downloadNote = "Downloading ${clip.path}…"
            scope.launch {
                val reader = try { factory(address) } catch (e: Exception) { downloadNote = e.message ?: "Timelapse unavailable."; return@launch }
                try {
                    val target = withContext(Dispatchers.IO) { reader.timelapseVideoUrl(clip.path) }
                    withContext(Dispatchers.IO) { TimelapseDownload.download(target, uri, contentResolver) }
                    downloadNote = "Saved ${clip.path}."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { downloadNote = e.message ?: "Download failed." }
                finally { reader.close() }
            }
        }
    }

    playing?.let { clip -> TimelapsePlayer(address, clip, factory) { playing = null } }

    AlertDialog(onDismissRequest = close, title = { Text("Timelapses") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: $address")
            Text("Tap a clip to play it here; the printer serves it with seeking support, so nothing downloads just to watch.")
            if (note.isNotBlank()) Text(note)
            if (downloadNote.isNotBlank()) Text(downloadNote, style = MaterialTheme.typography.bodySmall)
            val list = clips
            if (!list.isNullOrEmpty()) {
                val totalBytes = list.sumOf { it.size ?: 0L }
                Text("${list.size} ${if (list.size == 1) "clip" else "clips"} · ${formatFileSize(totalBytes)} on the printer", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // clips already arrive newest-first (Timelapses.parse), so grouping preserves that order.
                    var lastDay = ""
                    list.forEach { clip ->
                        val day = dayLabel(clip.modified ?: 0.0)
                        if (day != lastDay) { lastDay = day; item(key = "day:$day") { Text(day, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) } }
                        item(key = clip.path) {
                            TimelapseRow(address, clip, factory, onPlay = { playing = clip },
                                onDownload = { pendingDownload = clip; save.launch(clip.path.substringAfterLast('/')) })
                        }
                    }
                }
            }
            TextButton({ load() }, enabled = connected, modifier = Modifier.testTag("refresh-timelapses")) { Text("Refresh") }
            // Real trigger (Phase 7, §16): a manual moonraker-timelapse render, gated on the
            // transport-level capability - the component itself may still not be installed on a
            // given printer, which surfaces as a real error from renderTimelapse() rather than
            // being pre-checked (unlike bed-mesh calibration, there's no cheap live "is this
            // installed" query to make first; Moonraker just 404s the endpoint).
            if (canRender) {
                Button({ renderConfirm = true }, enabled = connected && !rendering, modifier = Modifier.testTag("render-timelapse")) {
                    Text(if (rendering) "Rendering…" else "Render now")
                }
                if (renderNote.isNotBlank()) Text(renderNote, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("render-timelapse-note"))
            }
        }
    })
    if (renderConfirm) AlertDialog(onDismissRequest = { renderConfirm = false }, title = { Text("Render timelapse now?") },
        text = { Text("Asks $address to render whatever frames it has already captured into a video now, rather than waiting for the current print to finish.") },
        confirmButton = { Button({
            renderConfirm = false; rendering = true; renderNote = ""
            scope.launch {
                val reader = try { factory(address) } catch (e: Exception) { renderNote = e.message ?: "Timelapse unavailable."; rendering = false; return@launch }
                try {
                    val result = withContext(Dispatchers.IO) { reader.renderTimelapse() }
                    renderNote = result.message.ifBlank { "Status: ${result.status}." }
                    if (result.succeeded) load()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { renderNote = e.message ?: "Could not render the timelapse." }
                finally { rendering = false; reader.close() }
            }
        }, modifier = Modifier.testTag("confirm-render-timelapse")) { Text("Render") } },
        dismissButton = { TextButton({ renderConfirm = false }) { Text("Cancel") } })
}

@Composable private fun TimelapseRow(address: String, clip: TimelapseClip, factory: (String) -> TimelapseReader, onPlay: () -> Unit, onDownload: () -> Unit) {
    // Matches the Card(Modifier.fillMaxWidth()) treatment Files/macro/saved-printer rows already
    // get elsewhere - this row previously had no card background at all.
    Card(Modifier.fillMaxWidth().testTag("timelapse-clip:${clip.path}")) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimelapsePoster(address, clip, factory)
            Column(Modifier.weight(1f)) {
                Text(clip.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                val time = clip.modified?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date((it * 1000).toLong())) } ?: "Unknown time"
                Text("${formatFileSize(clip.size)} · $time", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onDownload, Modifier.testTag("download-timelapse:${clip.path}")) { Text("Save") }
        }
    }
}

/** The poster frame moonraker-timelapse writes alongside each clip, or a plain placeholder without one. */
@Composable private fun TimelapsePoster(address: String, clip: TimelapseClip, factory: (String) -> TimelapseReader) {
    var bitmap by remember(address, clip.path) { mutableStateOf<Bitmap?>(null) }
    val posterPath = clip.posterPath
    LaunchedEffect(address, posterPath) {
        if (posterPath == null) return@LaunchedEffect
        val reader = try { factory(address) } catch (_: Exception) { return@LaunchedEffect }
        try {
            val bytes = withContext(Dispatchers.IO) { reader.timelapseThumbnail(posterPath) }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth !in 1..8192 || options.outHeight !in 1..8192) return@LaunchedEffect
            options.inJustDecodeBounds = false; options.inSampleSize = maxOf(1, maxOf(options.outWidth, options.outHeight) / 256)
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: Exception) { /* No poster; the placeholder box below stays. */ }
        finally { reader.close() }
    }
    Box(Modifier.size(72.dp, 48.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "Timelapse poster frame", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: CompanionIcon(CompanionSymbol.CAMERA, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun TimelapsePlayer(address: String, clip: TimelapseClip, factory: (String) -> TimelapseReader, close: () -> Unit) {
    var target by remember(address, clip.path) { mutableStateOf<TimelapseVideoUrl?>(null) }
    var note by remember(address, clip.path) { mutableStateOf("Loading video…") }
    LaunchedEffect(address, clip.path) {
        val reader = try { factory(address) } catch (e: Exception) { note = e.message ?: "Timelapse unavailable."; return@LaunchedEffect }
        try { target = withContext(Dispatchers.IO) { reader.timelapseVideoUrl(clip.path) }; note = "" }
        catch (e: Exception) { note = e.message ?: "Timelapse unavailable." }
        finally { reader.close() }
    }
    AlertDialog(onDismissRequest = close, title = { Text(clip.path.substringAfterLast('/')) }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (note.isNotBlank()) Text(note)
            target?.let { url ->
                KilnFrame {
                    AndroidView(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(Uri.parse(url.url), url.headers)
                            setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                            setOnPreparedListener { it.isLooping = false; start() }
                            setOnErrorListener { _, _, _ -> note = "Playback failed. The video may still be finalizing on the printer."; true }
                        }
                    }, onRelease = { it.stopPlayback() })
                }
            }
        }
    })
}

private object TimelapseDownload {
    // Timelapse videos routinely exceed the 256 MiB cap this app applies to gcode transfers
    // (GcodePreview.MAX_BYTES); this is a much larger, separate bound for the same reason those
    // caps exist at all - an unbounded write from an untrusted/misbehaving server is unsafe.
    private const val MAX_BYTES = 4L * 1024 * 1024 * 1024
    fun download(target: TimelapseVideoUrl, destination: Uri, resolver: android.content.ContentResolver) {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        try {
            val request = Request.Builder().url(target.url).apply { target.headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ApiFailure("Download failed (HTTP ${response.code}).")
                val body = response.body ?: throw ApiFailure("Empty download.")
                val out = resolver.openOutputStream(destination, "wt") ?: throw ApiFailure("Cannot write the chosen document.")
                out.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(65536); var total = 0L
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            total += n; if (total > MAX_BYTES) throw ApiFailure("Video exceeds the supported download size.")
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
        } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
    }
}
