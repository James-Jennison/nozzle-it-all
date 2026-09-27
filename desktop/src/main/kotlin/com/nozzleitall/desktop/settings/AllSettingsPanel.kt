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
import com.nozzleitall.desktop.ui.*

/**
 * Every engine setting, searchable, in Nozzle's own groups. A setting shows the profile's value until it's changed;
 * changes are kept per project ([overrides], serialized as the engine reads them) and can be reset one by one or all at once.
 */
@Composable
fun AllSettingsPanel(catalog: SettingsCatalog, profile: ProfileValues, overrides: MutableMap<String, String>, onChanged: () -> Unit, onClose: () -> Unit,
                     modifier: Modifier = Modifier) {
    val c = Nz.colors
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(Scope.PROCESS) }
    var detail by remember { mutableStateOf(Detail.MORE) }
    var changedOnly by remember { mutableStateOf(false) }
    var groupId by remember { mutableStateOf(catalog.groups.first { it.scope == Scope.PROCESS }.id) }

    fun visible(s: SettingDef) = (s.mode in detail.modes || s.key in overrides) && (!changedOnly || s.key in overrides)
    val searching = query.isNotBlank()
    val rows: List<Pair<SettingGroup, SettingDef>> = when {
        searching -> catalog.search(query).filter { visible(it.second) }
        changedOnly -> catalog.groups.flatMap { g -> g.settings.filter { it.key in overrides }.map { g to it } }
        else -> catalog.groups.firstOrNull { it.id == groupId }?.let { g -> g.settings.filter(::visible).map { g to it } } ?: emptyList()
    }

    Card(modifier.fillMaxHeight()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("All settings", Nz.type.title)
                Txt("${catalog.groups.sumOf { it.settings.size }} settings from the slicing engine · ${overrides.size} changed for this project", Nz.type.bodySmall, c.textMuted)
            }
            if (overrides.isNotEmpty()) NzButton("Reset all", { overrides.clear(); onChanged() }, kind = ButtonKind.QUIET)
            NzButton("Done", onClose, kind = ButtonKind.SECONDARY, icon = NzIcon.CLOSE)
        }
        Field("Find a setting", query, { query = it }, Modifier.fillMaxWidth(), placeholder = "seam, brim, gyroid, retraction…")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Scope.entries.forEach { s -> NzButton(s.label, { scope = s; changedOnly = false; groupId = catalog.groups.first { it.scope == s }.id },
                kind = if (scope == s && !searching && !changedOnly) ButtonKind.PRIMARY else ButtonKind.SECONDARY) }
            Spacer(Modifier.weight(1f))
            NzButton("Changed (${overrides.size})", { changedOnly = !changedOnly }, kind = if (changedOnly) ButtonKind.PRIMARY else ButtonKind.QUIET, enabled = overrides.isNotEmpty() || changedOnly)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Show", Nz.type.label, c.textMuted)
            Detail.entries.forEach { d -> NzButton(d.label, { detail = d }, kind = if (detail == d) ButtonKind.PRIMARY else ButtonKind.QUIET) }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Group list: hidden while searching or showing only changes, which span groups.
            if (!searching && !changedOnly) {
                Column(Modifier.width(190.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    catalog.groups.filter { it.scope == scope }.forEach { g ->
                        val changed = g.settings.count { it.key in overrides }
                        val selected = g.id == groupId
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) c.accent.copy(alpha = 0.16f) else c.surface)
                            .clickable { groupId = g.id }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(g.title, Nz.type.body, if (selected) c.accent else c.text, modifier = Modifier.weight(1f))
                            if (changed > 0) Txt("$changed", Nz.type.label, c.accent)
                        }
                    }
                }
            }
            val listState = rememberLazyListState()
            LaunchedEffect(groupId, query, changedOnly) { listState.scrollToItem(0) }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!searching && !changedOnly) catalog.groups.firstOrNull { it.id == groupId }?.let { g ->
                    item(key = "summary-${g.id}") { Txt(g.summary, Nz.type.bodySmall, c.textMuted) }
                }
                if (rows.isEmpty()) item(key = "empty") {
                    Txt(when { searching -> "No setting matches “$query” at this detail level. Try Everything."; changedOnly -> "Nothing is changed yet."
                        else -> "Nothing here at this detail level. Choose More or Everything." }, Nz.type.body, c.textMuted)
                }
                items(rows, key = { "${it.second.scope.id}:${it.second.key}" }) { (g, s) ->
                    SettingRow(s, if (searching || changedOnly) g.title else null, profile[s.key] ?: s.default, overrides[s.key],
                        onSet = { v -> if (v == null) overrides.remove(s.key) else overrides[s.key] = v; onChanged() })
                }
            }
        }
    }
}

