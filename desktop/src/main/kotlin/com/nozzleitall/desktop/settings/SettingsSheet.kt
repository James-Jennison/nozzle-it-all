@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.nozzleitall.desktop.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nozzleitall.desktop.prepare.PrepareState
import com.nozzleitall.desktop.prepare.QualityPreset
import com.nozzleitall.desktop.ui.*

/**
 * Prepare's settings, dense: one row per setting (label, value with units, reset when changed, help on hover), in tabs
 * of Nozzle's groups for the print, a material or the printer. The Advanced switch shows everything; off, only the
 * essentials (plus anything already changed). Search looks across the whole scope.
 */
@Composable
fun SettingsSheet(catalog: SettingsCatalog, profile: ProfileValues, p: PrepareState, modifier: Modifier = Modifier) {
    val c = Nz.colors
    val scope = p.settingsScope
    val tabs = catalog.tabs[scope].orEmpty()
    var tabId by remember(scope) { mutableStateOf(tabs.firstOrNull()?.id) }
    var searching by remember { mutableStateOf(false) }
    var query by remember(scope) { mutableStateOf("") }
    fun shown(s: SettingDef) = p.advancedSettings || s.mode == "simple" || p.isChanged(s.key, profile)

    Card(modifier) {
        // Header: what these settings are for, the preset, search and the Advanced switch.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scope != Scope.PROCESS) Txt("‹", Nz.type.title, c.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                .clickable(onClickLabel = "Back to print settings") { p.settingsScope = Scope.PROCESS }.padding(horizontal = 6.dp))
            Txt(when (scope) { Scope.PROCESS -> "Print"; Scope.FILAMENT -> "Material settings"; Scope.PRINTER -> "Printer settings" }, Nz.type.label)
            // The printer's own process presets where it has a profile family (Snapmaker's for the U1), else Nozzle's
            // guided presets for printers bundled with a single profile.
            val processes = p.processes()
            if (scope == Scope.PROCESS && processes.isNotEmpty()) DenseSelect("Process preset", processes.map { Choice(it.id, it.label) },
                p.currentProcess()?.id ?: processes.first().id, Modifier.width(170.dp)) { v -> p.processId = v; p.changed() }
            else if (scope == Scope.PROCESS) DenseSelect("Quality preset", QualityPreset.entries.map { Choice(it.name, it.label + " · " + (it.overrides["layer_height"] ?: "") + " mm") }, p.preset.name, Modifier.width(150.dp)) { v ->
                QualityPreset.entries.firstOrNull { it.name == v }?.let { p.preset = it; p.changed() }
            }
            Spacer(Modifier.weight(1f))
            IconToggle(NzIcon.SEARCH, "Find a setting", searching) { searching = !searching; if (!searching) query = "" }
            Txt("Advanced", Nz.type.label, c.textMuted)
            MiniSwitch(p.advancedSettings, "Show every setting") { p.advancedSettings = it }
        }
        if (scope == Scope.PROCESS && p.overrides.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val changed = p.overrides.size
            if (changed > 0) {
                Txt("$changed changed", Nz.type.bodySmall, c.accent)
                Txt("Reset", Nz.type.label, c.textMuted, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { p.overrides.clear(); p.changed() }.padding(4.dp))
            }
        }
        if (searching) DenseInput(query, { query = it }, "Find a setting", Modifier.fillMaxWidth(), placeholder = "seam, brim, gyroid…")
        if (!searching && tabs.size > 1) TabStrip(tabs, tabId) { tabId = it }

        val rows: List<Pair<SettingGroup, List<SettingDef>>> = if (searching && query.isNotBlank())
            catalog.search(query).filter { it.second.scope == scope }.groupBy({ it.first }, { it.second }).toList()
        else tabs.firstOrNull { it.id == tabId }?.groups?.map { g -> g to g.settings.filter(::shown) }?.filter { it.second.isNotEmpty() } ?: emptyList()
        val listState = rememberLazyListState()
        LaunchedEffect(tabId, query, scope) { listState.scrollToItem(0) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            if (rows.isEmpty()) item("empty") {
                Txt(if (searching) "No setting here matches “$query”." else "Nothing essential here. Turn on Advanced to see everything.", Nz.type.bodySmall, c.textMuted,
                    modifier = Modifier.padding(vertical = 12.dp))
            }
            rows.forEach { (g, list) ->
                item("h-${g.id}") { GroupHeader(g) }
                items(list, key = { "${g.id}:${it.key}" }) { s ->
                    DenseRow(s, p.setting(s.key, profile), p.baseSetting(s.key, profile), p.isChanged(s.key, profile)) { v -> p.setSetting(s.key, v, profile) }
                }
            }
        }
    }
}

