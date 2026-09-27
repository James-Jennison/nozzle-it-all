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
import com.nozzleitall.design.NozzleTokens
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
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Left: the project and everything about how it prints, with Slice always in view at the bottom. All settings
        // opens in the same place, wider, so the plate stays visible.
        // Left: the project, printer, filament and objects, then every print setting in dense tabs, with Slice always
        // in view at the bottom.
        BoxWithConstraints(Modifier.width(420.dp).fillMaxHeight()) {
        val panelHeight = maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProjectHeader(state)
            p.notice?.let { Banner(it, BannerKind.WARNING, "Dismiss" to { p.notice = null }) }
            // Printer, filament and objects scroll within the upper part of the panel when they grow (an open mix or
            // slot editor, many colours), so the print settings and Slice always stay in view.
            Column(Modifier.fillMaxWidth().heightIn(max = panelHeight * 0.58f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StepPrinter(state)
                MaterialsSection(state)
                StepObjects(state)
            }
            val profileValues = remember(p.profileId) { com.nozzleitall.desktop.settings.ProfileValues.read(p.profileDir(), com.nozzleitall.desktop.settings.SettingsCatalog.bundled) }
            com.nozzleitall.desktop.settings.SettingsSheet(com.nozzleitall.desktop.settings.SettingsCatalog.bundled, profileValues, p, Modifier.weight(1f).fillMaxWidth())
            StepSlice(state)
        }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                val objects = p.items.map { item ->
                    // A slot is a loaded filament or a Full Spectrum mix (drawn in its blended colour).
                    val paintColors = if (item.painted.isEmpty()) emptyList() else List((item.painted.maxOrNull() ?: 0) + 1) { n -> parseHex(p.slotHex(item.slotFor(n))) }
                    PlateObject(item.id, item.name, item.mesh, parseHex(p.slotHex(item.slot)) ?: c.accent, item.x, item.y, item.rotZ, item.scale, item.id == p.selected, paintColors)
                }
                val (bw, bd) = p.bed
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(20.dp))) {
                    if (p.items.isEmpty() && !p.showPreview) EmptyState(NzIcon.IMPORT, "Start with a model",
                        "Add an STL, 3MF or OBJ file, or open a project from Projects. Nothing leaves this computer.", Modifier.align(Alignment.Center)) {
                        NzButton("Add model", { chooseFiles("Add a model", listOf("stl", "3mf", "obj")).forEach { f -> runCatching { p.importModel(f) }.onFailure { p.notice = it.message } } }, kind = ButtonKind.PRIMARY)
                    }
                    else PlateViewer(bw, bd, objects, camera, c.surface, c.lineStrong, Modifier.fillMaxSize(),
                        preview = if (p.showPreview) done?.preview else null, previewLayer = p.previewLayer, toolColors = slotColors, onSelect = { focus.clearFocus(); p.selected = it })
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
        }
    }
}

/** The project name, whether it's saved, and adding and saving, at the top of the panel. */
@Composable
private fun ProjectHeader(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.nozzleitall.desktop.settings.DenseInput(p.name, { p.name = it; p.dirty = true }, "Project name", Modifier.weight(1f))
        com.nozzleitall.desktop.settings.IconToggle(NzIcon.IMPORT, "Add model", false) { chooseFiles("Add a model", listOf("stl", "3mf", "obj")).forEach { f -> runCatching { p.importModel(f) }.onFailure { p.notice = it.message } } }
        NzButton("Save", { runCatching { p.save() }.onFailure { p.notice = "Couldn't save: ${it.message}" } }, kind = ButtonKind.PRIMARY, enabled = p.items.isNotEmpty(), testTag = "save-project")
    }
    Txt(if (p.dirty) "Unsaved changes" else p.file?.let { "Saved" } ?: "Not saved yet", Nz.type.bodySmall, if (p.dirty) Nz.status.paused else c.textMuted)
}

/**
 * One part of the panel: a heading that folds it away, with a one-line [summary] of its current choice shown while
 * folded, so the whole setup can be read at a glance.
 */
