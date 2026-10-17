package com.nozzleitall.desktop.prepare

import com.nozzleitall.printer.ext.MixedFilamentFormat
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.nozzleitall.desktop.settings.Choice
import com.nozzleitall.desktop.settings.DenseSelect
import com.nozzleitall.desktop.settings.IconToggle
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.desktop.screens.parseHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Readable ink on a swatch of colour [c]. */
private fun inkOn(c: Color) = if (c.red * 0.299f + c.green * 0.587f + c.blue * 0.114f > 0.6f) Color(0xFF0D1114) else Color.White

/** A numbered slot badge in its colour: loaded filaments and mixes look the same, so a mix reads as just another slot. */
@Composable
fun SlotBadge(id: Int, hex: String?, size: androidx.compose.ui.unit.Dp = 30.dp) {
    val colour = parseHex(hex) ?: Nz.colors.surfaceSunken
    Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(colour), contentAlignment = Alignment.Center) { Txt("$id", Nz.type.label, inkOn(colour)) }
}

/**
 * Mixed filaments in the Filament card (OrcaSlicer 2.5.0's own): each one is a numbered slot after the loaded filaments
 * (5, 6 ... on a four-slot printer) with its blended colour and label ("F1 70% + F2 30%"); add, adjust and remove; and
 * "Match colours", which finds the loaded filament or mix closest to each colour of a painted model. Every colour, label
 * and recipe comes from Orca's code in the engine.
 */
@Composable
fun ColourMixingSection(p: PrepareState) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    val physical = p.materials()
    LaunchedEffect(physical.map { it.colorHex }, p.mixed.size, p.mixSublayers) { p.refreshMixes(this) }
    var editing by remember { mutableStateOf<Int?>(null) } // a mixed row index, or -1 for a new one
    var matching by remember { mutableStateOf<PrepItem?>(null) }

    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Colour mixing", Nz.type.label, c.text)
        Txt("Mixed filaments", Nz.type.bodySmall, c.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
        val painted = p.items.firstOrNull { it.id == p.selected && it.painted.isNotEmpty() } ?: p.items.firstOrNull { it.painted.isNotEmpty() }
        if (painted != null) Txt("Match colours", Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .clickable(onClickLabel = "Match the model's colours with the loaded filaments and mixes") { matching = painted }.padding(4.dp))
        IconToggle(NzIcon.ADD, "Add a mix", editing == -1) { editing = if (editing == -1) null else -1 }
    }
    p.mixProblem?.let { Txt(it, Nz.type.bodySmall, c.danger) }
    if (p.mixed.isEmpty() && editing == null)
        Txt("Mix loaded filaments into a new colour, printed in thin layers that blend, or as a gradient up the model.", Nz.type.bodySmall, c.textMuted)
    p.mixed.withIndex().toList().chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { (i, m) ->
                Row(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(6.dp)).background(c.surfaceSunken).border(1.dp, if (editing == i) c.accent else c.line, RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "Adjust mix ${p.mixedSlot(i)}") { editing = if (editing == i) null else i }
                    .semantics(mergeDescendants = true) { contentDescription = "Mix ${p.mixedSlot(i)}: ${m.label}" }, verticalAlignment = Alignment.CenterVertically) {
                    SlotBadge(p.mixedSlot(i), m.hex)
                    Txt((if (m.gradient.enabled) "Gradient " else "") + m.label, Nz.type.bodySmall, modifier = Modifier.padding(horizontal = 8.dp).weight(1f), maxLines = 1)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    p.mixed.forEachIndexed { i, m -> m.problems.firstOrNull()?.let { Txt("Mix ${p.mixedSlot(i)}: $it", Nz.type.bodySmall, c.heat) } }
    if (p.mixed.any { it.gradient.enabled }.not() && p.mixed.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Blend by", Nz.type.bodySmall, c.textMuted)
        DenseSelect("Blend by", listOf(Choice("sublayer", "Splitting layers"), Choice("alternate", "Alternating layers")), if (p.mixSublayers) "sublayer" else "alternate", Modifier.weight(1f)) {
            p.mixSublayers = it == "sublayer"; p.changed() }
    }
    editing?.let { i -> MixEditor(p, p.mixed.getOrNull(i), i, physical) { editing = null } }
    matching?.let { item -> ColourMatchWindow(p, item) { matching = null } }
}