@Composable
private fun TabStrip(tabs: List<SettingTab>, selected: String?, onSelect: (String) -> Unit) {
    val c = Nz.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        tabs.forEach { t ->
            val on = t.id == selected
            Column(Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSelect(t.id) }.padding(horizontal = 5.dp, vertical = 5.dp)
                .semantics { contentDescription = "${t.title} settings" }, horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(t.title, Nz.type.bodySmall, if (on) c.accent else c.textMuted, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.height(2.dp).width(24.dp).background(if (on) c.accent else c.surface))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

@Composable
private fun GroupHeader(g: SettingGroup) {
    val c = Nz.colors
    TooltipArea(tooltip = { Tip(g.summary) }, delayMillis = 500) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(c.accent))
            Txt(g.title, Nz.type.label, c.text, maxLines = 1)
            Box(Modifier.weight(1f).height(1.dp).background(c.line))
        }
    }
}

/** One setting: label (help on hover), the value with its units, and a reset mark when it differs from the base. */
@Composable
private fun DenseRow(s: SettingDef, value: String?, base: String?, changed: Boolean, onSet: (String?) -> Unit) {
    val c = Nz.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(if (changed) c.accent else c.surface))
        Spacer(Modifier.width(8.dp))
        TooltipArea(tooltip = { Tip(buildString { s.help?.let { append(it) }; if (base != null && changed) append("\n\nProfile: ${s.display(base).take(80)}") }) },
            delayMillis = 400, modifier = Modifier.weight(1f)) {
            Txt(s.label, Nz.type.bodySmall, if (changed) c.accent else c.text, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        DenseControl(s, value.orEmpty(), onSet)
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            if (changed) Txt("↺", Nz.type.label, c.textMuted, modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClickLabel = "Reset ${s.label}") { onSet(null) }.padding(2.dp))
        }
    }
}

@Composable
private fun DenseControl(s: SettingDef, serialized: String, onSet: (String?) -> Unit) {
    val width = 140.dp
    when {
        s.readonly -> Txt(s.display(serialized), Nz.type.bodySmall, Nz.colors.textMuted, modifier = Modifier.width(width), maxLines = 1)
        s.type == "bool" -> Box(Modifier.width(width)) { MiniCheck(serialized == "1", s.label) { onSet(if (it) "1" else "0") } }
        s.type == "enum" && !s.openChoices -> DenseSelect(s.label, s.choices, serialized, Modifier.width(width)) { onSet(it) }
        s.code || s.multiline -> CodeButton(s, serialized, Modifier.width(width), onSet)
        else -> {
            var text by remember(s.key, serialized) { mutableStateOf(s.display(serialized)) }
            val problem = s.problem(text)
            TooltipArea(tooltip = { problem?.let { Tip(it) } }, delayMillis = 0) {
                DenseInput(text, { t -> text = t; if (s.problem(t) == null) onSet(s.serialize(t)) }, s.label, Modifier.width(width), units = s.units, error = problem != null,
                    placeholder = if (s.nullable) "Printer's" else "")
            }
        }
    }
}

@Composable
fun DenseInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, units: String? = null, error: Boolean = false, placeholder: String = "") {
    val c = Nz.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Row(modifier.height(28.dp).clip(shape).background(c.surfaceSunken).border(1.dp, if (error) c.danger else if (focused) c.focus else c.line, shape).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) Txt(placeholder, Nz.type.bodySmall, c.textMuted, maxLines = 1)
            BasicTextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = label }, singleLine = true,
                textStyle = Nz.type.bodySmall.copy(color = c.text), cursorBrush = SolidColor(c.accent), interactionSource = interaction)
        }
        units?.let { Spacer(Modifier.width(4.dp)); Txt(it, Nz.type.bodySmall, c.textMuted, maxLines = 1) }
    }
}

@Composable
fun DenseSelect(label: String, choices: List<Choice>, value: String, modifier: Modifier, onSelect: (String) -> Unit) {
    val c = Nz.colors
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Box(modifier) {
        Row(Modifier.fillMaxWidth().height(28.dp).clip(shape).background(c.surfaceSunken).border(1.dp, if (open) c.focus else c.line, shape)
            .clickable(onClickLabel = "Choose $label") { open = !open }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(choices.firstOrNull { it.value == value }?.label ?: value, Nz.type.bodySmall, c.text, modifier = Modifier.weight(1f), maxLines = 1)
            Txt("▾", Nz.type.bodySmall, c.textMuted)
        }
        if (open) Popup(offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 30.dp.roundToPx() }),
            onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(240.dp).heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceRaised).border(1.dp, c.lineStrong, RoundedCornerShape(10.dp))
                .verticalScroll(rememberScrollState()).padding(4.dp)) {
                choices.forEach { ch ->
                    Txt(ch.label, Nz.type.bodySmall, if (ch.value == value) c.accent else c.text, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (ch.value == value) c.accent.copy(alpha = 0.14f) else c.surfaceRaised)
                            .clickable { onSelect(ch.value); open = false }.padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
        }
    }
}

