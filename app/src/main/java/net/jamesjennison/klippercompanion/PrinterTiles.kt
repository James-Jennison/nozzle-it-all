package net.jamesjennison.klippercompanion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

data class PrinterTile(val address: String, val label: String, val status: String, val snapshot: PrinterSnapshot?, val camera: Camera?, val apiKey: String = "", val kind: PrinterKind = PrinterKind.GENERIC_KLIPPER)

fun ScreenState.connectedPrinterTiles(): List<PrinterTile> =
    (savedPrinters + profiles.map { it.address } + address).filter { it.isNotBlank() }.distinct().mapNotNull { saved ->
        val connection = if (saved == address && connected)
            PrinterConnection(true, snapshot?.state ?: "Connected", snapshot, catalog.cameras) else printerConnections[saved]
        val profile = profiles.firstOrNull { it.address == saved }
        val camera = if(profile?.cameraId.isNullOrBlank()) connection?.cameras?.firstOrNull()
            else connection?.cameras?.firstOrNull { it.id == profile?.cameraId }
        if (connection?.connected != true) null else PrinterTile(saved,
            profile?.label ?: saved, connection.state, connection.snapshot, camera, profile?.apiKey.orEmpty(), profile?.kind ?: PrinterKind.GENERIC_KLIPPER)
    }
// M7's own exit criteria require unverified-by-hardware status to be visible in the app itself,
// not just in docs - both BAMBU_LAB (M8c) and PRUSA_LINK (P26) are real, wired code the owner has
// no matching hardware to physically verify against. See FEATURE_PARITY_ROADMAP.md's M7 section.
val PrinterKind.unverifiedOnRealHardware: Boolean get() = this == PrinterKind.BAMBU_LAB || this == PrinterKind.PRUSA_LINK

@Composable
fun PrinterTiles(tiles: List<PrinterTile>, enabled: Boolean, open: (String) -> Unit, cameraContent: @Composable (PrinterTile) -> Unit = { PrinterTileCamera(it) }) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEach { tile -> key(tile.address) {
            val snapshot = tile.snapshot
            val displayState = snapshot?.displayState ?: tile.status
            val activeFilename = snapshot?.activeFilename?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            val printing = displayState in setOf("printing", "paused")
            val error = displayState in setOf("error", "not ready")
            val dotColor = when { error -> MaterialTheme.colorScheme.error; printing -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
            fun temperature(value: Double?) = value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.0f°C", it) } ?: "—"
            val shape = RoundedCornerShape(20.dp)
            // A printing tile gets the same tinted-gradient treatment as the printer detail
            // hero card, so "something is actively happening here" reads at a glance across a
            // list of several printers, not just once you open one.
            val background = if (printing) Modifier.background(
                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), MaterialTheme.colorScheme.surface)), shape
            ) else Modifier.background(MaterialTheme.colorScheme.surface, shape)
            val border = if (printing) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), shape) else Modifier
            Box(Modifier.fillMaxWidth().clip(shape).then(background).then(border)
                .clickable(enabled = enabled) { open(tile.address) }.testTag("printer-tile:${tile.address}")) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))) { cameraContent(tile) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(tile.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(7.dp).background(dotColor, CircleShape))
                            Text(displayState.replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.labelMedium, color = dotColor)
                        }
                        if (tile.kind.unverifiedOnRealHardware) Text("Not verified on real hardware yet",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.testTag("unverified-hardware:${tile.address}"))
                        if (activeFilename != null) {
                            Text(activeFilename, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator(progress = { snapshot?.activeProgress ?: 0f }, modifier = Modifier.weight(1f).clip(CircleShape))
                                Text("${((snapshot?.activeProgress ?: 0f) * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            Text("No active file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                CompanionIcon(CompanionSymbol.NOZZLE, Modifier.size(16.dp), color = MaterialTheme.colorScheme.tertiary)
                                Text(temperature(snapshot?.nozzle), style = MaterialTheme.typography.bodySmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                CompanionIcon(CompanionSymbol.BED, Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary)
                                Text(temperature(snapshot?.bed), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        } }
    }
}

@Composable
internal fun PrinterTileCamera(tile: PrinterTile) {
    val host = LocalView.current
    var visible by remember(tile.address) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow(clipBounds = false)
        val origin = IntArray(2)
        host.getLocationInWindow(origin)
        visible = bounds.width > 0 && bounds.height > 0 &&
            bounds.bottom > origin[1] && bounds.top < origin[1] + host.height &&
            bounds.right > origin[0] && bounds.left < origin[0] + host.width
    }) {
        val camera = tile.camera
        val fallbackTextStyle = MaterialTheme.typography.labelSmall
        if(!visible) Spacer(Modifier.fillMaxSize())
        else if(camera == null) {
            Spacer(Modifier.fillMaxSize())
            Text("Camera unavailable", style = fallbackTextStyle, modifier = Modifier.align(Alignment.BottomCenter))
        } else when {
            // A Bambu chamber camera carries the loopback origin this app re-serves it on; a
            // Moonraker camera leaves that blank and renders against the printer's own address.
            camera.stream.isBlank() -> SnapshotCamera(camera.address.ifBlank { tile.address }, camera, modifier = Modifier.fillMaxSize(), showLabel = false, apiKey = tile.apiKey)
            camera.service == "webrtc-camerastreamer" -> LiveCamera(camera.address.ifBlank { tile.address }, camera, modifier = Modifier.fillMaxSize(), showLabel = false)
            camera.service in setOf("mjpegstreamer", "mjpegstreamer-adaptive", "bambu-chamber") -> MjpegCamera(camera.address.ifBlank { tile.address }, camera, modifier = Modifier.fillMaxSize(), showLabel = false)
            else -> {
                Spacer(Modifier.fillMaxSize())
                Text("Unsupported camera format", style = fallbackTextStyle, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}
