package com.nozzleitall.desktop.prepare

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.settings.Choice
import com.nozzleitall.desktop.settings.DenseRow
import com.nozzleitall.desktop.settings.DenseSelect
import com.nozzleitall.desktop.settings.ProfileValues
import com.nozzleitall.desktop.ui.*

/**
 * The selected object's own settings, as Orca's object list edits them: each one overrides the plate's value for this
 * object only (reset removes it), and "Add a setting" offers upstream's per-object menu (ObjectSettings) by category.
 */
@Composable
fun ObjectSettingsSection(p: PrepareState, item: PrepItem, profile: ProfileValues) {
    val c = Nz.colors
    val categories = remember { ObjectSettings.categories() }
    val byKey = remember { ObjectSettings.all().associateBy { it.key } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Settings for this object", Nz.type.label, modifier = Modifier.weight(1f))
            if (item.settings.isNotEmpty()) Txt("${item.settings.size} changed", Nz.type.bodySmall, c.accent)
        }
        if (item.settings.isEmpty()) Txt("It prints with the plate's settings. Add one to change it for this object only.", Nz.type.bodySmall, c.textMuted)
        item.settings.keys.sorted().forEach { key ->
            val def = byKey[key] ?: return@forEach
            val plate = p.setting(key, profile)
            DenseRow(def, item.settings[key], plate, true) { v ->
                if (v == null) item.settings.remove(key) else item.settings[key] = v
                p.changed()
            }
        }
        val choices = categories.flatMap { (cat, defs) -> defs.filter { it.key !in item.settings }.map { Choice(it.key, "$cat · ${it.label}") } }
        if (choices.isNotEmpty()) DenseSelect("Add a setting for this object", listOf(Choice("", "Add a setting…")) + choices, "", Modifier.fillMaxWidth()) { key ->
            val def = byKey[key] ?: return@DenseSelect
            // Starts from the plate's value, as upstream copies the current value when a setting is added to an object.
            item.settings[key] = p.setting(key, profile) ?: def.default ?: ""
            p.changed()
        }
    }
}
