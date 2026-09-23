package net.jamesjennison.klippercompanion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.util.Locale

// execute/generation (Phase 7, §16): both null by default, matching ConfigFilePanel's own
// optional-mutation convention (writerFactory/execute only passed when
// LIVE_HEATER_FAN_CONTROLS_ENABLED) - a caller that only wants the read-only mesh view (this
// panel's original, still-real purpose) can omit them and gets exactly today's behavior.
@Composable fun BedMeshPanel(address: String, connected: Boolean, close: () -> Unit, factory: (String) -> MeshReader = { Moonraker(it) },
    execute: ((PrinterCommand, Int) -> Unit)? = null, generation: Int = 0, canCalibrate: Boolean = false, printReady: Boolean = false) {
    var status by remember(address) { mutableStateOf<BedMeshStatus?>(null) }
    var note by remember(address) { mutableStateOf("Loading bed mesh…") }
    var epoch by remember(address) { mutableIntStateOf(0) }
    // Real per-printer signal (MeshReader.supportsBedMeshCalibration - printer/objects/list),
    // not assumed from canCalibrate (the caller's own transport-level capability) alone - a
    // Moonraker/Klipper printer with no `[bed_mesh]` configured genuinely has no
    // BED_MESH_CALIBRATE command to send, so the button stays hidden for it too (§20, no dead
    // buttons), not just for a non-Klipper vendor.
    var calibrationSupported by remember(address) { mutableStateOf(false) }
    var calibrating by remember(address) { mutableStateOf(false) }
    var calibrateConfirm by remember(address) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun load() {
        if (!connected) { status = null; note = "Disconnected. Connect to read the bed mesh."; return }
        val ticket = ++epoch
        note = "Loading bed mesh…"
        scope.launch {
            val reader = try { factory(address) } catch (_: Exception) { if (ticket == epoch) note = "Bed mesh unavailable."; return@launch }
            try {
                val result = withContext(Dispatchers.IO) { reader.meshStatus() }
                if (ticket != epoch) return@launch
                status = result
                note = if (result.hasMesh) "Loaded." else "No active bed mesh reported by the printer."
                if (canCalibrate) calibrationSupported = try { withContext(Dispatchers.IO) { reader.supportsBedMeshCalibration() } } catch (_: Exception) { false }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == epoch) { status = null; note = e.message ?: "Bed mesh unavailable." } }
            finally { reader.close() }
        }
    }
    LaunchedEffect(address, connected, lifecycle) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) load()
    }
    AlertDialog(onDismissRequest = close, title = { Text("Bed mesh") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: $address")
            Text(if (canCalibrate && calibrationSupported) "Shows the printer's last-calibrated bed mesh, and can trigger a new calibration below."
                else "Read-only view of the printer's last-calibrated bed mesh. Calibration is not started from here.")
            Text(note, modifier = Modifier.testTag("mesh-status"))
            val mesh = status
            if (mesh != null && mesh.hasMesh) {
                val values = mesh.flatValues
                val low = values.min(); val high = values.max()
                Text(if (mesh.profileName.isNotBlank()) "Profile: ${mesh.profileName}" else "Profile: (unnamed)")
                Text("Z range ${"%.3f".format(Locale.ROOT, low)} to ${"%.3f".format(Locale.ROOT, high)} mm across ${mesh.probedMatrix.size}×${mesh.probedMatrix.firstOrNull()?.size ?: 0} probed points")
                var view3d by remember(address) { mutableStateOf(true) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(view3d, { view3d = true }, label = { Text("3D surface") }, modifier = Modifier.testTag("mesh-view-3d"))
                    FilterChip(!view3d, { view3d = false }, label = { Text("Heatmap") }, modifier = Modifier.testTag("mesh-view-heatmap"))
                }
                if (view3d) {
                    KilnFrame { BedMesh3DView(mesh) }
                } else {
                    KilnFrame {
                        Canvas(Modifier.fillMaxWidth().height(220.dp).testTag("mesh-canvas")
                            .semantics { contentDescription = "Bed mesh heatmap, ${mesh.probedMatrix.size} by ${mesh.probedMatrix.firstOrNull()?.size ?: 0} points" }) {
                            val rows = mesh.probedMatrix.size; val cols = mesh.probedMatrix.firstOrNull()?.size ?: 0
                            if (rows > 0 && cols > 0) {
                                val cellWidth = size.width / cols; val cellHeight = size.height / rows
                                val span = (high - low).takeIf { it > 0.0 } ?: 1.0
                                mesh.probedMatrix.forEachIndexed { r, row ->
                                    row.forEachIndexed { c, z ->
                                        val t = ((z - low) / span).coerceIn(0.0, 1.0).toFloat()
                                        val color = lerp(Color(0xFF3B6FE0), Color(0xFFE0473B), t)
                                        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(c * cellWidth, (rows - 1 - r) * cellHeight), size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight))
                                    }
                                }
                            }
                        }
                    }
                    Text("Blue = lower probed Z, red = higher, scaled to this mesh's own range only.", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton({ load() }, enabled = connected, modifier = Modifier.testTag("refresh-mesh")) { Text("Refresh") }
            // Real trigger (Phase 7, §16): BED_MESH_CALIBRATE, gated on the transport-level
            // capability (canCalibrate), the printer's own live confirmation it's actually
            // configured (calibrationSupported), an idle/ready printer (printReady, the same real
            // gate jogging/heating use), and a confirm step - calibration physically probes the
            // bed and takes real minutes, worth the same one-tap-away-from-accidental protection
            // heater/LED changes already get.
            if (canCalibrate && calibrationSupported) {
                Button({ calibrateConfirm = true }, enabled = connected && printReady && execute != null && !calibrating, modifier = Modifier.testTag("calibrate-mesh")) {
                    Text(if (calibrating) "Calibrating…" else "Calibrate now")
                }
            }
        }
    })
    if (calibrateConfirm) AlertDialog(onDismissRequest = { calibrateConfirm = false }, title = { Text("Calibrate bed mesh?") },
        text = { Text("Sends BED_MESH_CALIBRATE to $address. The printer will probe the bed - clear it first and stay nearby.") },
        confirmButton = { Button({
            calibrateConfirm = false; calibrating = true
            execute?.invoke(PrinterCommand("Calibrate bed mesh", "printer/gcode/script", mapOf("script" to "BED_MESH_CALIBRATE")), generation)
            calibrating = false
        }, modifier = Modifier.testTag("confirm-calibrate-mesh")) { Text("Calibrate") } },
        dismissButton = { TextButton({ calibrateConfirm = false }) { Text("Cancel") } })
}
