package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun TimelapsePanel(address: String, connected: Boolean, close: () -> Unit, factory: (String) -> TimelapseReader = { Moonraker(it) }) {
    var videos by remember(address) { mutableStateOf<List<FileInfo>?>(null) }
    var note by remember(address) { mutableStateOf("Loading timelapses…") }
    var epoch by remember(address) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun load() {
        if (!connected) { videos = null; note = "Disconnected. Connect to browse timelapses."; return }
        val ticket = ++epoch
        note = "Loading timelapses…"
        scope.launch {
            val reader = try { factory(address) } catch (_: Exception) { if (ticket == epoch) note = "Timelapse unavailable."; return@launch }
            try {
                val result = withContext(Dispatchers.IO) { reader.timelapses() }
                if (ticket != epoch) return@launch
                videos = result
                note = if (result.isEmpty()) "No timelapse videos found. Requires the moonraker-timelapse component to be installed and enabled." else ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == epoch) { videos = null; note = e.message ?: "Timelapse unavailable." } }
            finally { reader.close() }
        }
    }
    LaunchedEffect(address, connected, lifecycle) { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) load() }
    AlertDialog(onDismissRequest = close, title = { Text("Timelapses") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: $address")
            Text("Read-only browsing of finished timelapse videos. Capture and playback are not started from here.")
            if (note.isNotBlank()) Text(note)
            val list = videos
            if (!list.isNullOrEmpty()) {
                val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list) { video ->
                        Column {
                            Text(video.path, style = MaterialTheme.typography.bodyLarge)
                            val size = video.size?.let { "%.1f MB".format(Locale.US, it / 1_048_576.0) } ?: "Unknown size"
                            val modified = video.modified?.let { formatter.format(Date((it * 1000).toLong())) } ?: "Unknown date"
                            Text("$size · $modified", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            TextButton({ load() }, enabled = connected, modifier = Modifier.testTag("refresh-timelapses")) { Text("Refresh") }
        }
    })
}
