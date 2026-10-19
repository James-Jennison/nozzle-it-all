package com.nozzleitall.desktop.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.environment.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.nozzleitall.desktop.environment.DesktopGoveeNames

@Composable fun EnvironmentalPanel(controller: EnvironmentalCoordinator, directory: DesktopGoveeNames) {
    val v by controller.view.collectAsState()
    val scope = rememberCoroutineScope()
    val importedNames by directory.devices.collectAsState()
    var choosing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    val candidateNames = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(v.candidates) { candidateNames.keys.retainAll(v.candidates.map { it.address }.toSet()) }
    var rows by remember { mutableStateOf<List<String>>(emptyList()) }
    var chartHistory by remember { mutableStateOf<List<ChartSample>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val roomDrafts = remember { mutableStateMapOf<String, Pair<String, String>>() }
    var offset by remember { mutableStateOf("0.0") }
    fun action(block: () -> Unit) { if (!busy && !stopping) { busy = true; error = null; scope.launch { try { withContext(Dispatchers.IO) { block() } } catch (e: QualificationUnavailableException) { error = e.message } catch (_: Exception) { error = "Operation failed; data preserved where possible. Check radio/permission/storage and retry explicitly." } finally { busy = false } } } }
    fun start(purpose: Purpose) {
        if (!busy && !stopping) { busy = true; error = null
            try { val work = controller.requestOperationAsync(purpose); scope.launch { try { work.join() } finally { busy = false } } }
            catch (_: Exception) { busy = false; error = "Preparation unavailable or cancelled — retry explicitly" }
        }
    }
    fun stop(disable: Boolean = false) {
        if (!stopping) { stopping = true; error = null
            val cleanup = controller.requestStopAsync(disable = disable)
            scope.launch { try { cleanup.join() } finally { stopping = false } }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Txt("Environmental monitoring (optional)", Nz.type.title)
        Txt(v.status, Nz.type.body)
        EnvironmentalProviders.ble.filter { it.id == "govee-ble" }.forEach { Txt(it.name + " · active scan", Nz.type.bodySmall) }
        Txt("Local foreground only. Optional name import is the only Govee cloud request; no cloud readings, pairing, background service or equipment control.", Nz.type.bodySmall)
        GoveeNameImport(directory)
        Txt("Imported Govee names fill matching sensors automatically. Physical measurement age is unknown; monitoring does not control equipment.", Nz.type.bodySmall)
        error?.let { Banner(it, BannerKind.WARNING) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (v.state == EnvironmentalState.DISABLED) NzButton("Enable", { action { controller.enable() } }, enabled = !busy && !stopping, testTag = "environment-enable")
            if (v.state == EnvironmentalState.ENABLED_IDLE) {
                NzButton("Discover sensors", { start(Purpose.DISCOVERY) }, enabled = !busy && !stopping, testTag = "environment-discover")
                NzButton("Start monitoring", { start(Purpose.COLLECTION) }, enabled = !busy && !stopping, testTag = "environment-start")
            }
            if (v.state != EnvironmentalState.DISABLED) {
                NzButton("Stop / cancel", { stop() }, enabled = !stopping, testTag = "environment-stop")
                NzButton("Disable", { stop(disable = true) }, enabled = !stopping)
            }
        }
        EnvironmentalDashboard(v, chartHistory)
        if (v.candidates.isNotEmpty()) {
            v.candidates.filter { it.protocol == "govee-ble" }.forEach { candidate -> key(candidate.address) { Column {
                val saved = v.bindings.firstOrNull { !it.archived && it.address.equals(candidate.address, true) }
                val added = saved != null
                val name = if (saved != null) v.sensorLabels[saved.sensorId] ?: candidate.defaultName
                    else candidateNames[candidate.address] ?: importedNames.singleOrNull { it.matches(candidate) }?.name ?: candidate.defaultName
                val reception = v.discovery[candidate.address]
                Txt(name, Nz.type.body)
                Txt("${candidate.model} · ${candidate.address}", Nz.type.bodySmall)
                Txt(reception?.readings?.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "${it.channel.name}: ${it.value} ${it.channel.unit}" } ?: "Readings unavailable", Nz.type.bodySmall)
                Txt("Signal: ${reception?.rssiDbm?.let { "$it dBm" } ?: "unavailable"} · Last radio reception: ${reception?.receivedAt?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "unavailable"}", Nz.type.bodySmall)
                if (!added) Field("Name this sensor", name, { candidateNames[candidate.address] = it.take(120) })
                NzButton(if (added) "Already added" else "Add sensor", {
                    val selectedName = name
                    action { controller.save(candidate, selectedName) }
                }, enabled = !busy && !stopping && !added && name.isNotBlank())
                if (!added && importedNames.any { it.model == candidate.model }) {
                    NzButton("Choose Govee name…", { choosing = candidate.address })
                    if (choosing == candidate.address) GoveeNameChoices(candidate.model, importedNames, { choosing = null }) {
                        candidateNames[candidate.address] = it.name; choosing = null
                    }
                }
            } } }
        }
        if (v.dropped > 0) Banner("Queue overflow counter for this monitor: ${v.dropped} — coverage incomplete", BannerKind.WARNING)
        if (v.recordedDrops > 0) Banner("History records ${v.recordedDrops} dropped reports; other unobserved gaps remain unknown", BannerKind.WARNING)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NzButton("Open local history", { action { val loaded = controller.loadHistory(); val parsed = EnvironmentalCharts.history(loaded); controller.savedBindings(); rows = loaded; chartHistory = parsed } }, enabled = !busy && !stopping, testTag = "environment-history")
            NzButton("Apply 30-day retention", { action { controller.retention() } }, enabled = !busy && !stopping)
            NzButton("Export history…", {
                val chooser = javax.swing.JFileChooser()
                if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    val file = chooser.selectedFile
                    if (!file.exists()) action { file.outputStream().bufferedWriter().use { writer -> controller.export { writer.write(it); writer.newLine() } } }
                    else error = "Choose a new filename; existing exports are not overwritten."
                }
            }, enabled = !busy && !stopping)
            NzButton("Delete environmental data…", { confirmDelete = true }, enabled = !busy && !stopping)
        }
        if (confirmDelete) {
            Txt("Delete only environmental configuration/history? External exports remain. Cleanup failures require explicit retry.", Nz.type.body)
            Row { NzButton("Confirm delete / resume", { directory.forget(); choosing = null; stopping = true; rows = emptyList(); chartHistory = emptyList(); candidateNames.clear(); roomDrafts.clear(); offset = "0.0"
                val cleanup = controller.requestDeleteAsync()
                scope.launch { try { cleanup.join(); confirmDelete = controller.view.value.state != EnvironmentalState.DISABLED } finally { stopping = false } }
            }, enabled = !busy && !stopping); NzButton("Keep data", { confirmDelete = false }) }
        }
        if (v.bindings.any { !it.archived }) {
            Field("Relative humidity calibration offset (%RH)", offset, { offset = it.take(12) })
        }
        v.bindings.filterNot { it.archived }.forEach { binding -> Column {
            val (equipmentName, positionName) = roomDrafts[binding.id] ?: v.roomLabels[binding.id] ?: ("Room" to "Shelf")
            Txt("Added: ${v.sensorLabels[binding.sensorId] ?: binding.model} · ${binding.model}", Nz.type.bodySmall)
            if (importedNames.any { it.model == binding.model }) {
                NzButton("Choose Govee name…", { choosing = binding.id }, enabled = !busy && !stopping)
                if (choosing == binding.id) GoveeNameChoices(binding.model, importedNames, { choosing = null }) { chosen ->
                    choosing = null; action { controller.renameSensor(binding, chosen.name) }
                }
            }
            Txt("${binding.address} · ${binding.protocol} — verify the physical identity before saving assignments", Nz.type.bodySmall)
            Field("Room label", equipmentName, { roomDrafts[binding.id] = it.take(120) to positionName })
            Field("Position label", positionName, { roomDrafts[binding.id] = equipmentName to it.take(120) })
            NzButton("Save room assignment", {
                val current = roomDrafts[binding.id] ?: v.roomLabels[binding.id] ?: ("Room" to "Shelf")
                action { controller.configureRoom(binding, current.first, current.second) }
            }, enabled = !busy && !stopping && equipmentName.isNotBlank() && positionName.isNotBlank())
            Txt("Room assignments do not block monitoring or certify sensor operating limits.", Nz.type.bodySmall)
            NzButton("Save RH calibration", { action { controller.calibrate(binding, Channel.HUMIDITY, offset.toDouble()) } }, enabled = !busy && !stopping && offset.toDoubleOrNull()?.isFinite() == true)
            NzButton("Archive binding", { action { controller.archive(binding) } }, enabled = !busy && !stopping)
        } }
        if (rows.size == 500) NzButton("Older 500", { val last = JSONObject(rows.last()); action { val loaded = controller.loadHistory(last.getLong("observedAt"), last.getString("eventId")); val parsed = EnvironmentalCharts.history(loaded); rows = loaded; chartHistory = parsed } }, enabled = !busy && !stopping)
    }
}