/**
 * The mix editor: two or three loaded filaments and their shares (a bar for two, a triangle for three), or for two a
 * gradient from the bottom of the model to its top. It previews the colour the engine blends, and offers preset swatches
 * (every pair of loaded filaments at 25, 50 and 75 %), or a recipe found for a target colour.
 */
@Composable
private fun MixEditor(p: PrepareState, mix: MixedFilamentFormat.Row?, index: Int, physical: List<com.nozzleitall.project.ProjectManifest.MaterialSlot>, onDone: () -> Unit) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    val typed = physical.map { (it.colorHex ?: "#FFFFFF") to it.type }
    val ids = physical.map { it.slot }
    val filaments = remember(index) { mutableStateListOf<Int>().apply { addAll(mix?.components?.take(3)?.map { it } ?: ids.take(2)) } }
    var share by remember(index) { mutableStateOf(mix?.takeIf { it.components.size == 2 }?.let { r -> (r.weights[1] * 100 / (r.weights.sum().takeIf { it > 0 } ?: 1)).coerceIn(10, 90) } ?: 50) }
    var tri by remember(index) { mutableStateOf(mix?.weights?.takeIf { it.size == 3 }?.let { w -> val t = w.sum().toFloat().takeIf { it > 0 } ?: 1f; w.map { it / t } } ?: listOf(1 / 3f, 1 / 3f, 1 / 3f)) }
    var gradient by remember(index) { mutableStateOf(mix?.gradient?.enabled == true) }
    // A gradient's range, as the first filament's share at the bottom and at the top (Orca's "0.9,0.1" text).
    val rangeNow = mix?.gradient?.range?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 }
    var bottom by remember(index) { mutableStateOf(((rangeNow?.get(0) ?: 0.9) * 100).toInt().coerceIn(0, 100)) }
    var top by remember(index) { mutableStateOf(((rangeNow?.get(1) ?: 0.1) * 100).toInt().coerceIn(0, 100)) }
    var target by remember(index) { mutableStateOf(mix?.hex ?: "#808080") }
    var matchInfo by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<MixedFilamentFormat.Row?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var presets by remember { mutableStateOf<List<MixedFilamentFormat.Row>>(emptyList()) }

    fun row(): MixedFilamentFormat.Row {
        val weights = when {
            gradient || filaments.size == 2 -> listOf(100 - share, share)
            else -> tri.map { Math.round(it * 100).toInt().coerceAtLeast(1) }
        }
        val comps = if (gradient) filaments.take(2) else filaments.toList()
        val grad = if (gradient) MixedFilamentFormat.Gradient(true, "%.2f,%.2f".format(java.util.Locale.ROOT, bottom / 100.0, top / 100.0)) else MixedFilamentFormat.Gradient()
        return MixedFilamentFormat.Row(comps, if (gradient) listOf(50, 50) else weights, grad)
    }
    LaunchedEffect(filaments.toList(), share, tri, gradient, bottom, top) {
        kotlinx.coroutines.delay(150)
        runCatching { withContext(Dispatchers.IO) { MixedFilament.display(typed, listOf(row())).single() } }
            .onSuccess { preview = it; problem = it.problems.firstOrNull() }.onFailure { preview = null; problem = it.message }
    }
    LaunchedEffect(Unit) { presets = runCatching { withContext(Dispatchers.IO) { MixedFilament.presets(typed) } }.getOrDefault(emptyList()) }
    LaunchedEffect(target) {
        kotlinx.coroutines.delay(120)
        runCatching { withContext(Dispatchers.IO) { MixedFilament.match(typed, target) } }
            .onSuccess { matchInfo = "ΔE %.1f".format(it.deltaE) + " · " + it.parts.joinToString(" + ") { part -> "F${part.filament} ${part.ratio}%" } }
            .onFailure { matchInfo = it.message }
    }
    fun applyRow(r: MixedFilamentFormat.Row) {
        filaments.clear(); filaments.addAll(r.components.take(3))
        if (r.components.size == 2) share = (r.weights[1] * 100 / (r.weights.sum().takeIf { it > 0 } ?: 1)).coerceIn(10, 90)
        else if (r.components.size == 3) { val t = r.weights.sum().toFloat().takeIf { it > 0 } ?: 1f; tri = r.weights.map { it / t } }
        gradient = false
    }

    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(index) { kotlinx.coroutines.delay(50); bring.bringIntoView() }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(bring).clip(RoundedCornerShape(8.dp)).background(c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(if (mix == null) "Add Mix" else "Edit Mix ${p.mixedSlot(index)}", Nz.type.label)
        val choices = physical.map { s -> Choice(s.slot.toString(), "${s.slot} · ${listOfNotNull(s.vendor, s.type).joinToString(" ").ifBlank { "Filament" }}") }
        filaments.forEachIndexed { i, id ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(if (gradient) (if (i == 0) "Bottom" else "Top") else "Filament ${i + 1}", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(64.dp))
                SlotBadge(id, p.slotHex(id), 22.dp)
                DenseSelect("Filament ${i + 1}", choices.filter { it.value == id.toString() || it.value.toInt() !in filaments }, id.toString(), Modifier.weight(1f)) { v ->
                    v.toIntOrNull()?.let { filaments[i] = it } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!gradient && filaments.size < 3 && physical.size >= 3) Txt("+ Add one filament", Nz.type.label, c.accent, modifier = Modifier.clickable {
                ids.firstOrNull { it !in filaments }?.let { filaments.add(it) } })
            if (!gradient && filaments.size > 2) Txt("− Remove last filament", Nz.type.label, c.accent, modifier = Modifier.clickable { filaments.removeAt(filaments.lastIndex) })
            if (filaments.size == 2 || gradient) Txt(if (gradient) "Plain mix" else "Gradient", Nz.type.label, c.accent, modifier = Modifier.clickable {
                gradient = !gradient; if (gradient && filaments.size > 2) filaments.removeAt(2) })
            if (gradient) Txt("Swap filaments", Nz.type.label, c.accent, modifier = Modifier.clickable { val a0 = filaments[0]; filaments[0] = filaments[1]; filaments[1] = a0 })
        }
        if (gradient && filaments.size == 2) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("At the bottom", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(96.dp))
                RatioBar(100 - bottom, parseHex(p.slotHex(filaments[0])) ?: c.accent, parseHex(p.slotHex(filaments[1])) ?: c.accent, Modifier.weight(1f), 0, 100, 5) { bottom = 100 - it }
                Txt("$bottom% F${filaments[0]}", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(64.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("At the top", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(96.dp))
                RatioBar(100 - top, parseHex(p.slotHex(filaments[0])) ?: c.accent, parseHex(p.slotHex(filaments[1])) ?: c.accent, Modifier.weight(1f), 0, 100, 5) { top = 100 - it }
                Txt("$top% F${filaments[0]}", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(64.dp))
            }
        } else if (filaments.size == 2) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("${100 - share}%", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(36.dp))
            RatioBar(share, parseHex(p.slotHex(filaments[0])) ?: c.accent, parseHex(p.slotHex(filaments[1])) ?: c.accent, Modifier.weight(1f), 10, 90, 1) { share = it }
            Txt("$share%", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(36.dp))
        } else if (filaments.size == 3) TrianglePicker(filaments.map { parseHex(p.slotHex(it)) ?: c.accent }, tri, 0.10f) { tri = it }
        preview?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("Mix Effect", Nz.type.bodySmall, c.textMuted)
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(parseHex(r.hex) ?: c.surface).border(1.dp, c.line, RoundedCornerShape(6.dp)))
                Txt((if (gradient) "Gradient " else "") + r.label, Nz.type.bodySmall, maxLines = 1)
            }
        }
        problem?.let { Txt(it, Nz.type.bodySmall, c.danger) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("Match a colour", Nz.type.bodySmall, c.textMuted)
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(parseHex(target) ?: c.surface).border(1.dp, c.line, RoundedCornerShape(5.dp)))
            var hex by remember(target) { mutableStateOf(target.removePrefix("#")) }
            Txt("#", Nz.type.bodySmall, c.textMuted)
            com.nozzleitall.desktop.settings.DenseInput(hex, { t -> hex = t.take(6); if (Regex("[0-9A-Fa-f]{6}").matches(hex)) target = "#" + hex.uppercase() },
                "Target colour hex", Modifier.width(90.dp), error = !Regex("[0-9A-Fa-f]{6}").matches(hex))
            matchInfo?.let { Txt(it, Nz.type.bodySmall, c.textMuted, modifier = Modifier.weight(1f), maxLines = 1) }
            Txt("Use", Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                scope.launch { runCatching { withContext(Dispatchers.IO) { MixedFilament.match(typed, target) } }.onSuccess { m -> m.row?.let { applyRow(it) } } } }.padding(4.dp))
        }
        if (presets.isNotEmpty()) {
            Txt("Presets", Nz.type.bodySmall, c.textMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.take(36).forEach { pr ->
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(parseHex(pr.hex) ?: c.surface).border(1.dp, c.line, RoundedCornerShape(5.dp))
                        .clickable(onClickLabel = pr.label) { applyRow(pr) }.semantics { contentDescription = pr.label })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mix != null) Txt("Delete", Nz.type.label, c.danger, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { p.removeMixed(index, scope); onDone() }.padding(4.dp))
            Spacer(Modifier.weight(1f))
            NzButton("Cancel", onDone, kind = ButtonKind.SECONDARY)
            NzButton("OK", { if (mix == null) p.addMixed(row(), scope) else p.updateMixed(index, row(), scope); onDone() }, kind = ButtonKind.PRIMARY, enabled = preview != null && problem == null)
        }
    }
}