/** G-code and other long text: edited in a larger box that opens from the row. */
@Composable
private fun CodeButton(s: SettingDef, serialized: String, modifier: Modifier, onSet: (String?) -> Unit) {
    val c = Nz.colors
    var open by remember { mutableStateOf(false) }
    val lines = s.display(serialized).lines().count { it.isNotBlank() }
    Box(modifier) {
        Txt(if (lines == 0) "Empty · Edit" else "$lines lines · Edit", Nz.type.bodySmall, c.accent, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 6.dp))
        if (open) Popup(onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            var text by remember { mutableStateOf(s.display(serialized)) }
            Column(Modifier.width(520.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceRaised).border(1.dp, c.lineStrong, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(s.label, Nz.type.label)
                Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceSunken).border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    BasicTextField(text, { text = it }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).semantics { contentDescription = s.label },
                        textStyle = Nz.type.metricSmall.copy(color = c.text), cursorBrush = SolidColor(c.accent))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    NzButton("Cancel", { open = false }, kind = ButtonKind.QUIET)
                    NzButton("Done", { onSet(s.serialize(text)); open = false }, kind = ButtonKind.PRIMARY)
                }
            }
        }
    }
}

@Composable
fun MiniSwitch(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val c = Nz.colors
    Box(Modifier.size(34.dp, 18.dp).clip(RoundedCornerShape(9.dp)).background(if (on) c.accent else c.surfaceSunken).border(1.dp, c.lineStrong, RoundedCornerShape(9.dp))
        .clickable(onClickLabel = label) { onChange(!on) }.semantics { contentDescription = "$label: ${if (on) "on" else "off"}" }) {
        Box(Modifier.padding(2.dp).size(14.dp).align(if (on) Alignment.CenterEnd else Alignment.CenterStart).clip(RoundedCornerShape(7.dp)).background(if (on) c.onAccent else c.textMuted))
    }
}

@Composable
private fun MiniCheck(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val c = Nz.colors
    Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(if (on) c.accent else c.surfaceSunken).border(1.dp, if (on) c.accent else c.lineStrong, RoundedCornerShape(4.dp))
        .clickable(onClickLabel = label) { onChange(!on) }.semantics { contentDescription = "$label: ${if (on) "on" else "off"}" }, contentAlignment = Alignment.Center) {
        if (on) Txt("✓", Nz.type.label, c.onAccent)
    }
}

@Composable
fun IconToggle(icon: NzIcon, label: String, on: Boolean, onClick: () -> Unit) {
    val c = Nz.colors
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(if (on) c.accent.copy(alpha = 0.16f) else c.surface).clickable(onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { Icon(icon, if (on) c.accent else c.textMuted, 16.dp) }
}

@Composable
private fun Tip(text: String) {
    if (text.isBlank()) return
    val c = Nz.colors
    Box(Modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceRaised).border(1.dp, c.lineStrong, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Txt(text, Nz.type.bodySmall, c.text)
    }
}

/** A choice from a list too long for buttons. Keyboard: Enter or Space opens it; the list is scrollable. */
@Composable
fun NzSelect(label: String, choices: List<Choice>, value: String, onSelect: (String) -> Unit) {
    val c = Nz.colors
    var open by remember { mutableStateOf(false) }
    Box {
        NzButton((choices.firstOrNull { it.value == value }?.label ?: value.ifBlank { "Choose…" }) + "  ▾", { open = !open }, kind = ButtonKind.SECONDARY)
        if (open) Popup(onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(280.dp).heightIn(max = 340.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceRaised).border(1.dp, c.lineStrong, RoundedCornerShape(10.dp))
                .verticalScroll(rememberScrollState()).padding(4.dp).semantics { contentDescription = "Choices for $label" }) {
                choices.forEach { ch ->
                    Txt(ch.label, Nz.type.body, if (ch.value == value) c.accent else c.text,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (ch.value == value) c.accent.copy(alpha = 0.14f) else c.surfaceRaised)
                            .clickable { onSelect(ch.value); open = false }.padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
        }
    }
}
