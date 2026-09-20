package net.jamesjennison.klippercompanion

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable fun ProfileEditor(profile: PrinterProfile, close: ()->Unit, save: (String,String,String,String,PrinterKind,String)->String?) {
    var name by remember(profile) { mutableStateOf(profile.name) }
    var address by remember(profile) { mutableStateOf(profile.address) }
    // For a BAMBU_LAB profile this same field holds the access code from the printer's own screen -
    // same class of secret (a control-granting credential), so it uses the same encrypted slot.
    var apiKey by remember(profile) { mutableStateOf(profile.apiKey) }
    var serial by remember(profile) { mutableStateOf(profile.serial) }
    var kind by remember(profile) { mutableStateOf(profile.kind) }
    var showKey by remember(profile) { mutableStateOf(false) }
    var showRemoteHelp by remember(profile) { mutableStateOf(false) }
    var error by remember(profile) { mutableStateOf<String?>(null) }
    if(showRemoteHelp) RemoteAccessHelpPanel { showRemoteHelp=false }
    AlertDialog(onDismissRequest=close,title={Text("Edit printer")},text={ Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name,{name=it.take(80)},label={Text("Printer name")},singleLine=true)
        if(kind==PrinterKind.BAMBU_LAB) {
            OutlinedTextField(address,{address=it},label={Text("Printer IP address")},placeholder={Text("192.168.1.50")},singleLine=true)
            Text("The address shown on the printer's network screen, with no http:// prefix.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(serial,{serial=it.take(40)},label={Text("Serial number")},singleLine=true,modifier=Modifier.testTag("bambu-serial"))
            OutlinedTextField(apiKey,{apiKey=it.take(200)},label={Text("Access code")},singleLine=true,modifier=Modifier.testTag("bambu-access-code"),
                visualTransformation=if(showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon={TextButton({showKey=!showKey}){Text(if(showKey) "Hide" else "Show")}})
            Text("Serial number and access code both come from the printer's own network settings. LAN mode must be on.",style=MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(address,{address=it},label={Text("Printer address")},singleLine=true)
            Text("A Tailscale address (100.x.x.x or *.ts.net), Cloudflare Tunnel or other https:// address also works away from home.",style=MaterialTheme.typography.bodySmall)
            TextButton({showRemoteHelp=true},modifier=Modifier.testTag("open-remote-access-help")){Text("How do I connect away from home?")}
            OutlinedTextField(apiKey,{apiKey=it.take(200)},label={Text("API key (optional)")},singleLine=true,
                visualTransformation=if(showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon={TextButton({showKey=!showKey}){Text(if(showKey) "Hide" else "Show")}})
            Text("Only needed if Moonraker requires authentication; copy it from Fluidd's or Mainsail's settings.",style=MaterialTheme.typography.bodySmall)
        }
        Text("Printer type",style=MaterialTheme.typography.labelLarge)
        Text("Generic Klipper and Snapmaker U1 both talk to Moonraker and differ only in which extra vendor controls appear. Bambu Lab is a different protocol entirely, with its own fields above.",style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(kind==PrinterKind.GENERIC_KLIPPER,{kind=PrinterKind.GENERIC_KLIPPER},label={Text("Generic Klipper")})
            FilterChip(kind==PrinterKind.SNAPMAKER_U1_PAXX,{kind=PrinterKind.SNAPMAKER_U1_PAXX},label={Text("Snapmaker U1 (PAXX)")})
            FilterChip(kind==PrinterKind.BAMBU_LAB,{kind=PrinterKind.BAMBU_LAB},label={Text("Bambu Lab")})
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        Text("Changing the address disconnects the active printer.")
    } },confirmButton={TextButton({error=save(profile.address,address,name,apiKey,kind,serial);if(error==null) close()},enabled=address.isNotBlank()) {Text("Save")}},dismissButton={TextButton(close){Text("Cancel")}})
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
