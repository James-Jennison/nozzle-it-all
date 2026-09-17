package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

data class PrinterTile(val address: String, val label: String, val status: String, val snapshot: PrinterSnapshot?, val camera: Camera?)

fun ScreenState.connectedPrinterTiles(): List<PrinterTile> =
    (savedPrinters + profiles.map { it.address } + address).filter { it.isNotBlank() }.distinct().mapNotNull { saved ->
        val connection = if (saved == address && connected)
            PrinterConnection(true, snapshot?.state ?: "Connected", snapshot, catalog.cameras) else printerConnections[saved]
        val profile = profiles.firstOrNull { it.address == saved }
        val camera = if(profile?.cameraId.isNullOrBlank()) connection?.cameras?.firstOrNull()
            else connection?.cameras?.firstOrNull { it.id == profile?.cameraId }
        if (connection?.connected != true) null else PrinterTile(saved,
            profile?.label ?: saved, connection.state, connection.snapshot, camera)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrinterTiles(tiles: List<PrinterTile>, enabled: Boolean, open: (String) -> Unit, cameraContent: @Composable (PrinterTile) -> Unit = { PrinterTileCamera(it) }) {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (fontScale > 1.3f || maxWidth < 300.dp) 1 else if (maxWidth >= 660.dp) 3 else 2
        val width = (maxWidth - 12.dp * (columns - 1)) / columns
        // Measure unexpanded content so rows can shrink as well as grow.
        val naturalHeights = remember(width, density.density, fontScale, tiles.map { it.address }) { mutableStateMapOf<String, Int>() }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(columns).forEach { row ->
                val rowHeight = row.maxOf { naturalHeights[it.address] ?: 0 }
                // Weights share integer pixels without accidentally wrapping a peer.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile -> key(tile.address) {
                Card(onClick = { open(tile.address) }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = with(density) { rowHeight.toDp() }).testTag("printer-tile:${tile.address}")) {
                    Column(Modifier.wrapContentHeight(Alignment.Top).onSizeChanged { naturalHeights[tile.address] = it.height }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tile.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text((tile.snapshot?.displayState ?: tile.status).replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.labelLarge)
                        Box {
                            cameraContent(tile)
                            Box(Modifier.matchParentSize().clickable(enabled = enabled) { open(tile.address) })
                        }
                        val snapshot = tile.snapshot
                        Text(snapshot?.activeFilename?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "No active file",
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val progress = snapshot?.activeProgress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                        Text(progress?.let { "${(it * 100).toInt()}%" } ?: "Progress unavailable", style = MaterialTheme.typography.labelLarge)
                        fun temperature(value: Double?) = value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.0f°C", it) } ?: "—"
                        Text("Nozzle ${temperature(snapshot?.nozzle)} · Bed ${temperature(snapshot?.bed)}", style = MaterialTheme.typography.bodySmall)
                        Text("Open dashboard", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun PrinterTileCamera(tile: PrinterTile) {
    val host = LocalView.current
    var visible by remember(tile.address) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow(clipBounds = false)
        val origin = IntArray(2)
        host.getLocationInWindow(origin)
        visible = bounds.width > 0 && bounds.height > 0 &&
            bounds.bottom > origin[1] && bounds.top < origin[1] + host.height &&
            bounds.right > origin[0] && bounds.left < origin[0] + host.width
    }) {
        val camera = tile.camera
        if(!visible) Spacer(Modifier.fillMaxWidth().aspectRatio(16f/9f))
        else if(camera == null) {
            Spacer(Modifier.fillMaxWidth().aspectRatio(16f/9f))
            Text("Camera unavailable", style = MaterialTheme.typography.bodySmall)
        } else when {
            camera.stream.isBlank() -> SnapshotCamera(tile.address, camera)
            camera.service == "webrtc-camerastreamer" -> LiveCamera(tile.address, camera)
            camera.service in setOf("mjpegstreamer", "mjpegstreamer-adaptive") -> MjpegCamera(tile.address, camera)
            else -> {
                Spacer(Modifier.fillMaxWidth().aspectRatio(16f/9f))
                Text("Unsupported camera format", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