@Composable
private fun Section(title: String, summary: String, initiallyOpen: Boolean = true, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Nz.colors
    var open by remember(title) { mutableStateOf(initiallyOpen) }
    val shape = RoundedCornerShape(NozzleTokens.Radius.card)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = if (open) "Fold $title" else "Open $title") { open = !open }
                .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(if (open) "▾" else "▸", Nz.type.label, c.textMuted)
                Txt(title, Nz.type.label)
                Txt(if (open) "" else summary, Nz.type.bodySmall, c.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
            }
            action?.invoke()
        }
        if (open) content()
    }
}

@Composable
private fun StepPrinter(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    var query by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    val printerName = p.printer()?.config?.identity?.displayName ?: "None (export only)"
    Section("Printer", printerName + " · " + (p.profile?.model ?: p.profileId),
        action = { com.nozzleitall.desktop.settings.IconToggle(NzIcon.TUNE, "Printer settings", p.settingsScope == com.nozzleitall.desktop.settings.Scope.PRINTER) {
            p.settingsScope = if (p.settingsScope == com.nozzleitall.desktop.settings.Scope.PRINTER) com.nozzleitall.desktop.settings.Scope.PROCESS else com.nozzleitall.desktop.settings.Scope.PRINTER } }) {
        val ids = state.fleet.order.value
        val choices = listOf(com.nozzleitall.desktop.settings.Choice("", "None (export only)")) +
            ids.mapNotNull { id -> state.fleet.printers[id]?.let { com.nozzleitall.desktop.settings.Choice(id, it.config.identity.displayName) } }
        com.nozzleitall.desktop.settings.DenseSelect("Printer", choices, p.printerId ?: "", Modifier.fillMaxWidth()) { v -> p.choosePrinter(v.ifBlank { null }) }
        // Slicing needs only a profile. It is independent of the connection, so any printer can be prepared for, offline.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt("Profile", Nz.type.bodySmall, c.textMuted)
            Txt(p.profile?.let { "${it.vendor} · ${it.model}" } ?: p.profileId, Nz.type.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
            Txt(if (choosing) "Done" else "Change", Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { choosing = !choosing }.padding(4.dp))
        }
        if (choosing) {
            com.nozzleitall.desktop.settings.DenseInput(query, { query = it }, "Search printer models", Modifier.fillMaxWidth(), placeholder = "Snapmaker, Prusa, Bambu, Voron…")
            ProfileCatalog.search(query).take(8).forEach { pr ->
                Txt("${pr.vendor} · ${pr.model}", Nz.type.bodySmall, if (p.profileId == pr.id) c.accent else c.text, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { p.profileId = pr.id; p.changed(); choosing = false }.padding(horizontal = 6.dp, vertical = 5.dp))
            }
            Txt("${ProfileCatalog.all.size} printer profiles. A profile means Nozzle can slice for that printer; it doesn't mean the printer has been tested.",
                Nz.type.bodySmall, c.textMuted)
        }
    }
}

