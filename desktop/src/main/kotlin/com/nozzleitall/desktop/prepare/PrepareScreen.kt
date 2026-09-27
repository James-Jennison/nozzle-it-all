package com.nozzleitall.desktop.prepare

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.screens.*
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun chooseFiles(title: String, extensions: List<String>, multiple: Boolean = true): List<File> {
    val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
    d.isMultipleMode = multiple
    d.setFilenameFilter { _, n -> extensions.any { n.endsWith(".$it", true) } }
    d.isVisible = true
    return d.files?.toList() ?: emptyList()
}

fun chooseSaveFile(title: String, suggested: String): File? {
    val d = FileDialog(null as Frame?, title, FileDialog.SAVE); d.file = suggested; d.isVisible = true
    return d.file?.let { File(d.directory, it) }
}

@Composable
fun PrepareScreen(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    val camera = remember { ViewCamera() }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Project bar: what you're working on, and saving it, always in view.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Project", p.name, { p.name = it; p.dirty = true }, Modifier.width(320.dp))
            Column(Modifier.padding(top = 22.dp)) { Txt(if (p.dirty) "Unsaved changes" else p.file?.let { "Saved" } ?: "Not saved yet", Nz.type.bodySmall, if (p.dirty) Nz.status.paused else c.textMuted) }
            Spacer(Modifier.weight(1f))
            NzButton("Add model", { chooseFiles("Add a model", listOf("stl", "3mf", "obj")).forEach { f -> runCatching { p.importModel(f) }.onFailure { p.notice = it.message } } },
                icon = NzIcon.IMPORT, testTag = "add-model")
            NzButton("Save", { runCatching { p.save() }.onFailure { p.notice = "Couldn't save: ${it.message}" } }, kind = ButtonKind.PRIMARY, enabled = p.items.isNotEmpty(), testTag = "save-project")
            NzButton("Open in Advanced Workspace", {
                runCatching { p.save() }.onSuccess { state.destination = Destination.WORKSPACE }.onFailure { p.notice = "Save the project before opening it in the Advanced Workspace: ${it.message}" }
            }, kind = ButtonKind.QUIET, icon = NzIcon.WORKSPACE, enabled = p.items.isNotEmpty())
        }
        p.notice?.let { Banner(it, BannerKind.WARNING, "Dismiss" to { p.notice = null }) }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // The plate.
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val done = p.slice as? SliceState.Done
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NzButton("Plate", { p.showPreview = false }, kind = if (!p.showPreview) ButtonKind.PRIMARY else ButtonKind.SECONDARY, icon = NzIcon.PREPARE)
                    NzButton("Layer preview", { p.showPreview = true }, kind = if (p.showPreview) ButtonKind.PRIMARY else ButtonKind.SECONDARY, icon = NzIcon.LAYERS, enabled = done?.preview != null)
                    Spacer(Modifier.weight(1f))
                    Txt("Drag to orbit · Shift-drag to pan · Scroll to zoom", Nz.type.bodySmall, c.textMuted)
                }
                val slots = p.materials()
                val slotColors = slots.map { parseHex(it.colorHex) ?: c.accent }
                val objects = p.items.map { PlateObject(it.id, it.name, it.mesh, slotColors.getOrElse(it.slot - 1) { c.accent }, it.x, it.y, it.rotZ, it.scale, it.id == p.selected) }
                val (bw, bd) = p.bed
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(20.dp))) {
                    if (p.items.isEmpty() && !p.showPreview) EmptyState(NzIcon.IMPORT, "Start with a model",
                        "Add an STL, 3MF or OBJ file, or open a project from Projects. Nothing leaves this computer.", Modifier.align(Alignment.Center)) {
                        NzButton("Add model", { chooseFiles("Add a model", listOf("stl", "3mf", "obj")).forEach { f -> runCatching { p.importModel(f) }.onFailure { p.notice = it.message } } }, kind = ButtonKind.PRIMARY)
                    }
                    else PlateViewer(bw, bd, objects, camera, c.surface, c.lineStrong, Modifier.fillMaxSize(),
                        preview = if (p.showPreview) done?.preview else null, previewLayer = p.previewLayer, toolColors = slotColors, onSelect = { p.selected = it })
                }
                if (p.showPreview && done?.preview != null) {
                    val layers = done.preview.layers
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NzButton("−", { p.previewLayer = (p.previewLayer - 1).coerceAtLeast(0) }, kind = ButtonKind.SECONDARY)
                        LayerSlider(p.previewLayer, layers.size, Modifier.weight(1f)) { p.previewLayer = it }
                        NzButton("+", { p.previewLayer = (p.previewLayer + 1).coerceAtMost(layers.size - 1) }, kind = ButtonKind.SECONDARY)
                        Txt("Layer ${p.previewLayer + 1} of ${layers.size} · Z ${"%.2f".format(layers[p.previewLayer].z)} mm", Nz.type.metricSmall, c.textMuted)
                    }
                }
            }
            // Guided steps.
            Column(Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StepPrinter(state)
                StepObjects(state)
                StepSettings(state)
                StepSlice(state)
            }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, content: @Composable ColumnScope.() -> Unit) = Card(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Nz.colors.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { Txt("$number", Nz.type.label, Nz.colors.accent) }
        Txt(title, Nz.type.title)
    }
    content()
}

