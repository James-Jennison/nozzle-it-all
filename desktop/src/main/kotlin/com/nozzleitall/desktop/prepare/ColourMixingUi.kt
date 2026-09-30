package com.nozzleitall.desktop.prepare

import com.nozzleitall.printer.ext.FullSpectrumFormat
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
 * Snapmaker Full Spectrum in the Filament card: the mixed filaments as numbered slots after the loaded ones (5, 6 ... on
 * a U1), each with its blended colour and Snapmaker's own label ("F2 33%+F4 67%"); add, adjust and remove; and
 * "Match colours", which opens Color Mixing Match. Every value shown comes from Snapmaker Orca's code in the engine.
 */
@Composable
fun ColourMixingSection(p: PrepareState) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    val physical = p.materials()
    LaunchedEffect(p.mixDefinitions, physical.map { it.colorHex }) { p.refreshMixes(this) }
    var editing by remember { mutableStateOf<Int?>(null) } // a mix id, or 0 for a new one
    var matching by remember { mutableStateOf<PrepItem?>(null) }

    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Colour mixing", Nz.type.label, c.text)
        Txt("Full Spectrum", Nz.type.bodySmall, c.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
        val painted = p.items.firstOrNull { it.id == p.selected && it.painted.isNotEmpty() } ?: p.items.firstOrNull { it.painted.isNotEmpty() }
        if (painted != null) Txt("Match colours", Nz.type.label, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .clickable(onClickLabel = "Match the model's colours with Full Spectrum") { matching = painted }.padding(4.dp))
        IconToggle(NzIcon.ADD, "Add a mix", editing == 0) { editing = if (editing == 0) null else 0 }
    }
    p.mixProblem?.let { Txt(it, Nz.type.bodySmall, c.danger) }
    if (p.mixes.isEmpty() && editing == null)
        Txt("Mix two loaded filaments into a new colour, printed in alternating thin layers.", Nz.type.bodySmall, c.textMuted)
    p.mixes.filter { it.enabled }.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { m ->
                Row(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(6.dp)).background(c.surfaceSunken).border(1.dp, if (editing == m.id) c.accent else c.line, RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "Adjust mix ${m.id}") { editing = if (editing == m.id) null else m.id }
                    .semantics(mergeDescendants = true) { contentDescription = "Mix ${m.id}: ${m.label}" }, verticalAlignment = Alignment.CenterVertically) {
                    SlotBadge(m.id, m.displayHex)
                    Txt(m.label, Nz.type.bodySmall, modifier = Modifier.padding(horizontal = 8.dp).weight(1f), maxLines = 1)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    editing?.let { id -> MixEditor(p, p.mixes.firstOrNull { it.id == id }, physical) { editing = null } }
    matching?.let { item -> ColourMatchWindow(p, item) { matching = null } }
}

/**
 * Snapmaker's mix editor (MixedFilamentDialog), in its four modes, computed by Snapmaker's own code in the engine:
 * Ratio (two filaments on a 10-90 % bar, or three on a triangle), Cycle (a layer pattern such as "1124"), Match (a
 * target colour; Snapmaker's search picks the mix, Min Mix Ratio 0-50 %), and Gradient (two filaments, bottom to top).
 * Every mode previews the result (its layer stripe and colour) and offers Snapmaker's recommended swatches.
 */