@Composable
private fun MaterialsSection(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    val slots = p.materials()
    val fromPrinter = p.printer()?.status?.value?.toolheads?.any { it.material != null } == true
    Section("Filament", slots.joinToString(" · ") { "${it.slot} ${it.type ?: "?"}" },
        action = { com.nozzleitall.desktop.settings.IconToggle(NzIcon.TUNE, "Material settings", p.settingsScope == com.nozzleitall.desktop.settings.Scope.FILAMENT) {
            p.settingsScope = if (p.settingsScope == com.nozzleitall.desktop.settings.Scope.FILAMENT) com.nozzleitall.desktop.settings.Scope.PROCESS else com.nozzleitall.desktop.settings.Scope.FILAMENT } }) {
        if (fromPrinter) Txt("As loaded in ${p.printer()?.config?.identity?.displayName}", Nz.type.bodySmall, c.textMuted)
        // Two columns of slots: the number on the material's colour, then the filament it slices with. Click to change it.
        var editingSlot by remember { mutableStateOf<Int?>(null) }
        val library = p.filamentLibrary()
        slots.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { s ->
                    val name = s.filamentProfile?.let { id -> library.firstOrNull { it.id == id }?.displayName } ?: listOfNotNull(s.vendor, s.type).joinToString(" ").ifBlank { "?" }
                    Row(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(6.dp)).background(c.surfaceSunken)
                        .border(1.dp, if (editingSlot == s.slot) c.accent else c.line, RoundedCornerShape(6.dp))
                        .clickable(onClickLabel = "Change slot ${s.slot}'s filament") { editingSlot = if (editingSlot == s.slot) null else s.slot }
                        .semantics(mergeDescendants = true) { contentDescription = "Slot ${s.slot}: $name ${s.colorHex}" }, verticalAlignment = Alignment.CenterVertically) {
                        SlotBadge(s.slot, s.colorHex)
                        Txt(name, Nz.type.bodySmall, modifier = Modifier.padding(horizontal = 8.dp).weight(1f), maxLines = 1)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        editingSlot?.let { id -> slots.firstOrNull { it.slot == id }?.let { SlotEditor(p, it, library, fromPrinter) { editingSlot = null } } }
        // Painted models: which slot prints each of the model's own colours.
        p.items.filter { it.painted.isNotEmpty() }.forEach { item -> ModelColours(p, item, slots) }
        // Full Spectrum (Snapmaker Orca's colour mixing), wherever the printer or profile offers it.
        if (com.nozzleitall.printer.ext.Snapmaker.FULL_SPECTRUM in p.features() && slots.size >= 2) ColourMixingSection(p)
    }
}

@Composable
private fun ModelColours(p: PrepareState, item: PrepItem, slots: List<com.nozzleitall.project.ProjectManifest.MaterialSlot>) {
    val c = Nz.colors
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Colours in ${item.name}", Nz.type.label, c.text, modifier = Modifier.weight(1f), maxLines = 1)
        Txt("Match", Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Match the model's colours to the nearest loaded filament") {
            p.matchByColour(item) }.padding(4.dp))
    }
    // Loaded filaments, then any Full Spectrum mixes: a model colour can print as either.
    val choices = p.allSlots().map { (id, label) -> com.nozzleitall.desktop.settings.Choice(id.toString(), "$id · $label") }
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(16.dp))
        Txt("Rest of the model", Nz.type.bodySmall, c.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
        Txt("→", Nz.type.bodySmall, c.textMuted)
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(parseHex(p.slotHex(item.slot)) ?: c.accent))
        com.nozzleitall.desktop.settings.DenseSelect("Slot for the rest of the model", choices, item.slot.toString(), Modifier.width(140.dp)) { v -> v.toIntOrNull()?.let { item.slot = it; p.changed() } }
    }
    item.painted.forEach { n ->
        val src = item.sources.firstOrNull { it.index == n }
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(parseHex(src?.colorHex) ?: c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(4.dp)))
            Txt(src?.name?.substringBefore(" @") ?: src?.type ?: "Colour $n", Nz.type.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
            Txt("→", Nz.type.bodySmall, c.textMuted)
            val slot = item.slotFor(n)
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(parseHex(p.slotHex(slot)) ?: c.accent))
            com.nozzleitall.desktop.settings.DenseSelect("Slot for colour $n", choices, slot.toString(), Modifier.width(140.dp)) { v -> v.toIntOrNull()?.let { p.setPaintSlot(item, n, it) } }
        }
    }
}