@Composable
private fun StepPrinter(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    Step(1, "Printer and materials") {
        val ids = state.fleet.order.value
        if (ids.isEmpty()) Txt("No printer added yet: preparing for a Snapmaker U1. Add a printer to match its loaded materials.", Nz.type.bodySmall, c.textMuted)
        else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ids.forEach { id -> state.fleet.printers[id]?.let { e -> NzButton(e.config.identity.displayName, { p.printerId = id; p.changed() },
                kind = if ((p.printerId ?: ids.first()) == id) ButtonKind.PRIMARY else ButtonKind.SECONDARY) } }
        }
        val slots = p.materials()
        val fromPrinter = p.printer()?.status?.value?.toolheads?.isNotEmpty() == true
        Txt(if (fromPrinter) "Loaded in the printer now:" else "Materials (the printer hasn't reported its toolheads):", Nz.type.bodySmall, c.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            slots.forEach { s -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Material ${s.slot}: ${s.type} ${s.colorHex}" }) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(parseHex(s.colorHex) ?: c.surfaceSunken).border(1.dp, c.lineStrong, CircleShape))
                Txt("${s.slot} · ${s.type ?: "?"}", Nz.type.bodySmall)
            } }
        }
    }
}

@Composable
private fun StepObjects(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    Step(2, "Objects on the plate") {
        if (p.items.isEmpty()) { Txt("Nothing on the plate yet.", Nz.type.body, c.textMuted); return@Step }
        p.items.forEach { item ->
            val sel = item.id == p.selected
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) c.surfaceRaised else Color.Transparent)
                .selectable(sel, role = Role.Button) { p.selected = if (sel) null else item.id }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(item.name, Nz.type.body, modifier = Modifier.weight(1f), maxLines = 1)
                Txt("${"%.0f".format(item.footprintW)}×${"%.0f".format(item.footprintD)}×${"%.0f".format(item.height)} mm", Nz.type.metricSmall, c.textMuted)
            }
        }
        val s = p.items.firstOrNull { it.id == p.selected }
        if (s != null) {
            Txt("Material for ${s.name}", Nz.type.label)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.materials().forEach { m -> NzButton("${m.slot}", { s.slot = m.slot; p.changed() }, kind = if (s.slot == m.slot) ButtonKind.PRIMARY else ButtonKind.SECONDARY) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("X mm", s.x) { s.x = it; p.changed() }
                NumberField("Y mm", s.y) { s.y = it; p.changed() }
                NumberField("Turn °", s.rotZ) { s.rotZ = it; p.changed() }
                NumberField("Size %", s.scale * 100) { v -> if (v > 0) { s.scale = v / 100; p.changed() } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NzButton("Duplicate", { p.duplicateSelected() }, kind = ButtonKind.SECONDARY)
                NzButton("Remove", { p.removeSelected() }, kind = ButtonKind.QUIET)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NzButton("Arrange", { p.arrange() }, kind = ButtonKind.SECONDARY, icon = NzIcon.MOVE) }
        p.outOfBounds().takeIf { it.isNotEmpty() }?.let { Txt("Off the plate: ${it.joinToString { o -> o.name }}", Nz.type.bodySmall, c.danger) }
    }
}

@Composable
private fun NumberField(label: String, value: Float, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf("%.1f".format(value).removeSuffix(".0")) }
    Field(label, text, { t -> text = t; t.replace(',', '.').toFloatOrNull()?.let(onValue) }, Modifier.width(84.dp))
}

@Composable
private fun StepSettings(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    Step(3, "How it prints") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QualityPreset.entries.forEach { q -> NzButton(q.label, { p.preset = q; p.changed() }, kind = if (p.preset == q) ButtonKind.PRIMARY else ButtonKind.SECONDARY) }
        }
        Txt(p.preset.detail, Nz.type.bodySmall, c.textMuted)
        Toggle("Supports", p.supports, { p.supports = it; p.changed() }, "Add supports under overhangs.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt("Infill", Nz.type.body, modifier = Modifier.width(60.dp))
            listOf(10, 15, 25, 40).forEach { v -> NzButton("$v%", { p.infill = v; p.changed() }, kind = if (p.infill == v) ButtonKind.PRIMARY else ButtonKind.SECONDARY) }
        }
        Txt("Need more control? Open the project in the Advanced Workspace.", Nz.type.bodySmall, c.textMuted)
    }
}

