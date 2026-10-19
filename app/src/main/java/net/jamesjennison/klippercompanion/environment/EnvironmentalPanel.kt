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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable fun EnvironmentalPanel() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember { (context.applicationContext as net.jamesjennison.klippercompanion.NozzleApp).environment }
    val goveeNames = remember { (context.applicationContext as net.jamesjennison.klippercompanion.NozzleApp).goveeNames }
    val importedNames by goveeNames.devices.collectAsState()
    var nameTarget by remember { mutableStateOf<Pair<String, Binding?>?>(null) }
    val view by controller.view.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    val candidateNames = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(view.candidates) { candidateNames.keys.retainAll(view.candidates.map { it.address }.toSet()) }
    var rows by remember { mutableStateOf<List<String>>(emptyList()) }
    var chartHistory by remember { mutableStateOf<List<ChartSample>>(emptyList()) }
    var historyMode by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val roomDrafts = remember { mutableStateMapOf<String, Pair<String, String>>() }
    var offset by remember { mutableStateOf("0.0") }
    var setup by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<String?>(null) }
    fun action(block: () -> Unit) { if (!busy && !stopping) { busy = true; problem = null; scope.launch { try { withContext(Dispatchers.IO) { block() } } catch (e: ChartHistoryException) { problem = e.message } catch (e: QualificationUnavailableException) { problem = e.message } catch (_: Exception) { problem = "Environmental operation unavailable — check radio, permission or storage. No automatic retry." } finally { busy = false } } } }
    fun start(kind: String) {
        if (!busy && !stopping) { busy = true; problem = null
            try {
                val work = controller.requestOperationAsync(if (kind == "discover") Purpose.DISCOVERY else Purpose.COLLECTION)
                scope.launch { try { work.join() } finally { busy = false } }
            } catch (_: Exception) { busy = false; problem = "Preparation unavailable or cancelled — retry explicitly" }
        }
    }
    fun stop(disable: Boolean = false) {
        if (!stopping) { stopping = true; problem = null; pending = null
            val cleanup = controller.requestStopAsync(disable = disable)
            scope.launch { try { cleanup.join() } finally { stopping = false } }
        }
    }
    val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val kind = pending; pending = null
        if (kind != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && permissions.all { results[it] == true || context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) start(kind)
        else if (kind != null) problem = "BLE permission denied or operation cancelled — no collection. Retry explicitly after granting permission."
    }
    fun authorize(kind: String) {
        if (busy || stopping || pending != null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
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
            if (event == Lifecycle.Event.ON_STOP) { pending = null; controller.releaseForegroundAsync() }
        }
        lifecycle.addObserver(observer)
        onDispose { pending = null; lifecycle.removeObserver(observer); controller.releaseForegroundAsync() }
    }
    val enabled = view.state != EnvironmentalState.DISABLED && view.state != EnvironmentalState.DISABLED_RECOVERY
    LaunchedEffect(controller, enabled) {
        if (enabled) {
            try {
                val (loaded, parsed) = withContext(Dispatchers.IO) {
                    controller.savedBindings()
                    val loaded = controller.loadHistory()
                    loaded to EnvironmentalCharts.history(loaded)
                }
                rows = loaded; chartHistory = parsed
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { problem = "Saved readings could not be loaded. Use Open local history to retry." }
        } else { chartHistory = emptyList(); rows = emptyList(); historyMode = false }
    }
    Column(Modifier.fillMaxWidth().testTag("environment-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Environment", style = MaterialTheme.typography.headlineMedium)
            Text(view.state.dashboardStatus(), style = MaterialTheme.typography.bodyMedium)
            view.nameDirectoryProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            view.dashboardNotice()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (historyMode) Text("Viewing saved history. Return to live readings for current updates.", color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view.state == EnvironmentalState.DISABLED) Button({ action { controller.enable() } }, enabled = !busy && !stopping, modifier = Modifier.testTag("environment-enable")) { Text("Enable") }
            if (view.state == EnvironmentalState.ENABLED_IDLE) {
                OutlinedButton({ authorize("discover") }, enabled = !busy && !stopping && pending == null, modifier = Modifier.testTag("environment-discover")) { Text("Discover sensors (45 seconds)") }
                Button({ historyMode = false; authorize("collect") }, enabled = !busy && !stopping && pending == null, modifier = Modifier.testTag("environment-start")) { Text("Start monitoring") }
            }
            if (view.state == EnvironmentalState.DISCOVERING || view.state == EnvironmentalState.COLLECTING || busy || stopping || pending != null) {
                OutlinedButton({ stop() }, enabled = !stopping, modifier = Modifier.testTag("environment-stop")) { Text("Stop / cancel") }
            }
            OutlinedButton({
                if (historyMode) historyMode = false else action { val loaded = controller.loadHistory(); chartHistory = EnvironmentalCharts.history(loaded); rows = loaded; historyMode = true }
            }, enabled = enabled && !busy && !stopping, modifier = Modifier.testTag("environment-history")) { Text(if (historyMode) "Return to live readings" else "Open local history") }
            OutlinedButton({ setup = !setup }) { Text(if (setup) "Close setup" else "Setup & data") }
            }
            EnvironmentalDashboard(view, chartHistory, historyMode, onDetails = { details = if (details == it.id) null else it.id })
            if (setup) {
            Text("Govee names & discovery", style = MaterialTheme.typography.titleMedium)
            Text("Import names once. Discovery reuses them and adds uniquely matched sensors automatically.", style = MaterialTheme.typography.bodySmall)
            if (enabled) GoveeNameImport(goveeNames)
            if (enabled) OutlinedButton({ stop(disable = true) }, enabled = !busy && !stopping) { Text("Disable monitoring") }
            if (view.candidates.isNotEmpty()) {
                view.candidates.filter { it.protocol == "govee-ble" && view.bindings.none { b -> !b.archived && b.address.equals(it.address, true) } }.forEach { candidate ->
                    key(candidate.address) {
                        val saved = view.bindings.firstOrNull { !it.archived && it.address.equals(candidate.address, true) }
                        val imported = importedNames.singleOrNull { it.matches(candidate) }?.name
                        val name = if (saved != null) view.sensorLabels[saved.sensorId] ?: candidate.defaultName else candidateNames[candidate.address] ?: imported ?: candidate.defaultName
                        GoveeDiscoveryCard(candidate, view.discovery[candidate.address], name, saved != null, busy || stopping,
                            onNameChange = { candidateNames[candidate.address] = it.take(120) }, onAdd = {
                            val selectedName = candidateNames[candidate.address] ?: importedNames.singleOrNull { it.matches(candidate) }?.name ?: candidate.defaultName
                            action { controller.save(candidate, selectedName) }
                        }, onChooseGoveeName = if (importedNames.any { it.model == candidate.model }) ({ nameTarget = candidate.address to null }) else null)
                    }
                }
            }
            if (view.dropped > 0) Text("Queue overflow counter for this monitor: ${view.dropped} — coverage incomplete", color = MaterialTheme.colorScheme.error)
            if (view.recordedDrops > 0) Text("History records ${view.recordedDrops} dropped reports; other gaps unknown", color = MaterialTheme.colorScheme.error)
            OutlinedButton({ action { controller.retention() } }, enabled = !busy && !stopping) { Text("Apply 30-day retention") }
            OutlinedButton({ export.launch("environment-history.ndjson") }, enabled = !busy && !stopping) { Text("Export history") }
            OutlinedButton({ deleting = true }, enabled = !busy && !stopping) { Text("Delete environmental data…") }
            if (deleting) {
                Text("Delete only environmental history and settings? External exports remain. Failed cleanup requires explicit resume.")
                Button({ pending = null; stopping = true; rows = emptyList(); chartHistory = emptyList(); candidateNames.clear(); roomDrafts.clear(); offset = "0.0"
                    val forgetGovee = goveeNames.requestForgetAsync()
                    val cleanup = controller.requestDeleteAsync()
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { try {
                        // Secret cleanup is independent of history cleanup outcome and survives navigation.
                        try { withContext(NonCancellable) { forgetGovee.await() } }
                        catch (_: Exception) { problem = "Govee key cleanup unavailable. Enable the panel and use Forget Govee key to retry; no automatic retry." }
                        cleanup.join(); deleting = controller.view.value.state != EnvironmentalState.DISABLED
                    } catch (_: Exception) { problem = "Environmental cleanup incomplete; retry deletion explicitly." }
                    finally { stopping = false } }
                }, enabled = !busy && !stopping) { Text("Confirm delete / resume") }
                TextButton({ deleting = false }) { Text("Keep data") }
            }
            }
            view.bindings.filter { !it.archived && it.id == details }.forEach { b ->
                TextButton({ details = null }) { Text("Close details") }
                val (equipment, position) = roomDrafts[b.id] ?: view.roomLabels[b.id] ?: ("Room" to "Shelf")
                Text("Sensor details · ${view.sensorLabels[b.sensorId] ?: b.model}", style = MaterialTheme.typography.titleMedium)
                if (importedNames.any { it.model == b.model }) TextButton({ nameTarget = b.address to b }, enabled = !busy && !stopping) { Text("Use Govee name…") }
                Text("${b.model} · ${b.address}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(equipment, { roomDrafts[b.id] = it.take(120) to position }, label = { Text("Room label") })
                OutlinedTextField(position, { roomDrafts[b.id] = equipment to it.take(120) }, label = { Text("Position label") })
                TextButton({
                    val current = roomDrafts[b.id] ?: view.roomLabels[b.id] ?: ("Room" to "Shelf")
                    action { controller.configureRoom(b, current.first, current.second) }
                }, enabled = !busy && !stopping && equipment.isNotBlank() && position.isNotBlank()) { Text("Save room assignment") }
                OutlinedTextField(offset, { offset = it.take(12) }, label = { Text("Humidity calibration offset (%RH)") })
                TextButton({ action { controller.calibrate(b, Channel.HUMIDITY, offset.toDouble()) } }, enabled = !busy && !stopping && offset.toDoubleOrNull()?.isFinite() == true) { Text("Save RH calibration") }
                TextButton({ action { controller.archive(b) } }, enabled = !busy && !stopping) { Text("Archive binding") }
            }
            if (historyMode && rows.size == controller.historyPageSize) OutlinedButton({ val last = JSONObject(rows.last()); action { val loaded = controller.loadHistory(last.getLong("observedAt"), last.getString("eventId")); val parsed = EnvironmentalCharts.history(loaded); rows = loaded; chartHistory = parsed } }, enabled = !busy && !stopping) { Text("Older ${controller.historyPageSize}") }
            if (setup) {
                Text("Monitoring runs while this screen is visible. Times show reception, not when the sensor measured. Charts show up to 500 samples; lines break at known gaps.", style = MaterialTheme.typography.bodySmall)
                Text(view.status, style = MaterialTheme.typography.bodySmall)
            }
    }
    nameTarget?.let { (address, binding) ->
        val model = binding?.model ?: view.candidates.firstOrNull { it.address.equals(address, true) }?.model
        if (model != null) GoveeNamePicker(model, importedNames, { nameTarget = null }) { selected ->
            nameTarget = null
            if (binding == null) candidateNames[address] = selected.name
            else action { controller.renameSensor(binding, selected.name) }
        }
    }
}