@Composable
private fun StepObjects(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    Section("Objects", if (p.items.isEmpty()) "None yet" else "${p.items.size} on the plate", initiallyOpen = p.items.isNotEmpty(),
        action = { if (p.items.isNotEmpty()) com.nozzleitall.desktop.settings.IconToggle(NzIcon.MOVE, "Arrange the plate", false) { if (!p.arrange()) p.notice = "Not everything fits on the plate." } }) {
        if (p.items.isEmpty()) { Txt("Nothing on the plate yet.", Nz.type.bodySmall, c.textMuted); return@Section }
        val slots = p.materials()
        p.items.forEach { item ->
            val sel = item.id == p.selected
            Row(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(6.dp)).background(if (sel) c.accent.copy(alpha = 0.14f) else Color.Transparent)
                .selectable(sel, role = Role.Button) { p.selected = if (sel) null else item.id }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(parseHex(p.slotHex(item.slot)) ?: c.accent))
                Txt(item.name, Nz.type.bodySmall, if (sel) c.accent else c.text, modifier = Modifier.weight(1f), maxLines = 1)
                Txt("${"%.0f".format(item.footprintW)}×${"%.0f".format(item.footprintD)}×${"%.0f".format(item.height)} mm", Nz.type.bodySmall, c.textMuted)
            }
        }
        val s = p.items.firstOrNull { it.id == p.selected }
        if (s != null) {
            ObjectRow("Material") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    slots.forEach { m ->
                        val on = s.slot == m.slot
                        Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(parseHex(m.colorHex) ?: c.surfaceSunken)
                            .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.line, RoundedCornerShape(5.dp)).clickable(onClickLabel = "Material ${m.slot}") { s.slot = m.slot; p.changed() },
                            contentAlignment = Alignment.Center) { Txt("${m.slot}", Nz.type.label, if ((parseHex(m.colorHex)?.let { it.red + it.green + it.blue } ?: 0f) > 1.8f) Color(0xFF0D1114) else Color.White) }
                    }
                }
            }
            ObjectRow("Position (mm)") { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { ObjectNumber("X", s.x) { s.x = it; p.changed() }; ObjectNumber("Y", s.y) { s.y = it; p.changed() } } }
            ObjectRow("Turn and size") { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { ObjectNumber("°", s.rotZ) { s.rotZ = it; p.changed() }; ObjectNumber("%", s.scale * 100) { v -> if (v > 0) { s.scale = v / 100; p.changed() } } } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt("Duplicate", Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { p.duplicateSelected() }.padding(4.dp))
                Txt("Remove", Nz.type.label, c.danger, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { p.removeSelected() }.padding(4.dp))
            }
        }
        p.outOfBounds().takeIf { it.isNotEmpty() }?.let { Txt("Off the plate: ${it.joinToString { o -> o.name }}", Nz.type.bodySmall, c.danger) }
    }
}

@Composable
private fun ObjectRow(label: String, content: @Composable () -> Unit) = Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
    Txt(label, Nz.type.bodySmall, Nz.colors.textMuted, modifier = Modifier.weight(1f)); content()
}

@Composable
private fun ObjectNumber(units: String, value: Float, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf("%.1f".format(value).removeSuffix(".0")) }
    com.nozzleitall.desktop.settings.DenseInput(text, { t -> text = t; t.replace(',', '.').toFloatOrNull()?.let(onValue) }, units, Modifier.width(80.dp), units = units)
}

@Composable
private fun NumberField(label: String, value: Float, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf("%.1f".format(value).removeSuffix(".0")) }
    Field(label, text, { t -> text = t; t.replace(',', '.').toFloatOrNull()?.let(onValue) }, Modifier.width(84.dp))
}

