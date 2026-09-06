package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun DashboardEditor(options:DashboardOptions, save:(DashboardOptions)->Unit, close:()->Unit) {
    AlertDialog(onDismissRequest=close,title={Text("Customize dashboard")},confirmButton={TextButton(close){Text("Done")}},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Changes save automatically on this phone. Connection status and command feedback always remain visible.")
            Text("Theme",style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { DashboardOptions.modes.forEach { mode -> FilterChip(options.mode==mode,{save(options.copy(mode=mode))},label={Text(mode)}) } }
            Text("Accent",style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { DashboardOptions.accents.forEach { accent -> FilterChip(options.accent==accent,{save(options.copy(accent=accent))},label={Text(accent)}) } }
            Text("Cards",style=MaterialTheme.typography.titleSmall)
            options.order.forEachIndexed { index, card ->
                Row { Checkbox(card !in options.hidden,{show->save(options.copy(hidden=if(show)options.hidden-card else options.hidden+card))}); Text(card,Modifier.padding(top=12.dp)) }
                FlowRow {
                    TextButton({save(options.move(card,-1))},enabled=index>0){Text("Move $card up")}
                    TextButton({save(options.move(card,1))},enabled=index<options.order.lastIndex){Text("Move $card down")}
                }
            }
            Text("Layouts",style=MaterialTheme.typography.titleSmall)
            TextButton({save(options.copy(order=DashboardOptions.cards,hidden=emptySet()))}){Text("Default layout")}
            TextButton({save(options.copy(order=listOf("Print","Temperatures","Camera","Quick tools"),hidden=setOf("Quick tools")))}){Text("Print focused")}
            TextButton({save(DashboardOptions())}){Text("Reset appearance")}
        }
    })
}
