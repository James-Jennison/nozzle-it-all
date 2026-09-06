package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Exercises file workflow against a local in-memory server only. */
@Composable fun FileChangePanel(close:()->Unit) {
    val server=remember {FileChangeSimulation()}
    DisposableEffect(server){onDispose{server.close()}}
    val scope=rememberCoroutineScope()
    var listing by remember {mutableStateOf(server.list())}
    var operation by remember {mutableStateOf(FileChangeSimulation.Operation.UPLOAD)}
    var source by remember {mutableStateOf("example.gcode")}
    var destination by remember {mutableStateOf("uploaded.gcode")}
    var fault by remember {mutableStateOf(FileChangeSimulation.Fault.NONE)}
    var draft by remember {mutableStateOf<FileChangeSimulation.Draft?>(null)}
    var notice by remember {mutableStateOf("")}
    var refreshRequired by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var progress by remember {mutableIntStateOf(0)}
    var job by remember {mutableStateOf<Job?>(null)}
    fun invalidate() {server.cancel();draft=null;notice=""}
    fun cancel() {job?.cancel();server.cancel();draft=null;busy=false;notice="Cancelled before commit. Simulated files unchanged."}
    AlertDialog(onDismissRequest={if(busy)cancel();close()},title={Text("Preview file management")},confirmButton={TextButton({if(busy)cancel();close()}){Text("Close")}},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Simulation only. Uses disposable example files on this phone. Your printer files are never changed.")
            Text("Simulated files (revision ${listing.revision})",style=MaterialTheme.typography.titleSmall)
            listing.names.forEach {Text(it)}
            TextButton({listing=server.list();refreshRequired=false;invalidate();notice="Simulated file list refreshed."},enabled=!busy){Text("Refresh simulated files")}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {FileChangeSimulation.Operation.entries.forEach {op->FilterChip(operation==op,{invalidate();operation=op},enabled=!busy,label={Text(op.name.lowercase().replaceFirstChar{it.uppercase()})})}}
            if(operation!=FileChangeSimulation.Operation.UPLOAD) OutlinedTextField(source,{source=it;invalidate()},label={Text("Source file")},singleLine=true,enabled=!busy,modifier=Modifier.testTag("file-change-source"))
            if(operation!=FileChangeSimulation.Operation.DELETE) OutlinedTextField(destination,{destination=it;invalidate()},label={Text("Destination file")},singleLine=true,enabled=!busy,modifier=Modifier.testTag("file-change-destination"))
            if(operation==FileChangeSimulation.Operation.UPLOAD)Text("Uploads a built-in 10-byte G-code example; no personal document is read.")
            Text("Simulate a failure",style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(FileChangeSimulation.Fault.NONE to "None",FileChangeSimulation.Fault.INTERRUPTED to "Interrupted",FileChangeSimulation.Fault.LOST_ACK to "Lost acknowledgement").forEach {(f,label)->FilterChip(fault==f,{fault=f;invalidate()},enabled=!busy,label={Text(label)})}
            }
            Button({invalidate();runCatching{server.prepare(operation,source,destination,if(operation==FileChangeSimulation.Operation.UPLOAD)"G90\nG1 X1\n".toByteArray()else null)}.onSuccess{draft=it}.onFailure{notice=it.message?:"Cannot prepare operation."}},enabled=!busy&&!refreshRequired,modifier=Modifier.testTag("review-file-change")){Text("Review operation")}
            draft?.let {d->
                Text(when(d.operation){FileChangeSimulation.Operation.UPLOAD->"Upload example (${d.bytes} bytes) to ${d.destination}?";FileChangeSimulation.Operation.RENAME->"Rename ${d.source} to ${d.destination}?";FileChangeSimulation.Operation.DELETE->"Delete ${d.source}? This removes the simulated file."})
                TextButton({runCatching{server.simulateOtherClient()}.onSuccess{notice="Another simulated client changed the list."}.onFailure{notice=it.message?:"Simulated change rejected."}},enabled=!busy){Text("Simulate concurrent change")}
                Button({busy=true;progress=0;val selectedFault=fault;job=scope.launch{
                    repeat(10){delay(100);progress=it+1}
                    runCatching{server.confirm(d.id,selectedFault)}.onSuccess{notice=it;listing=server.list()}.onFailure{refreshRequired=true;notice=it.message?:"Outcome unknown. Refresh before trying again."}
                    draft=null;busy=false
                }},enabled=!busy,modifier=Modifier.testTag("confirm-file-change")){Text("Confirm simulated operation")}
            }
            if(busy){LinearProgressIndicator(progress={progress/10f},modifier=Modifier.fillMaxWidth());TextButton(::cancel,modifier=Modifier.testTag("cancel-file-change")){Text("Cancel operation")}}
            if(notice.isNotBlank())Text(notice,modifier=Modifier.testTag("file-change-notice"))
            Text("The simulator rejects stale confirmations atomically. Moonraker does not promise this protection: live overwrite handling and idle-printer acceptance remain pending.",style=MaterialTheme.typography.bodySmall)
        }
    })
}
