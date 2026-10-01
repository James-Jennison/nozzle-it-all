package net.jamesjennison.klippercompanion

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Saved CustomProfiles, one JSON string per name in app-private preferences. */
class CustomProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("custom_slice_profiles", Context.MODE_PRIVATE)
    fun all(): List<CustomProfile> = prefs.all.values.mapNotNull { (it as? String)?.let(CustomProfile::decode) }.sortedBy { it.name.lowercase() }
    fun save(profile: CustomProfile) { prefs.edit().putString(profile.name, profile.encode()).apply() }
    fun delete(name: String) { prefs.edit().remove(name).apply() }
}

/**
 * Phase 9a: searchable, tiered advanced controls with saved/inheriting profiles and compare.
 * The overrides apply on top of the print profile [basePreset] (a ProcessPreset name, null for the pack default), whose
 * own values ([baseValues], setting key to value) each field shows as its placeholder. A saved profile records its
 * print profile; applying it also selects that print profile through [onSelectPreset] when this printer offers it.
 */
@Composable
fun AdvancedSettingsPanel(
    overrides: Map<String, String>, printerKey: String, family: MultiToolFamily? = null,
    basePreset: String? = null, baseValues: Map<String, String> = emptyMap(), presetLabel: (String) -> String? = { null }, onSelectPreset: (String) -> Unit = {},
    onOverridesChange: (Map<String, String>) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { CustomProfileStore(context) }
    var profiles by remember { mutableStateOf(store.all()) }
    var tier by remember { mutableStateOf(SettingTier.BASIC) }
    var query by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf("") }
    var compareWith by remember { mutableStateOf<CustomProfile?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("advanced-settings")) {
        Text("Advanced settings", style = MaterialTheme.typography.titleSmall)
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingTier.entries.forEach { t -> FilterChip(tier == t, { tier = t }, label = { Text(t.label) }, modifier = Modifier.testTag("advanced-tier-${t.name.lowercase()}")) }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Search settings") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("advanced-search"))
        val visible = SettingsCatalog.visible(tier, query, family)
        if (visible.isEmpty()) Text("No settings match.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("advanced-empty"))
        visible.groupBy { it.group }.forEach { (group, defs) ->
            Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            defs.forEach { def -> SettingRow(def, overrides[def.key], baseValues[def.key]) { value -> onOverridesChange(if (value == null) overrides - def.key else overrides + (def.key to value)) } }
        }
        HorizontalDivider()
        Text("Profiles", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(profileName, { profileName = it }, label = { Text("Save current as…") }, singleLine = true, modifier = Modifier.weight(1f).testTag("advanced-profile-name"))
            Button({ store.save(CustomProfile(profileName.trim(), printerKey, overrides, basePreset)); profileName = ""; profiles = store.all() }, enabled = profileName.isNotBlank() && overrides.isNotEmpty(), modifier = Modifier.testTag("advanced-profile-save")) { Text("Save") }
        }
        profiles.forEach { p ->
            Column {
                Text("${p.name} (${p.overrides.size})", maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                p.basePreset?.let { b -> Text("Based on ${presetLabel(b) ?: b.substringBefore(" @")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ onOverridesChange(p.overrides); p.basePreset?.takeIf { presetLabel(it) != null }?.let(onSelectPreset) }, modifier = Modifier.testTag("advanced-profile-apply-${p.name}")) { Text("Apply") }
                TextButton({ compareWith = if (compareWith == p) null else p }, modifier = Modifier.testTag("advanced-profile-compare-${p.name}")) { Text("Compare") }
                TextButton({ store.delete(p.name); profiles = store.all(); if (compareWith == p) compareWith = null }) { Text("Delete") }
            }
                }
        }
        compareWith?.let { other ->
            val diff = compareOverrides(overrides, other.overrides)
            Text(if (diff.isEmpty()) "Identical to ${other.name}." else "Current vs ${other.name}:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("advanced-compare"))
            diff.forEach { d -> Text("${SettingsCatalog.get(d.key)?.label ?: d.key}: ${d.left ?: "default"} → ${d.right ?: "default"}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun SettingRow(def: SettingDef, value: String?, base: String?, onChange: (String?) -> Unit) {
    var text by remember(def.key, value) { mutableStateOf(value ?: "") }
    Column(Modifier.fillMaxWidth().testTag("setting-${def.key}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(def.label + if (value != null) " •" else "", modifier = Modifier.weight(1f))
            if (value != null) TextButton({ onChange(null) }, modifier = Modifier.testTag("setting-reset-${def.key}")) { Text("Reset") }
        }
        when (val t = def.type) {
            SettingType.Toggle -> Switch(value == "1", { onChange(if (it) "1" else "0") }, modifier = Modifier.testTag("setting-input-${def.key}"))
            is SettingType.Choice -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                t.options.forEach { (label, v) -> FilterChip(value == v, { onChange(v) }, label = { Text(label) }) }
            }
            else -> {
                val invalid = text.isNotBlank() && SettingsCatalog.validate(def, text) == null
                OutlinedTextField(text, { text = it; SettingsCatalog.validate(def, it)?.let(onChange) }, singleLine = true, isError = invalid,
                    placeholder = { Text(base?.takeIf { it.isNotBlank() }?.let { "$it (print profile)" } ?: "profile default") }, suffix = { Text(unitOf(t)) },
                    supportingText = { Text(if (invalid) "Enter ${rangeOf(t)}" else def.help) }, modifier = Modifier.fillMaxWidth().testTag("setting-input-${def.key}"))
            }
        }
        if (def.type is SettingType.Toggle || def.type is SettingType.Choice) Text(def.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun unitOf(t: SettingType) = when (t) { is SettingType.IntRange -> t.unit; is SettingType.Decimal -> t.unit; else -> "" }
private fun rangeOf(t: SettingType) = when (t) { is SettingType.IntRange -> "${t.min}–${t.max}"; is SettingType.Decimal -> "${t.min}–${t.max}"; is SettingType.Percent -> "${t.min}–${t.max}%"; else -> "a valid value" }
