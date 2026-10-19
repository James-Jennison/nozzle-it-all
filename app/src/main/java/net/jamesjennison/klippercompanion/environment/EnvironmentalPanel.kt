package net.jamesjennison.klippercompanion.environment

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nozzleitall.environment.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject

@Composable fun EnvironmentalPanel() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember { EnvironmentalCoordinator(File(context.filesDir, "environment/control.json"),
        EnvironmentalHistory(SqlStoreFactory { AndroidEnvironmentalStore(File(context.filesDir, "environment/history.db")) }),
        { AndroidBleTransport(context.applicationContext) }, platformIdentity = "android/${Build.MODEL}/${Build.FINGERPRINT}") }
    val view by controller.view.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("Environmental sensor") }
    var rows by remember { mutableStateOf<List<String>>(emptyList()) }
    var deleting by remember { mutableStateOf(false) }
    var equipment by remember { mutableStateOf("Room") }
    var position by remember { mutableStateOf("Shelf") }
    var offset by remember { mutableStateOf("0.0") }
    fun action(block: () -> Unit) { if (!busy) { busy = true; problem = null; scope.launch { try { withContext(Dispatchers.IO) { block() } } catch (e: QualificationUnavailableException) { problem = e.message } catch (_: Exception) { problem = "Environmental operation unavailable — check radio, permission or storage. No automatic retry." } finally { busy = false } } } }
    fun start(kind: String) { action { if (kind == "discover") controller.discover() else controller.start() } }
    val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val kind = pending; pending = null
        if (kind != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && permissions.all { results[it] == true || context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) start(kind)
        else if (kind != null) problem = "BLE permission denied or operation cancelled — no collection. Retry explicitly after granting permission."
    }
    fun authorize(kind: String) {
        if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) start(kind)
        else { pending = kind; request.launch(permissions) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        uri?.let { action { context.contentResolver.openOutputStream(it, "wt")!!.bufferedWriter().use { writer -> controller.export { line -> writer.write(line); writer.newLine() } } } }
    }
    DisposableEffect(lifecycle, controller) {
        controller.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) controller.foreground(true)
            if (event == Lifecycle.Event.ON_STOP) { pending = null; controller.foreground(false) }
        }
        lifecycle.addObserver(observer)
        onDispose { pending = null; lifecycle.removeObserver(observer); controller.closeAsync() }
    }
    Card(Modifier.fillMaxWidth().testTag("environment-panel")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Environmental monitoring (optional)", style = MaterialTheme.typography.titleMedium)
            Text(view.status)
            EnvironmentalProviders.ble.forEach { Text(it.name + " · active scan", style = MaterialTheme.typography.bodySmall) }
            Text("Nearby-device permission is for scanning only; no CONNECT permission, GATT connection or pairing is used.", style = MaterialTheme.typography.bodySmall)
            Text("Active BLE scanning uses battery/radio. Local diagnostic receptions only; physical measurement age unknown. No pairing, Govee unbinding, cloud or background service. Leaving this panel or app stops collection.", style = MaterialTheme.typography.bodySmall)
            Text("Cadence declarations are not runtime-enforced; receptions never prove physical freshness. Assigned deployment needs owner-reviewed physical evidence; no self-qualification workflow is provided. OS/user/device/radio identity changes require requalification; generic radio IDs are not migrated.", style = MaterialTheme.typography.bodySmall)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (view.state == EnvironmentalState.DISABLED) Button({ action { controller.enable() } }, enabled = !busy, modifier = Modifier.testTag("environment-enable")) { Text("Enable") }
            if (view.state == EnvironmentalState.ENABLED_IDLE) {
                OutlinedButton({ authorize("discover") }, enabled = !busy && pending == null, modifier = Modifier.testTag("environment-discover")) { Text("Discover sensors (45 seconds)") }
                OutlinedButton({ authorize("collect") }, enabled = !busy && pending == null, modifier = Modifier.testTag("environment-start")) { Text("Start diagnostics") }
            }
            if (view.state != EnvironmentalState.DISABLED || pending != null) {
                OutlinedButton({ pending = null; action { controller.stop() } }, enabled = !busy, modifier = Modifier.testTag("environment-stop")) { Text("Stop / cancel") }
                OutlinedButton({ pending = null; action { controller.stop(disable = true) } }, enabled = !busy) { Text("Disable") }
            }
            if (view.candidates.isNotEmpty()) {
                OutlinedTextField(name, { name = it.take(120) }, label = { Text("Sensor label") })
                view.candidates.forEachIndexed { i, candidate ->
                    Text("${candidate.model} · ${candidate.address} · candidate ${i + 1} · firmware/calibration/thermal limits unqualified")
                    OutlinedButton({ action { controller.save(candidate, name) } }, enabled = !busy && name.isNotBlank()) { Text("Save only") }
                }
            }
            view.latest.values.forEach { o ->
                Text(o.readings.joinToString(" · ") { "${it.channel.name}: ${it.value} ${it.channel.unit}" })
                Text("Reception ${java.time.Instant.ofEpochMilli(o.observedAt)} — measurement age unknown", style = MaterialTheme.typography.bodySmall)
            }
            if (view.dropped > 0) Text("Queue overflow counter for this monitor: ${view.dropped} — coverage incomplete", color = MaterialTheme.colorScheme.error)
            if (view.recordedDrops > 0) Text("History records ${view.recordedDrops} dropped reports; other gaps unknown", color = MaterialTheme.colorScheme.error)
            OutlinedButton({ action { rows = controller.loadHistory(); controller.savedBindings() } }, enabled = !busy, modifier = Modifier.testTag("environment-history")) { Text("Open local history") }
            OutlinedButton({ action { controller.retention() } }, enabled = !busy) { Text("Apply 30-day retention") }
            OutlinedButton({ export.launch("environment-history.ndjson") }, enabled = !busy) { Text("Export history") }
            OutlinedButton({ deleting = true }, enabled = !busy) { Text("Delete environmental data…") }
            if (deleting) {
                Text("Delete only environmental history and settings? External exports remain. Failed cleanup requires explicit resume.")
                Button({ pending = null; action { controller.deleteAll(); rows = emptyList(); deleting = false } }, enabled = !busy) { Text("Confirm delete / resume") }
                TextButton({ deleting = false }) { Text("Keep data") }
            }
            view.bindings.filterNot { it.archived }.forEach { b ->
                Text("${b.model} — diagnostic, unqualified")
                Text("${b.address} · ${b.protocol} — confirm physical identity", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(equipment, { equipment = it.take(120) }, label = { Text("Equipment label (draft)") })
                OutlinedTextField(position, { position = it.take(120) }, label = { Text("Position label (draft)") })
                TextButton({ action { controller.configureRoom(b, equipment, position) } }, enabled = !busy && equipment.isNotBlank() && position.isNotBlank()) { Text("Save draft assignment only") }
                Text("Unqualified assignments block Start; thermal/firmware evidence required for deployment.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(offset, { offset = it.take(12) }, label = { Text("Humidity calibration offset (%RH)") })
                TextButton({ action { controller.calibrate(b, Channel.HUMIDITY, offset.toDouble()) } }, enabled = !busy && offset.toDoubleOrNull()?.isFinite() == true) { Text("Save RH calibration") }
                TextButton({ action { controller.archive(b) } }, enabled = !busy) { Text("Archive binding") }
            }
            rows.forEach { raw -> val j = JSONObject(raw); Text("${java.time.Instant.ofEpochMilli(j.getLong("observedAt"))} · ${j.getJSONArray("readings")} · age unknown", style = MaterialTheme.typography.bodySmall) }
            if (rows.size == 500) OutlinedButton({ val last = JSONObject(rows.last()); action { rows = controller.loadHistory(last.getLong("observedAt"), last.getString("eventId")) } }, enabled = !busy) { Text("Older 500") }
        }
    }
}
