package com.nozzleitall.desktop.prepare

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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

/** Adding or adjusting a two-filament mix: the two loaded slots and the share of the second, in 5 % steps. */
@Composable
private fun MixEditor(p: PrepareState, mix: FullSpectrum.Mix?, physical: List<com.nozzleitall.project.ProjectManifest.MaterialSlot>, onDone: () -> Unit) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    var a by remember(mix?.id) { mutableStateOf(mix?.a ?: physical.getOrNull(0)?.slot ?: 1) }
    var b by remember(mix?.id) { mutableStateOf(mix?.b ?: physical.getOrNull(1)?.slot ?: 2) }
    var share by remember(mix?.id) { mutableStateOf(mix?.mixBPercent ?: 50) }
    val choices = physical.map { s -> Choice(s.slot.toString(), "${s.slot} · ${listOfNotNull(s.vendor, s.type).joinToString(" ").ifBlank { "Filament" }}") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(if (mix == null) "New mix" else "Mix ${mix.id}", Nz.type.label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DenseSelect("First filament", choices, a.toString(), Modifier.weight(1f)) { v -> v.toIntOrNull()?.let { a = it } }
            Txt("+", Nz.type.label, c.textMuted)
            DenseSelect("Second filament", choices, b.toString(), Modifier.weight(1f)) { v -> v.toIntOrNull()?.let { b = it } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("${100 - share}%", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(36.dp))
            RatioBar(share, parseHex(p.slotHex(a)) ?: c.accent, parseHex(p.slotHex(b)) ?: c.accent, Modifier.weight(1f)) { share = it }
            Txt("$share%", Nz.type.bodySmall, c.textMuted, modifier = Modifier.width(36.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mix != null) Txt("Remove", Nz.type.label, c.danger, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                p.editMixes(scope) { phys, defs -> FullSpectrum.remove(phys, defs, mix.id) }; onDone() }.padding(4.dp))
            Spacer(Modifier.weight(1f))
            NzButton("Cancel", onDone, kind = ButtonKind.SECONDARY)
            NzButton(if (mix == null) "Add" else "Apply", {
                if (a != b) {
                    if (mix == null) p.editMixes(scope) { phys, defs -> FullSpectrum.add(phys, defs, a, b, share) }
                    else p.editMixes(scope) { phys, defs -> FullSpectrum.update(phys, defs, mix.id, a, b, share) }
                    onDone()
                }
            }, kind = ButtonKind.PRIMARY, enabled = a != b)
        }
    }
}

/** A two-colour bar split at [share] percent of the second colour; click or drag to set it, in 5 % steps (5-95). */
@Composable
private fun RatioBar(share: Int, first: Color, second: Color, modifier: Modifier, onChange: (Int) -> Unit) {
    var width by remember { mutableStateOf(1f) }
    val set: (Float) -> Unit = { x -> onChange((Math.round(x / width * 100f / 5f) * 5).coerceIn(5, 95)) }
    Box(modifier.height(20.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, Nz.colors.line, RoundedCornerShape(4.dp))
        .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
        .pointerInput(Unit) { detectTapGestures { set(it.x) } }
        .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> set(change.position.x) } }
        .semantics { contentDescription = "Mix ratio: ${100 - share} percent first, $share percent second" }) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight((100 - share).toFloat()).fillMaxHeight().background(first))
            Box(Modifier.weight(share.toFloat()).fillMaxHeight().background(second))
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
    var result by remember { mutableStateOf<FullSpectrum.MatchResult?>(null) }
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
