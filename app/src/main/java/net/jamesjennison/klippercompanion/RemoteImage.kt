package net.jamesjennison.klippercompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// Small, deliberately restrictive image loader for Discover thumbnails: https only, no redirects to other schemes,
// bounded size, downsampled decode, in-memory LRU cache. A failed load simply shows a placeholder.
object RemoteImages {
    private const val MAX_BYTES = 6L * 1024 * 1024
    private val http = OkHttpClient.Builder().followRedirects(true).followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }

    fun cached(url: String): Bitmap? = cache.get(url)

    /** Returns null for anything that is not a small https image. */
    fun load(url: String, targetPx: Int): Bitmap? {
        cache.get(url)?.let { return it }
        if (!url.startsWith("https://")) return null
        return try {
            val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = r.body ?: return null
                if (body.contentLength() > MAX_BYTES) return null
                val src = body.source(); val buf = okio.Buffer(); var total = 0L
                while (true) { val n = src.read(buf, 8192); if (n < 0) break; total += n; if (total > MAX_BYTES) return null }
                buf.readByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 40_000_000L) return null
            var sample = 1; while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.also { cache.put(url, it) }
        } catch (e: Exception) { null }
    }
}

@Composable fun RemoteImage(url: String?, modifier: Modifier = Modifier, targetPx: Int = 400) {
    var bitmap by remember(url) { mutableStateOf(url?.let { RemoteImages.cached(it) }) }
    LaunchedEffect(url) { if (url != null && bitmap == null) bitmap = withContext(Dispatchers.IO) { RemoteImages.load(url, targetPx) } }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
    }
}
