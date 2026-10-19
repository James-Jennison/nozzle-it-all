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

@Composable fun EnvironmentalPanel(controller: EnvironmentalCoordinator) {
    val v by controller.view.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("Environmental sensor") }
    var rows by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var equipmentName by remember { mutableStateOf("Room") }
    var positionName by remember { mutableStateOf("Shelf") }
    var offset by remember { mutableStateOf("0.0") }
    fun action(block: () -> Unit) { if (!busy) { busy = true; error = null; scope.launch { try { withContext(Dispatchers.IO) { block() } } catch (_: Exception) { error = "Operation failed; data preserved where possible. Check radio/permission/storage and retry explicitly." } finally { busy = false } } } }
    Card(Modifier.fillMaxWidth()) {
        Txt("Environmental monitoring (optional)", Nz.type.title)
        Txt(v.status, Nz.type.body)
        EnvironmentalProviders.ble.forEach { Txt(it.name + " · active scan", Nz.type.bodySmall) }
        Txt("Local foreground only. Active BLE scanning uses radio/battery. No pairing, Govee unbinding, cloud, background service or equipment control.", Nz.type.bodySmall)
        error?.let { Banner(it, BannerKind.WARNING) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (v.state == EnvironmentalState.DISABLED) NzButton("Enable", { action { controller.enable() } }, enabled = !busy, testTag = "environment-enable")
            if (v.state == EnvironmentalState.ENABLED_IDLE) {
                NzButton("Discover sensors", { action { controller.discover() } }, enabled = !busy, testTag = "environment-discover")
                NzButton("Start diagnostics", { action { controller.start() } }, enabled = !busy, testTag = "environment-start")
            }
            if (v.state != EnvironmentalState.DISABLED) {
                NzButton("Stop / cancel", { action { controller.stop() } }, enabled = !busy, testTag = "environment-stop")
                NzButton("Disable", { action { controller.stop(disable = true) } }, enabled = !busy)
            }
        }
        if (v.candidates.isNotEmpty()) {
            Field("Sensor label", name, { name = it.take(120) })
            v.candidates.forEachIndexed { i, candidate -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("${candidate.model} · ${candidate.address} · candidate ${i + 1} · unqualified", Nz.type.body)
                NzButton("Save only", { action { controller.save(candidate, name) } }, enabled = !busy && name.isNotBlank())
            } }
        }
        v.latest.values.forEach { observation ->
            Txt(observation.readings.joinToString(" · ") { "${it.channel.name}: ${it.value} ${it.channel.unit}" }, Nz.type.metricSmall)
            Txt("Reception ${java.time.Instant.ofEpochMilli(observation.observedAt)} — physical measurement age unknown; decoder ${observation.decoder}", Nz.type.bodySmall)
        }
        if (v.dropped > 0) Banner("${v.dropped} reports dropped — coverage incomplete", BannerKind.WARNING)
        if (v.recordedDrops > 0) Banner("History records ${v.recordedDrops} dropped reports; other unobserved gaps remain unknown", BannerKind.WARNING)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NzButton("Open local history", { action { rows = controller.loadHistory(); controller.savedBindings() } }, enabled = !busy, testTag = "environment-history")
            NzButton("Apply 30-day retention", { action { controller.retention() } }, enabled = !busy)
            NzButton("Export history…", {
                val chooser = javax.swing.JFileChooser()
                if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    val file = chooser.selectedFile
                    if (!file.exists()) action { file.outputStream().bufferedWriter().use { writer -> controller.export { writer.write(it); writer.newLine() } } }
                    else error = "Choose a new filename; existing exports are not overwritten."
                }
            }, enabled = !busy)
            NzButton("Delete environmental data…", { confirmDelete = true }, enabled = !busy)
        }
        if (confirmDelete) {
            Txt("Delete only environmental configuration/history? External exports remain. Cleanup failures require explicit retry.", Nz.type.body)
            Row { NzButton("Confirm delete / resume", { action { controller.deleteAll(); rows = emptyList(); confirmDelete = false } }, enabled = !busy); NzButton("Keep data", { confirmDelete = false }) }
        }
        if (v.bindings.any { !it.archived }) {
            Field("Equipment label (draft)", equipmentName, { equipmentName = it.take(120) })
            Field("Position label (draft)", positionName, { positionName = it.take(120) })
            Field("Relative humidity calibration offset (%RH)", offset, { offset = it.take(12) })
        }
        v.bindings.filterNot { it.archived }.forEach { binding -> Column {
            Txt("${binding.model} — firmware/calibration/operating envelope unqualified", Nz.type.bodySmall)
            Txt("${binding.address} · ${binding.protocol} — verify the physical identity before saving assignments", Nz.type.bodySmall)
            NzButton("Save draft assignment only", { action { controller.configureRoom(binding, equipmentName, positionName) } }, enabled = !busy && equipmentName.isNotBlank() && positionName.isNotBlank())
            Txt("Assignments without proven thermal/firmware qualification block diagnostic Start. No qualified deployment claim.", Nz.type.bodySmall)
            NzButton("Save RH calibration", { action { controller.calibrate(binding, Channel.HUMIDITY, offset.toDouble()) } }, enabled = !busy && offset.toDoubleOrNull()?.isFinite() == true)
            NzButton("Archive binding", { action { controller.archive(binding) } }, enabled = !busy)
        } }
        rows.take(500).forEach { raw ->
            val j = JSONObject(raw)
            Txt("${java.time.Instant.ofEpochMilli(j.getLong("observedAt"))} · ${j.getJSONArray("readings")} · measurement age unknown", Nz.type.bodySmall)
        }
        if (rows.size == 500) NzButton("Older 500", { val last = JSONObject(rows.last()); action { rows = controller.loadHistory(last.getLong("observedAt"), last.getString("eventId")) } }, enabled = !busy)
    }
}