@Composable
private fun MixEditor(p: PrepareState, mix: FullSpectrumFormat.Mix?, physical: List<com.nozzleitall.project.ProjectManifest.MaterialSlot>, onDone: () -> Unit) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    val typed = physical.map { (it.colorHex ?: "#FFFFFF") to it.type }
    val ids = physical.map { it.slot }
    // Edit opens on the mode the mix was made in (Snapmaker's ui_mode: 0 Ratio, 1 Cycle, 2 Match, 3 Gradient).
    var mode by remember(mix?.id) { mutableStateOf(when (mix?.uiMode) { 1 -> "cycle"; 2 -> "match"; 3 -> "gradient"; else -> "ratio" }) }
    // Ratio / Match / Gradient filaments, and the ratio state.
    val filaments = remember(mix?.id) { mutableStateListOf<Int>().apply { addAll(mix?.components?.take(3) ?: ids.take(2)) } }
    var share by remember(mix?.id) { mutableStateOf((mix?.mixBPercent ?: 50).coerceIn(10, 90)) }
    var tri by remember(mix?.id) { mutableStateOf(mix?.weights?.takeIf { it.size == 3 }?.map { it / 100f } ?: listOf(1 / 3f, 1 / 3f, 1 / 3f)) }
    var pattern by remember(mix?.id) { mutableStateOf(mix?.pattern?.ifBlank { null } ?: "12") }
    var target by remember(mix?.id) { mutableStateOf(mix?.displayHex ?: "#808080") }
    var minPct by remember { mutableStateOf(15) }
    var matchDialog by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var matchInfo by remember { mutableStateOf<String?>(null) }
    var direction by remember { mutableStateOf(0) }
    var preview by remember { mutableStateOf<FullSpectrumFormat.Mixes?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var presets by remember { mutableStateOf<List<FullSpectrum.Preset>>(emptyList()) }

    fun dialog(): org.json.JSONObject? = when (mode) {
        "ratio" -> org.json.JSONObject().put("mode", "ratio").put("filaments", org.json.JSONArray(filaments.toList())).apply {
            if (filaments.size == 3) put("weights", org.json.JSONArray(tri.map { (it * 100).toInt() })) else put("mix_b_percent", share) }
        "cycle" -> org.json.JSONObject().put("mode", "cycle").put("pattern", pattern)
        "match" -> matchDialog
        else -> org.json.JSONObject().put("mode", "gradient").put("filaments", org.json.JSONArray(filaments.take(2))).put("direction", direction)
    }
    // Preview (debounced), exactly what OK would store.
    LaunchedEffect(mode, filaments.toList(), share, tri, pattern, matchDialog, direction) {
        kotlinx.coroutines.delay(150)
        val d = dialog() ?: run { preview = null; return@LaunchedEffect }
        runCatching { withContext(Dispatchers.IO) { FullSpectrum.preview(typed, d) } }
            .onSuccess { preview = it; problem = null }.onFailure { preview = null; problem = it.message }
    }
    // Match: Snapmaker's search, 120 ms after the target or Min Mix Ratio changes.
    LaunchedEffect(mode, target, minPct) {
        if (mode != "match") return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        runCatching { withContext(Dispatchers.IO) { FullSpectrum.matchOne(typed, target, minPct) } }
            .onSuccess { matchDialog = it.dialog; matchInfo = "${it.quality} · ΔE %.1f".format(it.deltaE); problem = null }
            .onFailure { matchDialog = null; matchInfo = null; problem = it.message }
    }
    LaunchedEffect(mode, filaments.size, minPct) {
        val m = when (mode) { "ratio" -> if (filaments.size == 3) "ratio3" else "ratio"; "cycle" -> null; else -> mode }
        presets = m?.let { runCatching { withContext(Dispatchers.IO) { FullSpectrum.presets(typed, it, minPct) } }.getOrDefault(emptyList()) } ?: emptyList()
    }
    fun applyPreset(d: org.json.JSONObject) {
        val f = d.optJSONArray("filaments")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
        f?.let { filaments.clear(); filaments.addAll(it) }
        d.optJSONArray("weights")?.let { a -> val w = (0 until a.length()).map { a.getDouble(it).toFloat() }; val sum = w.sum().takeIf { it > 0 } ?: 1f
            if (w.size == 3) tri = w.map { it / sum } else if (w.size == 2) share = ((w[1] / sum) * 100).toInt().coerceIn(10, 90) }
        if (d.has("mix_b_percent")) share = d.getInt("mix_b_percent").coerceIn(10, 90)
        if (d.has("direction")) direction = d.getInt("direction")
        if (mode == "match") matchDialog = d
    }

    // Opening the editor scrolls it into view (it opens below the mix list).
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(mix?.id) { kotlinx.coroutines.delay(50); bring.bringIntoView() }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(bring).clip(RoundedCornerShape(8.dp)).background(c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(if (mix == null) "Add Mix" else "Edit Mix ${mix.id}", Nz.type.label)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("ratio" to "Ratio", "cycle" to "Cycle", "match" to "Match", "gradient" to "Gradient").forEach { (m, label) ->
                val on = mode == m
                Txt(label, Nz.type.label, if (on) c.accent else c.textMuted, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (on) c.accent.copy(alpha = 0.14f) else Color.Transparent).clickable { mode = m; if (m == "gradient" && filaments.size > 2) filaments.removeAt(2) }
                    .padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        val choices = physical.map { s -> Choice(s.slot.toString(), "${s.slot} · ${listOfNotNull(s.vendor, s.type).joinToString(" ").ifBlank { "Filament" }}") }
        when (mode) {
            "ratio", "gradient" -> {
                filaments.forEachIndexed { i, id ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(if (mode == "gradient") (if (i == 0) "Bottom" else "Top") else "Filament ${i + 1}", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(64.dp))
                        SlotBadge(id, p.slotHex(id), 22.dp)
                        DenseSelect("Filament ${i + 1}", choices.filter { it.value == id.toString() || it.value.toInt() !in filaments }, id.toString(), Modifier.weight(1f)) { v ->
                            v.toIntOrNull()?.let { filaments[i] = it } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (mode == "ratio" && filaments.size < 3 && physical.size >= 3) Txt("+ Add one filament", Nz.type.label, c.accent, modifier = Modifier.clickable {
                        ids.firstOrNull { it !in filaments }?.let { filaments.add(it) } })
                    if (mode == "ratio" && filaments.size > 2) Txt("− Remove last filament", Nz.type.label, c.accent, modifier = Modifier.clickable { filaments.removeAt(filaments.lastIndex) })
                    if (mode == "gradient") Txt("Swap filaments", Nz.type.label, c.accent, modifier = Modifier.clickable {
                        val a0 = filaments[0]; filaments[0] = filaments[1]; filaments[1] = a0; direction = 0 })
                }
                if (mode == "ratio" && filaments.size == 2) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt("${100 - share}%", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(36.dp))
                    RatioBar(share, parseHex(p.slotHex(filaments[0])) ?: c.accent, parseHex(p.slotHex(filaments[1])) ?: c.accent, Modifier.weight(1f), 10, 90, 1) { share = it }
                    Txt("$share%", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(36.dp))
                }
                if (mode == "ratio" && filaments.size == 3) TrianglePicker(filaments.map { parseHex(p.slotHex(it)) ?: c.accent }, tri, 0.10f) { tri = it }
            }
            "cycle" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("Tap to add", Nz.type.bodySmall, c.textMuted)
                    physical.forEach { s -> Box(Modifier.clickable(onClickLabel = "Append filament ${s.slot} to pattern") {
                        pattern = (pattern + if (s.slot >= 10) "[${s.slot}]" else "${s.slot}").take(512) }) { SlotBadge(s.slot, s.colorHex, 22.dp) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt("Pattern", Nz.type.bodySmall, c.textMuted)
                    com.nozzleitall.desktop.settings.DenseInput(pattern, { pattern = it.take(512) }, "Layer pattern", Modifier.weight(1f), placeholder = "12")
                    Txt("Clear", Nz.type.label, c.accent, modifier = Modifier.clickable { pattern = "" })
                }
            }
            "match" -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt("Target colour", Nz.type.bodySmall, c.textMuted)
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(parseHex(target) ?: c.surface).border(1.dp, c.line, RoundedCornerShape(5.dp)))
                    var hex by remember(target) { mutableStateOf(target.removePrefix("#")) }
                    Txt("Hex: #", Nz.type.bodySmall, c.textMuted)
                    com.nozzleitall.desktop.settings.DenseInput(hex, { t -> hex = t.take(6); if (Regex("[0-9A-Fa-f]{6}").matches(hex)) target = "#" + hex.uppercase() },
                        "Target colour hex", Modifier.width(100.dp), error = !Regex("[0-9A-Fa-f]{6}").matches(hex))
                    matchInfo?.let { Txt(it, Nz.type.bodySmall, c.textMuted) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt("Min Mix Ratio", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(96.dp))
                    RatioBar(minPct, c.line, c.accent, Modifier.weight(1f), 0, 50, 1) { minPct = it }
                    Txt("$minPct%", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(36.dp))
                }
            }
        }
        // Result: the layer stripe and the colour it reads as, with Snapmaker's label.
        preview?.rows?.lastOrNull()?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("Mix Effect", Nz.type.bodySmall, c.textMuted)
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(parseHex(r.displayHex) ?: c.surface).border(1.dp, c.line, RoundedCornerShape(6.dp)))
                LayerStripe(r.components, r.weights, p, Modifier.weight(1f).height(14.dp))
                Txt(r.label, Nz.type.bodySmall, maxLines = 1)
            }
        }
        preview?.warning?.let { Txt(it, Nz.type.bodySmall, c.heat) }
        problem?.let { Txt(it, Nz.type.bodySmall, c.danger) }
        if (presets.any { it.visible }) {
            Txt("Mixing Recommendations", Nz.type.bodySmall, c.textMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.filter { it.visible }.take(24).forEach { pr ->
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(parseHex(pr.previewHex) ?: c.surface).border(1.dp, c.line, RoundedCornerShape(5.dp))
                        .clickable(onClickLabel = pr.tooltip) { applyPreset(pr.dialog) }.semantics { contentDescription = pr.tooltip })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mix != null) Txt("Delete", Nz.type.label, c.danger, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                p.editMixes(scope) { phys, defs -> FullSpectrum.remove(phys, defs, mix.id) }; onDone() }.padding(4.dp))
            Spacer(Modifier.weight(1f))
            NzButton("Cancel", onDone, kind = ButtonKind.SECONDARY)
            NzButton("OK", {
                dialog()?.let { d -> p.editMixes(scope) { _, defs -> FullSpectrum.save(typed, defs, d, mix?.id) }; onDone() }
            }, kind = ButtonKind.PRIMARY, enabled = problem == null && preview != null)
        }
    }
}

