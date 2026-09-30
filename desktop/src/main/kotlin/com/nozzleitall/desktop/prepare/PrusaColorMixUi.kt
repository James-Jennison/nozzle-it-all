package com.nozzleitall.desktop.prepare

import com.nozzleitall.printer.ext.PrusaColorMixFormat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.screens.parseHex
import com.nozzleitall.desktop.settings.Choice
import com.nozzleitall.desktop.settings.DenseInput
import com.nozzleitall.desktop.settings.DenseSelect
import com.nozzleitall.desktop.settings.IconToggle
import com.nozzleitall.desktop.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.relocation.bringIntoViewRequester

/**
 * PrusaSlicer's Color mix in the Filament card (multi-slot printers without Full Spectrum): the virtual extruders listed as
 * "[V] Extruder N" after the physical ones, each in the colour PrusaSlicer predicts, with "Add blend", editing and
 * removal as PrusaSlicer's Color Mixing dialog does them. Values come from PrusaSlicer 2.9.6's code in the engine.
 */
@Composable
fun PrusaColorMixSection(p: PrepareState) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    val physical = p.materials()
    LaunchedEffect(physical.map { it.colorHex }) { p.refreshColorMix(this) }
    var editing by remember { mutableStateOf<Int?>(null) } // a virtual id, or 0 for a new blend
    var confirmDelete by remember { mutableStateOf<PrusaColorMixFormat.Virtual?>(null) }

    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Color mix", Nz.type.label, c.text)
        Txt("Virtual extruders", Nz.type.bodySmall, c.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
        IconToggle(NzIcon.ADD, "Add blend", editing == 0) { editing = if (editing == 0) null else 0 }
    }
    if (p.colorMix.isEmpty() && editing == null)
        Txt("Blend two or three loaded filaments into a new colour, printed as a repeating layer cycle.", Nz.type.bodySmall, c.textMuted)
    p.colorMix.forEach { v ->
        Row(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(6.dp)).background(c.surfaceSunken)
            .border(1.dp, if (editing == v.id) c.accent else c.line, RoundedCornerShape(6.dp))
            .clickable(onClickLabel = "Edit virtual extruder ${v.id}") { editing = if (editing == v.id) null else v.id }
            .semantics(mergeDescendants = true) { contentDescription = "Virtual extruder ${v.id}: ${v.summary}" }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlotBadge(v.id, v.colorOverride ?: v.effectiveHex)
            Txt("[V] Extruder ${v.id}", Nz.type.bodySmall)
            Txt(v.summary, Nz.type.bodySmall, c.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
            Txt("✕", Nz.type.label, c.textMuted, modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .clickable(onClickLabel = "Remove virtual extruder ${v.id}") { confirmDelete = v }.padding(horizontal = 8.dp))
        }
    }
    confirmDelete?.let { v ->
        // PrusaSlicer asks before removing one objects use; they go back to the default extruder.
        val used = p.usedSlots().contains(v.id)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surfaceSunken).border(1.dp, c.danger, RoundedCornerShape(8.dp)).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(if (used) "Virtual extruder ${v.id} is in use. Objects and painted areas that use it will print with the default extruder."
                else "Remove virtual extruder ${v.id}?", Nz.type.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                NzButton("Cancel", { confirmDelete = null }, kind = ButtonKind.SECONDARY)
                NzButton("Remove", {
                    p.removeVirtualExtruder(v.id); confirmDelete = null; if (editing == v.id) editing = null
                }, kind = ButtonKind.PRIMARY)
            }
        }
    }
    editing?.let { id ->
        val existing = p.colorMix.firstOrNull { it.id == id }
        if (existing != null && !existing.isBlend) Txt("Virtual extruder ${existing.id} is a gradient from its file (${existing.summary}); PrusaSlicer edits gradients only in the file.",
            Nz.type.bodySmall, c.textMuted)
        else BlendEditor(p, existing, physical) { editing = null }
    }
}

