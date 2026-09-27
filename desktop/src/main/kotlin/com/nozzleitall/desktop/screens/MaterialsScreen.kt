package com.nozzleitall.desktop.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*

/** Common U1 material types and finishes, offered as quick picks; any text is still accepted. */
val materialTypes = listOf("PLA", "PETG", "ABS", "ASA", "TPU", "PVA", "PA", "PC")
val materialFinishes = listOf("Basic", "Matte", "Silk", "SnapSpeed", "Support", "HF", "95A")

@Composable
fun MaterialsScreen(state: AppState) {
    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeader("Materials & Toolheads", "What's loaded in each toolhead, as the printer reports it.")
        val entry = PrinterPicker(state) ?: run { NoPrinterYet(state, "what's loaded in each toolhead"); return@Column }
        val status = entry.status.value
        val caps = entry.capabilities.value
        val flow = rememberActionFlow(entry)
        ActionFlowUi(flow, entry)
        if (status.toolheads.isEmpty()) {
            Card(Modifier.fillMaxWidth()) { EmptyState(NzIcon.TOOLHEAD, "No toolhead information yet",
                if (status.state == PrinterState.OFFLINE) "${entry.config.identity.displayName} is offline. Toolheads appear once it's connected." else "This printer hasn't reported its toolheads.") }
            return@Column
        }
        var editing by remember(entry) { mutableStateOf<Int?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            status.toolheads.forEach { t -> ToolheadCard(t, caps, status, flow, Modifier.weight(1f)) { editing = t.index } }
        }
        editing?.let { index -> MaterialEditor(entry, status.toolheads.first { it.index == index }, flow) { editing = null } }
        Card(Modifier.fillMaxWidth()) {
            Txt("How materials reach your prints", Nz.type.title)
            Txt("When you prepare a project for this printer, Nozzle matches each colour in your project to the toolhead loaded with the closest material. " +
                "Tag-read spools are shown as reported; you can override them when the printer allows it.", Nz.type.body, Nz.colors.textMuted)
        }
    }
}

@Composable
private fun ToolheadCard(t: Toolhead, caps: Capabilities?, status: PrinterStatus, flow: ActionFlow, modifier: Modifier, onEdit: () -> Unit) {
    val c = Nz.colors
    val color = parseHex(t.material?.colorHex)
    Card(modifier, raised = t.active) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(NzIcon.TOOLHEAD, if (t.active) c.accent else c.textMuted, 22.dp)
            Txt("Toolhead ${t.index + 1}", Nz.type.title, modifier = Modifier.weight(1f))
            if (t.active) Txt("Active", Nz.type.label, c.accent)
        }
        Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(color ?: c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Loaded colour ${t.material?.colorHex ?: "unknown"}" }, contentAlignment = Alignment.Center) {
            if (!t.loaded) Txt("Empty", Nz.type.body, c.textMuted)
        }
        Txt(if (t.loaded) t.material?.label ?: "Unknown material" else "No material loaded", Nz.type.body)
        if (t.material?.fromTag == true) Txt("Read from the spool's tag", Nz.type.bodySmall, c.textMuted)
        Txt("Nozzle ${temp(t.nozzleTemperature)}${t.nozzleDiameterMm?.let { " · %.1f mm".format(it) } ?: ""}", Nz.type.metricSmall, c.textMuted)
        val blocked = flow.busy
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (caps?.materialEdit == true) NzButton("Set material", onEdit, kind = ButtonKind.SECONDARY, enabled = !blocked && status.state in PrinterAction.SetMaterialInfo(t.index, Material()).allowedStates)
            if (caps?.materials == true) {
                NzButton("Load", { flow.request(PrinterAction.LoadMaterial(t.index)) }, kind = ButtonKind.QUIET, enabled = !blocked && status.state in PrinterAction.LoadMaterial(0).allowedStates)
                NzButton("Unload", { flow.request(PrinterAction.UnloadMaterial(t.index)) }, kind = ButtonKind.QUIET, enabled = !blocked && t.loaded && status.state in PrinterAction.UnloadMaterial(0).allowedStates)
            }
        }
    }
}

@Composable
private fun MaterialEditor(entry: PrinterEntry, t: Toolhead, flow: ActionFlow, onClose: () -> Unit) {
    val c = Nz.colors
    var vendor by remember(t) { mutableStateOf(t.material?.vendor ?: "") }
    var type by remember(t) { mutableStateOf(t.material?.type ?: "PLA") }
    var finish by remember(t) { mutableStateOf(t.material?.subType ?: "Basic") }
    var color by remember(t) { mutableStateOf(t.material?.colorHex ?: "#FFFFFF") }
    val valid = parseHex(color) != null
    Card(Modifier.fillMaxWidth(), raised = true) {
        Txt("Set material for toolhead ${t.index + 1}", Nz.type.title)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Brand", vendor, { vendor = it }, Modifier.weight(1f), placeholder = "Polymaker")
            Field("Colour", color, { color = it.take(7) }, Modifier.width(140.dp), placeholder = "#RRGGBB", error = if (valid) null else "Use #RRGGBB")
            Box(Modifier.padding(top = 26.dp).size(40.dp).clip(CircleShape).background(parseHex(color) ?: c.surfaceSunken).border(1.dp, c.lineStrong, CircleShape))
        }
        Txt("Type", Nz.type.label)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { materialTypes.forEach { m -> NzButton(m, { type = m }, kind = if (type == m) ButtonKind.PRIMARY else ButtonKind.SECONDARY) } }
        Txt("Finish", Nz.type.label)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { materialFinishes.forEach { m -> NzButton(m, { finish = m }, kind = if (finish == m) ButtonKind.PRIMARY else ButtonKind.SECONDARY) } }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NzButton("Save to printer", { flow.request(PrinterAction.SetMaterialInfo(t.index, Material(vendor.ifBlank { null }, type, finish, color.uppercase(), false))); onClose() },
                kind = ButtonKind.PRIMARY, enabled = valid)
            NzButton("Cancel", onClose, kind = ButtonKind.QUIET)
        }
    }
}