/** The repeating layer order of a mix, one band per layer (a:b ratio or the weights), as Snapmaker's preview stripe. */
@Composable
private fun LayerStripe(components: List<Int>, weights: List<Int>, p: PrepareState, modifier: Modifier) {
    val total = weights.sum().takeIf { it > 0 } ?: 1
    val seq = ArrayList<Int>(); val emitted = IntArray(components.size)
    val len = 20
    for (pos in 0 until len) {
        val pick = components.indices.maxByOrNull { i -> (pos + 1) * weights.getOrElse(i) { 0 } / total.toDouble() - emitted[i] } ?: 0
        emitted[pick]++; seq += components.getOrElse(pick) { 1 }
    }
    Row(modifier.clip(RoundedCornerShape(3.dp))) { seq.forEach { id -> Box(Modifier.weight(1f).fillMaxHeight().background(parseHex(p.slotHex(id)) ?: Nz.colors.surface)) } }
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
 * Snapmaker's "Color Mixing Match" for [item]: every colour of the model becomes a loaded slot, or a new mix of loaded
 * slots, whichever looks closest (Snapmaker Orca's own search, run by the engine). Auto matches against Snapmaker's
 * recommended Full Spectrum filaments; Manual against the slots chosen here. Confirm applies the mapping and adds the mixes.
 */
@Composable
fun ColourMatchWindow(p: PrepareState, item: PrepItem, onClose: () -> Unit) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    val physical = p.materials()
    var mode by remember { mutableStateOf("auto") }
    val manual = remember { mutableStateListOf<Int>().apply { addAll(physical.map { it.slot }) } }
    var result by remember { mutableStateOf<FullSpectrumFormat.MatchResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // The model's colours, one entry per distinct colour with the file filaments using it: the file's own colours when it
    // has them (a Bambu/Orca project, as Snapmaker Orca adopts them), otherwise the colour each one prints in now.
    val targets = remember(item) {
        (item.painted + listOfNotNull(item.ownFilament)).distinct().mapNotNull { n ->
            (item.sources.firstOrNull { it.index == n }?.colorHex ?: p.slotHex(item.slotFor(n)))?.uppercase()?.let { it to n }
        }.groupBy({ it.first }, { it.second }).toList()
    }
    fun start() {
        busy = true; problem = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                FullSpectrum.match(mode, physical.map { (it.colorHex ?: "#FFFFFF") to it.type }, targets, p.mixDefinitions, manual.toList())
            } }.onSuccess { result = it }.onFailure { problem = it.message }
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (targets.isNotEmpty()) start() }

    DialogWindow(onCloseRequest = onClose, title = "Match colours", state = rememberDialogState(width = 720.dp, height = 760.dp)) {
        Column(Modifier.fillMaxSize().background(c.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("Match with", Nz.type.label)
                DenseSelect("Match mode", listOf(Choice("auto", "Recommended Full Spectrum filaments"), Choice("manual", "The filaments I choose")), mode,
                    Modifier.width(300.dp)) { mode = it; result = null }
                Spacer(Modifier.weight(1f))
                NzButton(if (result == null) "Start matching" else "Match again", { start() }, kind = ButtonKind.SECONDARY, enabled = !busy && targets.isNotEmpty())
            }
            if (targets.isEmpty()) Banner("This model has no colours from its file to match. Paint it or open a multi-colour file first.", BannerKind.INFO)
            if (mode == "manual") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Use", Nz.type.bodySmall, c.textMuted)
                physical.forEach { s ->
                    val on = s.slot in manual
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).border(2.dp, if (on) c.accent else Color.Transparent, RoundedCornerShape(8.dp)).padding(2.dp)
                        .clickable(onClickLabel = if (on) "Don't use slot ${s.slot}" else "Use slot ${s.slot}") { if (on) manual.remove(s.slot) else manual.add(s.slot); result = null }) {
                        SlotBadge(s.slot, s.colorHex)
                    }
                }
            }
            result?.palette?.takeIf { mode == "auto" && it.isNotEmpty() }?.let { pal ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("Load", Nz.type.bodySmall, c.textMuted)
                    pal.forEach { (slot, hex) -> SlotBadge(slot, hex) }
                    Txt("in slots ${pal.joinToString { it.first.toString() }}.", Nz.type.bodySmall, c.textMuted)
                }
            }
            // Original and matched, side by side.
            Row(Modifier.fillMaxWidth().height(230.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MatchPreview("Original", item, Modifier.weight(1f)) { n -> item.sources.firstOrNull { it.index == n }?.colorHex ?: p.slotHex(item.slotFor(n)) }
                MatchPreview("Matched", item, Modifier.weight(1f)) { n ->
                    val r = result?.results?.firstOrNull { n in it.sourceIds } ?: return@MatchPreview null
                    if (r.pure) result?.palette?.firstOrNull { it.first == r.slot }?.second ?: p.slotHex(r.slot) else r.previewHex
                }
            }
            if (busy) Txt("Matching…", Nz.type.bodySmall, c.textMuted)
            problem?.let { Banner(it, BannerKind.WARNING) }
            Txt("Colour mapping", Nz.type.label)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                result?.results?.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(parseHex(r.targetHex) ?: c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(6.dp)))
                        Txt("→", Nz.type.label, c.textMuted)
                        val hex = if (r.pure) result?.palette?.firstOrNull { it.first == r.slot }?.second ?: p.slotHex(r.slot) else r.previewHex
                        SlotBadge(r.slot, hex, 26.dp)
                        Txt(if (r.pure) "Slot ${r.slot}" else "New mix ${r.slot}: " + r.components.zip(r.weights).joinToString(" + ") { (id, w) -> "F$id $w%" },
                            Nz.type.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                        r.quality?.let { q -> Txt(q + (r.deltaE?.let { " · ΔE %.1f".format(it) } ?: ""), Nz.type.bodySmall, c.textMuted) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NzButton("Cancel", onClose, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f))
                NzButton("Confirm", { result?.let { p.applyMatch(item, it, scope) }; onClose() }, kind = ButtonKind.PRIMARY,
                    enabled = result != null && !busy, modifier = Modifier.weight(1f))
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