/** Three filaments' shares on a triangle (each corner one filament), each share clamped to at least [minShare]. */
@Composable
internal fun TrianglePicker(colours: List<Color>, weights: List<Float>, minShare: Float, onChange: (List<Float>) -> Unit) {
    val c = Nz.colors
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(1, 1)) }
    fun corners(): List<androidx.compose.ui.geometry.Offset> { val w = size.width.toFloat(); val h = size.height.toFloat()
        return listOf(androidx.compose.ui.geometry.Offset(w / 2, 6f), androidx.compose.ui.geometry.Offset(6f, h - 6f), androidx.compose.ui.geometry.Offset(w - 6f, h - 6f)) }
    fun pick(pt: androidx.compose.ui.geometry.Offset) {
        val (a, b, d) = corners()
        val det = (b.y - d.y) * (a.x - d.x) + (d.x - b.x) * (a.y - d.y)
        var w1 = ((b.y - d.y) * (pt.x - d.x) + (d.x - b.x) * (pt.y - d.y)) / det
        var w2 = ((d.y - a.y) * (pt.x - d.x) + (a.x - d.x) * (pt.y - d.y)) / det
        var w3 = 1 - w1 - w2
        repeat(4) { w1 = w1.coerceIn(minShare, 1 - 2 * minShare); w2 = w2.coerceIn(minShare, 1 - 2 * minShare); w3 = w3.coerceIn(minShare, 1 - 2 * minShare)
            val s = w1 + w2 + w3; w1 /= s; w2 /= s; w3 /= s }
        onChange(listOf(w1, w2, w3))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(160.dp, 140.dp).onSizeChanged { size = it }
            .pointerInput(Unit) { detectTapGestures { pick(it) } }
            .pointerInput(Unit) { detectDragGestures { ch: androidx.compose.ui.input.pointer.PointerInputChange, _: androidx.compose.ui.geometry.Offset -> pick(ch.position) } }
            .semantics { contentDescription = "Mix of three filaments: " + weights.joinToString { "${(it * 100).toInt()} percent" } }) {
            val (a, b, d) = corners()
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y); close() }
            drawPath(path, c.surface)
            drawPath(path, c.line, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
            listOf(a, b, d).forEachIndexed { i, o -> drawCircle(colours[i], 7f, o) }
            val pt = androidx.compose.ui.geometry.Offset(a.x * weights[0] + b.x * weights[1] + d.x * weights[2], a.y * weights[0] + b.y * weights[1] + d.y * weights[2])
            drawCircle(Color.White, 6f, pt); drawCircle(c.accent, 4f, pt)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            weights.forEachIndexed { i, w -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(colours[i])); Txt("${(w * 100).toInt()}%", Nz.type.bodySmall) } }
        }
    }
}

