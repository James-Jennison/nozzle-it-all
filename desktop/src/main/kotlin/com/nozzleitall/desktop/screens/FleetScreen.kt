package com.nozzleitall.desktop.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.design.NozzleTokens
import com.nozzleitall.printer.*

fun parseHex(hex: String?): Color? = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

fun formatDuration(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds < 0) return "–"
    val m = (seconds / 60).toLong()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

fun temp(t: Double?) = t?.let { "%.0f °C".format(it) } ?: "–"

@Composable
fun FleetScreen(state: AppState) {
    val fleet = state.fleet
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeader("Printers", "Everything you print on, at a glance.") {
            NzButton("Add printer", { adding = true }, kind = ButtonKind.PRIMARY, icon = NzIcon.ADD, testTag = "add-printer")
        }
        val ids = fleet.order.value
        if (ids.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                EmptyState(NzIcon.FLEET, "Add your first printer",
                    "Nozzle It All talks to your printers on your own network. A Snapmaker U1 with PAXX works fully offline: no account, no cloud. " +
                        "Away from home? Connect through your own private network, such as Tailscale.") {
                    NzButton("Add printer", { adding = true }, kind = ButtonKind.PRIMARY, icon = NzIcon.ADD)
                }
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(340.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(ids, key = { it }) { id -> fleet.printers[id]?.let { PrinterCard(it) { state.openPrinter(id) } } }
            }
        }
    }
    if (adding) AddPrinterDialog(state) { adding = false }
}

@Composable
fun PrinterCard(entry: PrinterEntry, onOpen: () -> Unit) {
    val c = Nz.colors
    val status = entry.status.value
    val identity = entry.config.identity
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(NozzleTokens.Radius.panel)
    val stateColor = Nz.status.forState(status.state.glossaryId)
    Column(Modifier.clip(shape).background(c.surface).border(1.dp, c.line, shape).focusRing(focused, c.focus, shape)
        .clickable(interaction, null, role = Role.Button, onClickLabel = "Open ${identity.displayName}", onClick = onOpen)
        .semantics(mergeDescendants = true) { contentDescription = "${identity.displayName}, ${status.state.label}" }) {
        // A thin band in the state colour: readable at a distance across a room of printers.
        Box(Modifier.fillMaxWidth().height(4.dp).background(stateColor))
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(identity.displayName.ifBlank { identity.address }, Nz.type.title, maxLines = 1)
                    Txt("${identity.model} · ${identity.firmware.label}", Nz.type.bodySmall, c.textMuted)
                }
                StatusPill(status.state)
            }
            val job = status.job
            if (job != null) {
                Txt(job.fileName, Nz.type.body, maxLines = 1)
                ProgressBar(job.fraction, stateColor, label = "Print progress")
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Metric("Done", "${(job.fraction * 100).toInt()}%")
                    Metric("Elapsed", formatDuration(job.elapsedSeconds))
                    job.totalLayers?.let { Metric("Layer", "${job.currentLayer ?: 0} / $it") }
                }
            } else {
                Txt(status.message ?: entry.problem.value ?: status.state.description, Nz.type.body, c.textMuted, maxLines = 2)
            }
            if (status.toolheads.isNotEmpty()) ToolheadStrip(status.toolheads)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RouteBadge(status.route)
                Spacer(Modifier.weight(1f))
                status.bed?.current?.let { Txt("Bed ${temp(it)}", Nz.type.metricSmall, c.textMuted) }
            }
        }
    }
}

/** The U1's four toolheads as the colours actually loaded, numbered from 1, active one outlined. */
@Composable
fun ToolheadStrip(heads: List<Toolhead>) {
    val c = Nz.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        heads.forEach { t ->
            val color = parseHex(t.material?.colorHex)
            val desc = "Toolhead ${t.index + 1}: " + (if (t.loaded) t.material?.label ?: "loaded" else "empty") + if (t.active) ", active" else ""
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(c.surfaceRaised).border(if (t.active) 2.dp else 1.dp, if (t.active) c.accent else c.line, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp).semantics(mergeDescendants = true) { contentDescription = desc },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt("${t.index + 1}", Nz.type.label, c.textMuted)
                Box(Modifier.size(14.dp).clip(CircleShape).background(color ?: c.surfaceSunken).border(1.dp, if (t.loaded) c.lineStrong else c.line, CircleShape))
                Txt(t.material?.type ?: if (t.loaded) "?" else "Empty", Nz.type.bodySmall, if (t.loaded) c.text else c.textMuted)
            }
        }
    }
}