@Composable
private fun StepSlice(state: AppState) {
    val p = state.prepare
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        when (val s = p.slice) {
            SliceState.Idle, SliceState.Cancelled, is SliceState.Failed -> {
                if (s is SliceState.Failed) Banner(s.message, BannerKind.DANGER)
                if (s is SliceState.Cancelled) Txt("Slicing was cancelled. Nothing was changed.", Nz.type.bodySmall, c.textMuted)
                NzButton("Slice", { p.slice(state.scope) }, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY, icon = NzIcon.SLICE, enabled = p.items.isNotEmpty(), testTag = "slice")
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
    val entry = p.printer() ?: run { Txt("No printer chosen: export the file and send it yourself, or choose a printer in step 1.", Nz.type.bodySmall, c.textMuted); return }
    val caps = entry.capabilities.value ?: run { Txt("Connecting to ${entry.config.identity.displayName}…", Nz.type.bodySmall, c.textMuted); return }
    val flow = rememberActionFlow(entry)
    var uploaded by remember(p.slice) { mutableStateOf<String?>(null) }
    var problem by remember(p.slice) { mutableStateOf<String?>(null) }
    val done = p.slice as? SliceState.Done ?: return
    ActionFlowUi(flow, entry)
    problem?.let { Banner(it, BannerKind.WARNING) }
    val status = entry.status.value
    val remoteName = p.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60) + ".gcode"
    when {
        // Everything below follows what this printer's adapter reports; nothing depends on who made it.
        "gcode" !in caps.acceptedOutputs -> Banner("${entry.config.identity.displayName} needs a ${caps.acceptedOutputs.joinToString(" or ")} file, which Nozzle It All for Desktop can't make yet. Export the file and use the printer maker's own app to send it.", BannerKind.INFO)
        caps.uploadJob -> if (uploaded == null) {
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
            val toolMap = if (caps.multiMaterial && p.materials().size > 1) p.materials().map { (it.toolhead ?: (it.slot - 1)) } else emptyList()
            if (caps.startPrint) NzButton("Start print", { flow.request(PrinterAction.StartJob(uploaded!!, toolMap)) }, kind = ButtonKind.PRIMARY, icon = NzIcon.PLAY,
                enabled = status.state in PrinterAction.StartJob("x").allowedStates && !flow.busy)
        }
        // Printers that receive a file and start it in one request: one confirmed action.
        caps.uploadAndStart -> NzButton("Send and print on ${entry.config.identity.displayName}", {
            flow.request(PrinterAction.UploadAndStart(done.result.gcode.absolutePath, remoteName))
        }, kind = ButtonKind.PRIMARY, icon = NzIcon.SEND, enabled = !flow.busy && status.state in PrinterAction.UploadAndStart("", "").allowedStates, testTag = "send")
        else -> Txt("${entry.config.identity.displayName} can't receive jobs from Nozzle It All. Export the file instead.", Nz.type.bodySmall, c.textMuted)
    }
    if (caps.anyControl || caps.camera) NzButton("Watch it print", { state.openPrinter(entry.config.identity.id) }, kind = ButtonKind.QUIET, icon = NzIcon.MONITOR)
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

/**
 * One slot's filament: the filament profile it slices with (the printer's own library), and for a slot Nozzle keeps
 * itself, its colour from that filament's catalogue colours (or any colour). A printer that reports what's loaded
 * decides the colour and material; only the profile is chosen here.
 */
@Composable
private fun SlotEditor(p: PrepareState, slot: com.nozzleitall.project.ProjectManifest.MaterialSlot, library: List<FilamentLibrary.Entry>,
                       fromPrinter: Boolean, onDone: () -> Unit) {
    val c = Nz.colors
    val current = library.firstOrNull { it.id == slot.filamentProfile }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("Slot ${slot.slot}", Nz.type.label)
            Spacer(Modifier.weight(1f))
            Txt("Done", Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onDone() }.padding(4.dp))
        }
        if (library.isEmpty()) Txt("This printer profile slices every slot with its own filament.", Nz.type.bodySmall, c.textMuted)
        else com.nozzleitall.desktop.settings.DenseSelect("Filament for slot ${slot.slot}",
            listOf(com.nozzleitall.desktop.settings.Choice("", "Printer default")) + library.map { com.nozzleitall.desktop.settings.Choice(it.id, it.displayName) },
            current?.id ?: "", Modifier.fillMaxWidth()) { id -> p.setSlotFilament(slot.slot, library.firstOrNull { it.id == id }) }
        if (fromPrinter) Txt("Colour and material are what the printer reports loaded.", Nz.type.bodySmall, c.textMuted)
        else {
            val swatches = current?.let { FilamentColours.forFamily(it.family) }.orEmpty()
            if (swatches.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                swatches.forEach { sw ->
                    val on = sw.hex.equals(slot.colorHex, true)
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(parseHex(sw.hex) ?: c.surface)
                        .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.line, RoundedCornerShape(5.dp))
                        .clickable(onClickLabel = sw.name.ifBlank { sw.hex }) { p.setSlotFilament(slot.slot, current, sw.hex) }
                        .semantics { contentDescription = "${sw.name} ${sw.hex}" })
                }
            }
            var hex by remember(slot.slot, slot.colorHex) { mutableStateOf(slot.colorHex ?: "") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("Colour", Nz.type.bodySmall, c.textMuted)
                com.nozzleitall.desktop.settings.DenseInput(hex, { t -> hex = t
                    if (Regex("#?[0-9A-Fa-f]{6}").matches(t.trim())) p.setSlotFilament(slot.slot, current, "#" + t.trim().removePrefix("#").uppercase()) },
                    "Colour for slot ${slot.slot}", Modifier.width(120.dp), placeholder = "#RRGGBB")
            }
        }
    }
}