@Composable
private fun StepSlice(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    Step(4, "Slice and send") {
        when (val s = p.slice) {
            SliceState.Idle, SliceState.Cancelled, is SliceState.Failed -> {
                if (s is SliceState.Failed) Banner(s.message, BannerKind.DANGER)
                if (s is SliceState.Cancelled) Txt("Slicing was cancelled. Nothing was changed.", Nz.type.bodySmall, c.textMuted)
                NzButton("Slice", { p.slice(state.scope) }, kind = ButtonKind.PRIMARY, icon = NzIcon.SLICE, enabled = p.items.isNotEmpty(), testTag = "slice")
            }
            is SliceState.Running -> {
                Txt(s.stage, Nz.type.body)
                ProgressBar(s.progress, label = "Slicing progress")
                NzButton("Cancel", { p.cancelSlice() }, kind = ButtonKind.SECONDARY, icon = NzIcon.CLOSE)
            }
            is SliceState.Done -> {
                val st = s.result.stats
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Metric("Time", formatDuration(st.seconds)); Metric("Material", st.grams?.let { "%.1f g".format(it) } ?: "–"); Metric("Layers", "${st.layers ?: "–"}")
                }
                if (st.toolChanges > 0) Txt("${st.toolChanges} toolhead changes", Nz.type.bodySmall, c.textMuted)
                s.result.warnings.forEach { Banner(it, BannerKind.WARNING) }
                SendPanel(state)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NzButton("Export file", { chooseSaveFile("Export sliced file", p.name + ".gcode")?.let { t -> s.result.gcode.copyTo(t, overwrite = true) } }, kind = ButtonKind.QUIET, icon = NzIcon.EXPORT)
                    NzButton("Slice again", { p.slice(state.scope) }, kind = ButtonKind.QUIET)
                }
            }
        }
    }
}

@Composable
private fun SendPanel(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    val entry = p.printer() ?: run { Txt("Add a printer to send this job, or export the file.", Nz.type.bodySmall, c.textMuted); return }
    val flow = rememberActionFlow(entry)
    var uploaded by remember(p.slice) { mutableStateOf<String?>(null) }
    var problem by remember(p.slice) { mutableStateOf<String?>(null) }
    ActionFlowUi(flow, entry)
    problem?.let { Banner(it, BannerKind.WARNING) }
    val status = entry.status.value
    if (uploaded == null) {
        p.uploadProgress?.let { ProgressBar(it, label = "Sending to printer") }
        NzButton("Send to ${entry.config.identity.displayName}", { scope.launch {
            problem = null
            when (val r = p.upload(entry)) {
                is UploadResult.Uploaded -> uploaded = r.remotePath
                is UploadResult.Interrupted -> problem = "The transfer was interrupted: ${r.reason} The file on the printer may be incomplete. Send it again before printing."
                is UploadResult.Failed -> problem = r.reason
            }
        } }, kind = ButtonKind.PRIMARY, icon = NzIcon.SEND, enabled = p.uploadProgress == null && status.state != PrinterState.OFFLINE, testTag = "send")
    } else {
        Banner("Sent to ${entry.config.identity.displayName}. Start printing when the plate is clear.", BannerKind.SUCCESS)
        val map = p.items.map { it.slot }.distinct().sorted()
        val toolMap = if (entry.status.value.toolheads.isNotEmpty()) (1..(map.maxOrNull() ?: 1)).map { it - 1 } else emptyList()
        NzButton("Start print", { flow.request(PrinterAction.StartJob(uploaded!!, toolMap)) }, kind = ButtonKind.PRIMARY, icon = NzIcon.PLAY,
            enabled = status.state in PrinterAction.StartJob("x").allowedStates && !flow.busy)
        NzButton("Watch it print", { state.openPrinter(entry.config.identity.id) }, kind = ButtonKind.QUIET, icon = NzIcon.MONITOR)
    }
}

@Composable
fun LayerSlider(value: Int, count: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val c = Nz.colors
    var width by remember { mutableStateOf(1f) }
    Box(modifier.height(28.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }.semantics {
        stateDescription = "Layer ${value + 1} of $count"
        setProgress { t -> onChange(t.toInt().coerceIn(0, count - 1)); true }
        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..(count - 1).coerceAtLeast(1).toFloat())
    }.pointerInput(count) {
        awaitPointerEventScope { while (true) { val e = awaitPointerEvent(); val ch = e.changes.firstOrNull() ?: continue
            if (ch.pressed) onChange(((ch.position.x / width) * (count - 1)).toInt().coerceIn(0, count - 1)) } }
    }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceRaised))
        Box(Modifier.fillMaxWidth(if (count <= 1) 1f else value / (count - 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.accent))
    }
}

