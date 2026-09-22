package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The full macro inventory the printer's own configuration provides. Owner request, 2026-09-22:
 * the Control tab should show dedicated, protocol-verified everyday controls, not a raw dump of
 * arbitrary macro names this app has no way to know the meaning of - so the Control tab now only
 * shows macros explicitly favorited here, and this became the one place to browse, search,
 * organize and run the rest. Purely a presentation change: nothing here deletes or alters a
 * printer macro.
 */
@Composable fun MacrosBrowserPanel(
    macros: List<String>,
    macroOptions: Map<String, MacroOptions>,
    saveMacro: (String, MacroOptions) -> Unit,
    refresh: () -> Unit,
    edit: (String) -> Unit,
    prepare: (String) -> Unit,
    close: () -> Unit,
) {
    var macroFilter by remember { mutableStateOf("") }
    var showHiddenMacros by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text("Advanced macros") },
        confirmButton = { TextButton(close) { Text("Close") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This is the full list of macros your printer's own configuration provides - nothing here was removed or changed. Commonly used operations (heating, fans, lights, tools, bed mesh and the rest) get their own dedicated controls on the Control tab instead of relying on a macro's name to guess what it does. Favorite a macro below to also show it there.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("macros-browser-notice"))
                OutlinedTextField(macroFilter, {macroFilter=it}, label={Text("Search macros or groups")}, modifier=Modifier.fillMaxWidth().testTag("macros-search"))
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(showHiddenMacros,{showHiddenMacros=!showHiddenMacros},label={Text("Show hidden")},modifier=Modifier.testTag("show-hidden-macros"))
                    TextButton(refresh) {Text("Refresh lists")}
                }
                if (macros.isEmpty()) Text("No available macros. Connect to a ready printer, then refresh.")
                val visible = macros.filter {(it.contains(macroFilter,true)||(macroOptions[it]?.group?:"").contains(macroFilter,true))&&(showHiddenMacros||macroOptions[it]?.hidden!=true)}
                val favorites = visible.filter {macroOptions[it]?.favorite==true}.sorted()
                val grouped = visible.filterNot {macroOptions[it]?.favorite==true}.groupBy {macroOptions[it]?.group?.ifBlank{null}?:"General"}.toSortedMap()
                if (favorites.isNotEmpty()) {
                    Text("Favorites", style=MaterialTheme.typography.titleSmall)
                    favorites.forEach { macro -> MacroBrowserCard(macro, macroOptions[macro]?:MacroOptions(), saveMacro, edit, prepare) }
                }
                grouped.forEach { (group, inGroup) ->
                    Text(group, style=MaterialTheme.typography.titleSmall)
                    inGroup.sorted().forEach { macro -> MacroBrowserCard(macro, macroOptions[macro]?:MacroOptions(), saveMacro, edit, prepare) }
                }
            }
        })
}

@Composable private fun MacroBrowserCard(macro: String, options: MacroOptions, saveMacro: (String, MacroOptions) -> Unit, edit: (String) -> Unit, prepare: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
        Text(macro,style=MaterialTheme.typography.titleMedium)
        if(options.group.isNotBlank())Text(options.group)
        if(options.hidden)Text("Hidden from the default list",style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton({saveMacro(macro,options.copy(favorite=!options.favorite))}){Text(if(options.favorite)"Unfavorite" else "Favorite")}
            TextButton({edit(macro)}){Text("Organize")}
            TextButton({saveMacro(macro,options.copy(hidden=!options.hidden))},modifier=Modifier.testTag("hide-macro-$macro")){Text(if(options.hidden)"Unhide" else "Hide")}
            if(LIVE_HEATER_FAN_CONTROLS_ENABLED && !options.hidden) Button({prepare(macro)}){Text("Run")}
        }
    }}
}
