package com.nozzleitall.desktop.prepare

import net.jamesjennison.klippercompanion.FlushVolumes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.nozzleitall.desktop.settings.DenseInput
import com.nozzleitall.desktop.ui.*

/**
 * Snapmaker Orca's Flushing volumes window (src/slic3r/GUI/WipeTowerDialog.cpp WipingPanel): the volume purged for each
 * change from one slot (row) to another (column), shown multiplied by the multiplier as upstream shows it, Auto-calculate
 * from the colours, and the multiplier (0-3). Saving a matrix identical to the calculated one keeps it automatic, so later
 * colour changes are followed.
 */
@Composable
fun FlushVolumesWindow(p: PrepareState, onClose: () -> Unit) {
    val c = Nz.colors
    val slots = p.materials()
    val n = slots.size
    val auto = remember(slots) { p.autoFlush() }
    val draft = remember { mutableStateListOf<Int>().apply { addAll(p.flushMatrix()) } }
    var multiplier by remember { mutableStateOf(p.flushMultiplier()) }
    var multiplierText by remember { mutableStateOf("%.2f".format(java.util.Locale.ROOT, multiplier)) }
    val setup = remember(slots) { p.flushSetup() }
    val minimum = setup.minimum.minOrNull() ?: 0

    DialogWindow(onCloseRequest = onClose, title = "Flushing volumes", state = rememberDialogState(width = (180 + 96 * n).coerceIn(520, 1100).dp, height = (260 + 44 * n).coerceIn(420, 900).dp)) {
        Column(Modifier.fillMaxSize().background(c.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt("Flushing volume (mm³) for each filament change, from the slot on the left to the slot on top.", Nz.type.bodySmall, c.textMuted)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(40.dp))
                    slots.forEach { s -> Box(Modifier.width(84.dp), contentAlignment = Alignment.Center) { SlotBadge(s.slot, s.colorHex, 26.dp) } }
                }
                slots.forEachIndexed { from, fs ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(40.dp)) { SlotBadge(fs.slot, fs.colorHex, 26.dp) }
                        slots.forEachIndexed { to, ts ->
                            val i = from * n + to
                            if (from == to) Box(Modifier.width(84.dp), contentAlignment = Alignment.Center) { Txt("—", Nz.type.bodySmall, c.textMuted) }
                            else {
                                val shown = (draft[i] * multiplier).toInt().toString()
                                var text by remember(i, shown) { mutableStateOf(shown) }
                                DenseInput(text, { t -> text = t
                                    t.trim().toIntOrNull()?.takeIf { it >= 0 }?.let { v -> draft[i] = if (multiplier > 0f) Math.round(v / multiplier) else v } },
                                    "Flush from slot ${fs.slot} to slot ${ts.slot}", Modifier.width(84.dp), units = "mm³")
                            }
                        }
                    }
                }
            }
            Txt("Suggestion: flushing volume in the range $minimum-${setup.max} mm³, before the multiplier.", Nz.type.bodySmall, c.textMuted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("Multiplier", Nz.type.label)
                DenseInput(multiplierText, { t -> multiplierText = t
                    t.trim().toFloatOrNull()?.let { multiplier = it.coerceIn(FlushVolumes.MIN_MULTIPLIER, FlushVolumes.MAX_MULTIPLIER) } },
                    "Flushing volume multiplier", Modifier.width(90.dp))
                Txt("${FlushVolumes.MIN_MULTIPLIER.toInt()}-${FlushVolumes.MAX_MULTIPLIER.toInt()}; the printer uses each volume times this.", Nz.type.bodySmall, c.textMuted)
                Spacer(Modifier.weight(1f))
                NzButton("Auto-calculate", { draft.clear(); draft.addAll(auto) }, kind = ButtonKind.SECONDARY)
                NzButton("Cancel", onClose, kind = ButtonKind.QUIET)
                NzButton("OK", {
                    p.setFlush(if (draft.toList() == auto) null else draft.toList())
                    if (multiplier != p.flushMultiplier()) p.setFlushMultiplier(multiplier)
                    onClose()
                }, kind = ButtonKind.PRIMARY, testTag = "flush-ok")
            }
        }
    }
}
