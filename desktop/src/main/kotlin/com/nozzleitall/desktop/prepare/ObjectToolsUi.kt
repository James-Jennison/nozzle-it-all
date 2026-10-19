package com.nozzleitall.desktop.prepare

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.settings.Choice
import com.nozzleitall.desktop.settings.DenseInput
import com.nozzleitall.desktop.settings.DenseRow
import com.nozzleitall.desktop.settings.DenseSelect
import com.nozzleitall.desktop.settings.ProfileValues
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.project.ProjectManifest

private fun number(v: Double) = "%.2f".format(v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

/** A small number box that edits on the go; [onValue] gets only parseable input. */
@Composable
private fun Num(label: String, value: Double, width: Int = 64, units: String? = null, onValue: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(number(value)) }
    DenseInput(text, { t -> text = t; t.replace(',', '.').toDoubleOrNull()?.let(onValue) }, label, Modifier.width(width.dp), units = units)
}

/**
 * Orca's object manipulation panel beyond position and turn: the size on each axis (unlocked scale) and mirroring.
 * Mirroring an axis negates that axis's size; the engine takes the sizes and the mirror from the object's placement.
 */
@Composable
fun ObjectSizeSection(p: PrepareState, item: PrepItem) {
    val c = Nz.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt("Size on each axis", Nz.type.label)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("X" to item.sx, "Y" to item.sy, "Z" to item.sz).forEachIndexed { axis, (name, factor) ->
                Num(name, kotlin.math.abs(factor.toDouble()) * item.scale * 100, units = "%") { v ->
                    if (v > 0) { val rel = (v / 100 / item.scale).toFloat() * (if (factor < 0) -1f else 1f)
                        when (axis) { 0 -> item.sx = rel; 1 -> item.sy = rel; else -> item.sz = rel }; p.changed() }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Mirror", Nz.type.bodySmall, c.textMuted)
            listOf("X" to item.sx, "Y" to item.sy, "Z" to item.sz).forEachIndexed { axis, (name, factor) ->
                val on = factor < 0
                Txt(name, Nz.type.label, if (on) c.accent else c.text, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "Mirror along $name") {
                        when (axis) { 0 -> item.sx = -item.sx; 1 -> item.sy = -item.sy; else -> item.sz = -item.sz }; p.changed() }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

private val partKinds = listOf("negative" to "Negative volume", "modifier" to "Modifier", "blocker" to "Support blocker", "enforcer" to "Support enforcer", "part" to "Part")
private val partShapes = listOf("box" to "Box", "cylinder" to "Cylinder", "sphere" to "Sphere")

/**
 * The object's extra volumes (Orca's "Add part / negative volume / modifier / support blocker / support enforcer"): a box, cylinder or
 * sphere with a position and size. A modifier (or part) carries settings that apply inside it only.
 */
@Composable
fun ObjectPartsSection(p: PrepareState, item: PrepItem, profile: ProfileValues) {
    val c = Nz.colors
    val byKey = remember { ObjectSettings.all().associateBy { it.key } }
    val categories = remember { ObjectSettings.categories() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt("Parts and modifiers", Nz.type.label)
        if (item.parts.isEmpty()) Txt("Cut holes, block or force supports, or change settings inside a region.", Nz.type.bodySmall, c.textMuted)
        item.parts.forEachIndexed { i, part ->
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt("${partKinds.first { it.first == part.kind }.second} · ${partShapes.firstOrNull { it.first == part.shape }?.second ?: part.shape}", Nz.type.bodySmall, modifier = Modifier.weight(1f))
                    Txt("Remove", Nz.type.label, c.danger, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Remove this part") { p.removePart(item, i) }.padding(4.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("At", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(24.dp))
                    for (axis in 0..2) Num("xyz"[axis].uppercase(), part.center[axis], 60) { v -> p.updatePart(item, i, part.copy(center = part.center.toMutableList().also { it[axis] = v })) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("Size", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(24.dp))
                    for (axis in 0..2) Num("xyz"[axis].uppercase(), part.size[axis], 60) { v -> if (v > 0) p.updatePart(item, i, part.copy(size = part.size.toMutableList().also { it[axis] = v })) }
                }
                if (part.kind == "modifier" || part.kind == "part") {
                    part.settings.keys.sorted().forEach { key ->
                        val def = byKey[key] ?: return@forEach
                        DenseRow(def, part.settings[key], item.settings[key] ?: p.setting(key, profile), true) { v ->
                            p.updatePart(item, i, part.copy(settings = if (v == null) part.settings - key else part.settings + (key to v)))
                        }
                    }
                    val choices = categories.flatMap { (cat, defs) -> defs.filter { it.key !in part.settings }.map { Choice(it.key, "$cat · ${it.label}") } }
                    if (choices.isNotEmpty()) DenseSelect("Add a setting for this ${part.kind}", listOf(Choice("", "Add a setting…")) + choices, "", Modifier.fillMaxWidth()) { key ->
                        val def = byKey[key] ?: return@DenseSelect
                        p.updatePart(item, i, part.copy(settings = part.settings + (key to (item.settings[key] ?: p.setting(key, profile) ?: def.default ?: ""))))
                    }
                }
            }
        }
        var kind by remember { mutableStateOf("negative") }; var shape by remember { mutableStateOf("box") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            DenseSelect("Kind of part", partKinds.map { Choice(it.first, it.second) }, kind, Modifier.weight(1f)) { kind = it }
            DenseSelect("Shape", partShapes.map { Choice(it.first, it.second) }, shape, Modifier.width(110.dp)) { shape = it }
            NzButton("Add", { p.addPart(item, kind, shape) }, kind = ButtonKind.SECONDARY, testTag = "add-part")
        }
    }
}

/** Height range modifiers (Orca's "Height range Modifier"): settings, usually a layer height, for the layers between two heights. */
@Composable
fun HeightRangesSection(p: PrepareState, item: PrepItem, profile: ProfileValues) {
    val c = Nz.colors
    val byKey = remember { ObjectSettings.all().associateBy { it.key } }
    val categories = remember { ObjectSettings.categories() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt("Height ranges", Nz.type.label)
        if (item.ranges.isEmpty()) Txt("Print some layers of this object with different settings.", Nz.type.bodySmall, c.textMuted)
        item.ranges.forEachIndexed { i, range ->
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Num("From", range.from, 70, "mm") { v -> if (v >= 0) p.updateRange(item, i, range.copy(from = v)) }
                    Num("To", range.to, 70, "mm") { v -> if (v > range.from) p.updateRange(item, i, range.copy(to = v)) }
                    Spacer(Modifier.weight(1f))
                    Txt("Remove", Nz.type.label, c.danger, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Remove this height range") { p.removeRange(item, i) }.padding(4.dp))
                }
                range.settings.keys.sorted().forEach { key ->
                    val def = byKey[key] ?: return@forEach
                    DenseRow(def, range.settings[key], item.settings[key] ?: p.setting(key, profile), true) { v ->
                        p.updateRange(item, i, range.copy(settings = if (v == null) range.settings - key else range.settings + (key to v)))
                    }
                }
                val choices = categories.flatMap { (cat, defs) -> defs.filter { it.key !in range.settings }.map { Choice(it.key, "$cat · ${it.label}") } }
                if (choices.isNotEmpty()) DenseSelect("Add a setting for this range", listOf(Choice("", "Add a setting…")) + choices, "", Modifier.fillMaxWidth()) { key ->
                    val def = byKey[key] ?: return@DenseSelect
                    p.updateRange(item, i, range.copy(settings = range.settings + (key to (item.settings[key] ?: p.setting(key, profile) ?: def.default ?: ""))))
                }
            }
        }
        NzButton("Add height range", { p.addRange(item) }, kind = ButtonKind.QUIET, testTag = "add-height-range")
    }
}

/** Variable layer height (Orca's layer editing): the adaptive profile from the engine, how fine it ended up, and clearing it. */
@Composable
fun VariableLayerSection(p: PrepareState, item: PrepItem) {
    val c = Nz.colors
    var quality by remember(item.id) { mutableStateOf(0.5) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt("Variable layer height", Nz.type.label)
        val heights = item.layerProfile.filterIndexed { i, _ -> i % 2 == 1 }
        if (heights.isEmpty()) Txt("Layers are as tall as the process says. Adaptive makes them finer where the surface is shallow.", Nz.type.bodySmall, c.textMuted)
        else Txt("${number(heights.min())}–${number(heights.max())} mm layers", Nz.type.bodySmall, c.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Num("Quality 0–1", quality, 90) { quality = it.coerceIn(0.0, 1.0) }
            NzButton("Adaptive", { p.adaptiveLayers(item, quality) }, kind = ButtonKind.SECONDARY, enabled = p.toolBusy == null, testTag = "adaptive-layers")
            if (heights.isNotEmpty()) NzButton("Clear", { p.clearLayerProfile(item) }, kind = ButtonKind.QUIET)
        }
        // Orca's warning for the combination (the engine's rule, shown while it applies).
        p.ruleChecks?.notices?.firstOrNull { it.id == "mixed_sublayer_variable_layer" }?.let { Txt(it.message, Nz.type.bodySmall, c.danger) }
    }
}