/**
 * A blend, as PrusaSlicer's Color Mixing dialog edits it: two extruders on a ratio bar (5 % steps, 5-95) or three on a
 * triangle (5 % steps, snapping to 33/33/34 near the centre); a display colour that overrides the predicted one until
 * "Reset to blended" (any recipe change resets it too); the layer sequence it prints; and the preset palette.
 */
@Composable
private fun BlendEditor(p: PrepareState, v: PrusaColorMixFormat.Virtual?, physical: List<com.nozzleitall.project.ProjectManifest.MaterialSlot>, onDone: () -> Unit) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    val typed = physical.map { (it.colorHex ?: "#FFFFFF") to it.type }
    val extruders = remember(v?.id) { mutableStateListOf<Int>().apply { addAll(v?.components?.map { it.extruder } ?: listOf(1, 2)) } }
    var ratios by remember(v?.id) { mutableStateOf(v?.components?.map { it.value } ?: listOf(0.5, 0.5)) }
    var colour by remember(v?.id) { mutableStateOf(v?.colorOverride) }
    var result by remember { mutableStateOf<PrusaColorMixFormat.Virtual?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var presets by remember { mutableStateOf<List<PrusaColorMixFormat.Preset>>(emptyList()) }
    val filter = remember { mutableStateListOf<Int>() } // presets: only those using these extruders
    fun balanced(n: Int) = List(n) { i -> if (i == n - 1) 1.0 - (n - 1) * Math.round(100.0 / n) / 100.0 else Math.round(100.0 / n) / 100.0 }
    fun draft(id: Int) = PrusaColorMixFormat.Virtual(id, "fullspectrum", extruders.zip(ratios).map { (e, r) -> PrusaColorMixFormat.Component(e, r) }, colour)
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(v?.id) { kotlinx.coroutines.delay(50); bring.bringIntoView() }
    LaunchedEffect(extruders.toList(), ratios, colour) {
        kotlinx.coroutines.delay(150)
        val id = v?.id ?: runCatching { withContext(Dispatchers.IO) { PrusaColorMix.nextId(physical.size, p.colorMix.toList()) } }.getOrElse { physical.size + 1 }
        runCatching { withContext(Dispatchers.IO) { PrusaColorMix.normalize(typed, listOf(draft(id))) } }
            .onSuccess { result = it.firstOrNull(); problem = if (it.isEmpty()) "PrusaSlicer won't accept this blend (it needs 2-3 different extruders)." else null }
            .onFailure { result = null; problem = it.message }
    }
    LaunchedEffect(physical.map { it.colorHex }) { presets = runCatching { withContext(Dispatchers.IO) { PrusaColorMix.presets(typed) } }.getOrDefault(emptyList()) }

    val choices = physical.map { s -> Choice(s.slot.toString(), "E${s.slot} · ${listOfNotNull(s.vendor, s.type).joinToString(" ").ifBlank { "Filament" }}") }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(bring).clip(RoundedCornerShape(8.dp)).background(c.surfaceSunken)
        .border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(if (v == null) "New blend" else "[V] Extruder ${v.id}", Nz.type.label)
        extruders.forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlotBadge(e, p.slotHex(e), 22.dp)
                DenseSelect("Extruder ${i + 1}", choices.filter { it.value == e.toString() || it.value.toInt() !in extruders }, e.toString(), Modifier.weight(1f)) { s ->
                    s.toIntOrNull()?.let { extruders[i] = it; colour = null } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (extruders.size < 3 && physical.size >= 3) Txt("Add third extruder", Nz.type.label, c.accent, modifier = Modifier.clickable {
                physical.map { it.slot }.firstOrNull { it !in extruders }?.let { extruders.add(it); ratios = balanced(3); colour = null } })
            if (extruders.size == 3) Txt("Remove third extruder", Nz.type.label, c.accent, modifier = Modifier.clickable {
                extruders.removeAt(2); ratios = listOf(0.5, 0.5); colour = null })
        }
        if (extruders.size == 2) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val b = Math.round(ratios[1] * 100).toInt()
            Txt("${100 - b}%", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(36.dp))
            RatioBar(b, parseHex(p.slotHex(extruders[0])) ?: c.accent, parseHex(p.slotHex(extruders[1])) ?: c.accent, Modifier.weight(1f), 5, 95, 5) {
                ratios = listOf((100 - it) / 100.0, it / 100.0); colour = null }
            Txt("$b%", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(36.dp))
        } else if (extruders.size == 3) TrianglePicker(extruders.map { parseHex(p.slotHex(it)) ?: c.accent }, ratios.map { it.toFloat() }, 0.05f) { w ->
            // BarycentricRatioPicker: 5 % steps, and 33/33/34 near the centre.
            val near = w.all { kotlin.math.abs(it - 1f / 3f) < 0.04f }
            ratios = if (near) listOf(0.33, 0.33, 0.34) else {
                val a = Math.round(w[0] * 20) * 5; val b = Math.round(w[1] * 20) * 5
                listOf(a / 100.0, b / 100.0, (100 - a - b).coerceAtLeast(5) / 100.0)
            }
            colour = null
        }
        // The colour it reads as (or the chosen display colour), and the repeating layer order it prints.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("Display color", Nz.type.bodySmall, c.textMuted)
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(parseHex(colour ?: result?.effectiveHex) ?: c.surface).border(1.dp, c.line, RoundedCornerShape(5.dp)))
            var hex by remember(colour, result?.effectiveHex) { mutableStateOf((colour ?: result?.effectiveHex ?: "").removePrefix("#")) }
            Txt("#", Nz.type.bodySmall, c.textMuted)
            DenseInput(hex, { t -> hex = t.take(6); if (Regex("[0-9A-Fa-f]{6}").matches(hex)) colour = "#" + hex.lowercase() }, "Display colour", Modifier.width(90.dp))
            if (colour != null) Txt("Reset to blended", Nz.type.label, c.accent, modifier = Modifier.clickable { colour = null })
        }
        result?.cycle?.takeIf { it.isNotEmpty() }?.let { cycle ->
            Txt("Layer sequence preview", Nz.type.bodySmall, c.textMuted)
            val cells = List(maxOf(28, cycle.size)) { cycle[it % cycle.size] }
            Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(3.dp))) {
                cells.forEach { e -> Box(Modifier.weight(1f).fillMaxHeight().background(parseHex(p.slotHex(e)) ?: c.surface)) }
            }
        }
        problem?.let { Txt(it, Nz.type.bodySmall, c.danger) }
        if (presets.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt("Presets", Nz.type.bodySmall, c.textMuted)
                physical.forEach { s -> val on = s.slot in filter
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).border(2.dp, if (on) c.accent else Color.Transparent, RoundedCornerShape(6.dp)).padding(1.dp)
                        .clickable(onClickLabel = if (on) "Don't limit presets to E${s.slot}" else "Only presets with E${s.slot}") { if (on) filter.remove(s.slot) else filter.add(s.slot) }) {
                        SlotBadge(s.slot, s.colorHex, 18.dp) } }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.filter { pr -> filter.all { f -> pr.components.any { it.extruder == f } } }.take(48).forEach { pr ->
                    val tip = pr.components.joinToString(" + ") { "E${it.extruder} ${Math.round(it.value * 100)}%" }
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(parseHex(pr.hex) ?: c.surface).border(1.dp, c.line, RoundedCornerShape(5.dp))
                        .clickable(onClickLabel = tip) { extruders.clear(); extruders.addAll(pr.components.map { it.extruder }); ratios = pr.components.map { it.value }; colour = null }
                        .semantics { contentDescription = tip })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            NzButton("Cancel", onDone, kind = ButtonKind.SECONDARY)
            NzButton("OK", {
                result?.let { r -> p.saveVirtualExtruder(r.copy(colorOverride = colour), scope) }; onDone()
            }, kind = ButtonKind.PRIMARY, enabled = result != null && problem == null)
        }
    }
}