/** A two-colour bar split at [share] percent of the second colour; click or drag to set it within [min]..[max] in [step]s. */
@Composable
internal fun RatioBar(share: Int, first: Color, second: Color, modifier: Modifier, min: Int = 5, max: Int = 95, step: Int = 5, onChange: (Int) -> Unit) {
    var width by remember { mutableStateOf(1f) }
    val set: (Float) -> Unit = { x -> onChange((Math.round(x / width * 100f / step) * step).coerceIn(min, max)) }
    Box(modifier.height(20.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, Nz.colors.line, RoundedCornerShape(4.dp))
        .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
        .pointerInput(min, max, step) { detectTapGestures { set(it.x) } }
        .pointerInput(min, max, step) { detectHorizontalDragGestures { change, _ -> set(change.position.x) } }
        .semantics { contentDescription = "${100 - share} percent first, $share percent second" }) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight((100 - share).coerceAtLeast(1).toFloat()).fillMaxHeight().background(first))
            Box(Modifier.weight(share.coerceAtLeast(1).toFloat()).fillMaxHeight().background(second))
        }
    }
}

/**
 * "Match colours" for [item]: each colour of the model becomes the closest loaded filament, or a new mix of loaded
 * filaments, whichever looks nearest (the engine's recipe search, run for every colour). Confirm adds the mixes and sets
 * the slots the model's colours print in.
 */
