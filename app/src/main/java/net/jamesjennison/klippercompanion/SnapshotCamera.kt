package net.jamesjennison.klippercompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable
fun SnapshotCamera(address: String, camera: Camera, modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f), showLabel: Boolean = true, apiKey: String = "") {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_START) active = true
            if(event == Lifecycle.Event.ON_STOP) active = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val api = remember(address, apiKey) { Moonraker(address, apiKey) }
    var bitmap by remember(address, camera, active) { mutableStateOf<Bitmap?>(null) }
    var label by remember(address, camera, active) { mutableStateOf("Loading camera…") }
    DisposableEffect(api, camera, active) { onDispose { api.close() } }
    LaunchedEffect(api, camera, active) {
        if(!active) return@LaunchedEffect
        while(isActive) {
            try {
                val image = withContext(Dispatchers.IO) {
                    val bytes = api.image(camera)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    if(options.outWidth !in 1..8192 || options.outHeight !in 1..8192) throw ApiFailure("Unsupported camera image.")
                    options.inJustDecodeBounds = false
                    options.inSampleSize = maxOf(1, maxOf(options.outWidth, options.outHeight) / 512)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw ApiFailure("Invalid camera image.")
                }
                ensureActive(); bitmap = image; label = "Refreshing snapshots"
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { bitmap = null; label = "Camera unavailable. Retrying…" }
            delay(2_000)
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), "Current printer camera", modifier, contentScale = ContentScale.Crop) }
        ?: Spacer(modifier)
    if(showLabel) Text(label, style = MaterialTheme.typography.bodySmall)
}
