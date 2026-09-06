package net.jamesjennison.klippercompanion

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable fun ProfileEditor(profile: PrinterProfile, close: ()->Unit, save: (String,String,String)->String?) {
    var name by remember(profile) { mutableStateOf(profile.name) }
    var address by remember(profile) { mutableStateOf(profile.address) }
    var error by remember(profile) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=close,title={Text("Edit printer")},text={ Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name,{name=it.take(80)},label={Text("Printer name")},singleLine=true)
        OutlinedTextField(address,{address=it},label={Text("Printer address")},singleLine=true)
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        Text("Changing the address disconnects the active printer.")
    } },confirmButton={TextButton({error=save(profile.address,address,name);if(error==null) close()},enabled=address.isNotBlank()) {Text("Save")}},dismissButton={TextButton(close){Text("Cancel")}})
}
@Composable fun FileDetails(state: ScreenState) {
    if(state.fileLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.fileMetadata?.let { m ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(m.filename,style=MaterialTheme.typography.titleMedium)
            state.thumbnail?.let { Image(it.asImageBitmap(),"Model thumbnail",Modifier.fillMaxWidth().heightIn(max=180.dp)) }
            Text("Slicer estimate: ${formatDuration(m.estimatedSeconds)}")
            Text("Layers: ${m.layers ?: "Unknown"} · Filament: ${formatMaterial(m.filamentMm)}")
            if(m.slicer.isNotBlank()) Text(m.slicer)
        } }
    }
    if(state.fileNote.isNotBlank()) Text(state.fileNote)
}
@Composable fun HistoryHeader(state: ScreenState, load: (Int)->Unit) {
    Text("Print history",style=MaterialTheme.typography.titleLarge)
    Text("Page of available Moonraker records. Summaries below cover only this page.")
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton({load(0)},enabled=state.connected&&!state.historyLoading,modifier=Modifier.testTag("refresh-history")){Text("Refresh history")}
        OutlinedButton({load((state.historyOffset-50).coerceAtLeast(0))},enabled=state.connected&&!state.historyLoading&&state.historyOffset>0){Text("Previous")}
        OutlinedButton({load(state.historyOffset+50)},enabled=state.connected&&!state.historyLoading&&state.historyPageSize>=50){Text("Next")}
    }
    if(state.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if(state.historyNote.isNotBlank()) Text(state.historyNote)
    if(state.history.isNotEmpty()) {
        Text("Records ${state.historyOffset+1}–${state.historyOffset+state.history.size} (this page)")
        val duration=state.history.mapNotNull { it.duration };val material=state.history.mapNotNull { it.filamentMm }
        Text("${state.history.count { it.status=="completed" }} completed · Recorded duration ${formatDuration(duration.takeIf { it.isNotEmpty() }?.sum())}")
        Text("Recorded filament ${formatMaterial(material.takeIf { it.isNotEmpty() }?.sum())}; missing measurements excluded.")
    } else if(!state.historyLoading && state.historyNote.isBlank()) Text("No records on this page. Refresh to read the latest history.")
}