@Composable
private fun SettingRow(s: SettingDef, groupTitle: String?, profileValue: String?, override: String?, onSet: (String?) -> Unit) {
    val c = Nz.colors
    val current = override ?: profileValue ?: ""
    var showHelp by remember(s.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceRaised).border(1.dp, if (override != null) c.accent.copy(alpha = 0.6f) else c.line, RoundedCornerShape(12.dp))
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Txt(s.label + (s.units?.let { "  ($it)" } ?: ""), Nz.type.label)
                groupTitle?.let { Txt(it, Nz.type.bodySmall, c.textMuted) }
            }
            if (override != null) {
                Pill("Changed", c.accent)
                NzButton("Reset", { onSet(null) }, kind = ButtonKind.QUIET)
            }
        }
        // Setting a value equal to the profile's clears the change rather than storing a copy.
        val set: (String) -> Unit = { v -> onSet(if (v == profileValue) null else v) }
        SettingControl(s, current, set)
        if (override != null && profileValue != null) Txt("Profile value: ${s.display(profileValue).ifBlank { "(empty)" }.take(80)}", Nz.type.bodySmall, c.textMuted)
        s.help?.let { help ->
            val short = help.substringBefore(". ").let { if (it.length < help.length) "$it." else it }
            Txt(if (showHelp) help else short, Nz.type.bodySmall, c.textMuted)
            if (short.length < help.length) Txt(if (showHelp) "Less" else "More about this", Nz.type.label, c.accent, modifier = Modifier.clickable { showHelp = !showHelp })
        }
    }
}

@Composable
private fun SettingControl(s: SettingDef, serialized: String, set: (String) -> Unit) {
    val c = Nz.colors
    if (s.readonly) { Txt(s.display(serialized), Nz.type.body, c.textMuted); return }
    when {
        s.type == "bool" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NzButton("On", { set("1") }, kind = if (serialized == "1") ButtonKind.PRIMARY else ButtonKind.SECONDARY)
            NzButton("Off", { set("0") }, kind = if (serialized != "1") ButtonKind.PRIMARY else ButtonKind.SECONDARY)
        }
        s.type == "enum" && s.choices.size <= 4 && !s.openChoices -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.choices.forEach { ch -> NzButton(ch.label, { set(ch.value) }, kind = if (serialized == ch.value) ButtonKind.PRIMARY else ButtonKind.SECONDARY) }
        }
        s.type == "enum" && !s.openChoices -> NzSelect(s.label, s.choices, serialized) { set(it) }
        else -> SettingText(s, serialized, multiline = s.code || s.multiline, set = set)
    }
}

/** A text/number box that stores the value as soon as it's valid and explains when it isn't. */
@Composable
private fun SettingText(s: SettingDef, serialized: String, multiline: Boolean, set: (String) -> Unit) {
    val c = Nz.colors
    var text by remember(s.key, serialized) { mutableStateOf(s.display(serialized)) }
    val problem = s.problem(text)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().then(if (multiline) Modifier.heightIn(min = 90.dp, max = 220.dp) else Modifier).clip(shape).background(c.surfaceSunken)
            .border(if (focused) 2.dp else 1.dp, if (problem != null) c.danger else if (focused) c.focus else c.lineStrong, shape).padding(horizontal = 10.dp, vertical = 8.dp)) {
            BasicTextField(text, { t -> text = t; if (s.problem(t) == null) set(s.serialize(t)) },
                Modifier.fillMaxWidth().semantics { contentDescription = s.label }.then(if (multiline) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                textStyle = (if (s.code) Nz.type.metricSmall else Nz.type.body).copy(color = c.text), cursorBrush = SolidColor(c.accent), singleLine = !multiline,
                interactionSource = interaction)
        }
        problem?.let { Txt(it, Nz.type.bodySmall, c.danger) }
        if (s.isList && s.type != "strings") Txt("One value per toolhead or material, separated by commas.", Nz.type.bodySmall, c.textMuted)
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