@Composable
fun ColourMatchWindow(p: PrepareState, item: PrepItem, onClose: () -> Unit) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    val physical = p.materials()
    var results by remember { mutableStateOf<List<Pair<String, MixedFilamentFormat.Match>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // The model's colours, one entry per distinct colour with the file filaments using it: the file's own colours when it
    // has them, otherwise the colour each one prints in now.
    val targets = remember(item) {
        (item.painted + listOfNotNull(item.ownFilament)).distinct().mapNotNull { n ->
            (item.sources.firstOrNull { it.index == n }?.colorHex ?: p.slotHex(item.slotFor(n)))?.uppercase()?.let { it to n }
        }.groupBy({ it.first }, { it.second }).toList()
    }
    LaunchedEffect(Unit) {
        busy = true
        val typed = physical.map { (it.colorHex ?: "#FFFFFF") to it.type }
        runCatching { withContext(Dispatchers.IO) { targets.map { (hex, _) -> hex to MixedFilament.match(typed, hex) } } }
            .onSuccess { results = it }.onFailure { problem = it.message }
        busy = false
    }
    DialogWindow(onCloseRequest = onClose, title = "Match colours", state = rememberDialogState(width = 640.dp, height = 640.dp)) {
        Column(Modifier.fillMaxSize().background(c.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (targets.isEmpty()) Banner("This model has no colours from its file to match. Paint it or open a multi-colour file first.", BannerKind.INFO)
            Row(Modifier.fillMaxWidth().height(200.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MatchPreview("Original", item, Modifier.weight(1f)) { n -> item.sources.firstOrNull { it.index == n }?.colorHex ?: p.slotHex(item.slotFor(n)) }
                MatchPreview("Matched", item, Modifier.weight(1f)) { n ->
                    val hex = targets.firstOrNull { n in it.second }?.first
                    results.firstOrNull { it.first == hex }?.second?.let { m -> m.row?.hex ?: m.matchedHex }
                }
            }
            if (busy) Txt("Matching…", Nz.type.bodySmall, c.textMuted)
            problem?.let { Banner(it, BannerKind.WARNING) }
            Txt("Colour mapping", Nz.type.label)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                results.forEach { (hex, m) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(parseHex(hex) ?: c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(6.dp)))
                        Txt("→", Nz.type.label, c.textMuted)
                        Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(parseHex(m.row?.hex ?: m.matchedHex) ?: c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(6.dp)))
                        Txt(m.parts.joinToString(" + ") { "F${it.filament} ${it.ratio}%" }, Nz.type.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                        Txt("ΔE %.1f".format(m.deltaE), Nz.type.bodySmall, c.textMuted)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NzButton("Cancel", onClose, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f))
                NzButton("Confirm", { p.matchWithMixing(item, targets, scope); onClose() }, kind = ButtonKind.PRIMARY, enabled = results.isNotEmpty() && !busy, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The model drawn with each of its file's colours shown as [colourOf] says (null keeps the model's own colour). */
@Composable
private fun MatchPreview(title: String, item: PrepItem, modifier: Modifier, colourOf: (Int) -> String?) {
    val c = Nz.colors
    val camera = remember { ViewCamera().apply { distance = 260f } }
    val own = parseHex(item.ownFilament?.let(colourOf)) ?: c.accent
    val paintColors = List((item.painted.maxOrNull() ?: 0) + 1) { n -> if (n == 0) own else parseHex(colourOf(n)) }
    val b = item.bounds
    val obj = PlateObject(item.id, item.name, item.mesh, own, 60f, 60f, item.rotZ, (100f / maxOf(b[3] - b[0], b[4] - b[1], 1f)).coerceAtMost(4f), false, paintColors)
    Box(modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(c.surfaceSunken)) {
        PlateViewer(120f, 120f, listOf(obj), camera, c.surfaceSunken, c.line.copy(alpha = 0f), Modifier.fillMaxSize(), onSelect = {})
        Txt(title, Nz.type.label, c.text, modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surface).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
