package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File

@Composable fun LiveFilePanel(state:ScreenState,workspace:FileWorkspace,refresh:()->Unit,close:()->Unit,factory:(String,File)->LiveFileBackend={a,f->LiveFileChanges(a,f)}) {
    val context=LocalContext.current
    val api=remember(state.address,state.generation){factory(state.address,File(context.cacheDir,"live-file-changes"))}
    val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current.lifecycle
    var operation by remember {mutableStateOf(LiveFileChanges.Operation.UPLOAD)}
    var source by remember {mutableStateOf("")};var name by remember {mutableStateOf("upload.gcode")}
    var deleteConsent by remember {mutableStateOf(false)}
    var draft by remember {mutableStateOf<LiveFileChanges.Draft?>(null)}
    var notice by remember {mutableStateOf(api.recoveryNotice())}
    var busy by remember {mutableStateOf(false)};var foreground by remember {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    var job by remember {mutableStateOf<Job?>(null)};var epoch by remember {mutableIntStateOf(0)}
    fun cancel(){epoch++;job?.cancel();api.cancel();draft=null;deleteConsent=false;busy=false;notice="Cancelled. A dispatched operation may already have completed. Inspect the last destination before trying again. ${api.recoveryNotice()}"}
    fun invalidate(){api.cancel();draft=null;deleteConsent=false;notice="";epoch++}
    DisposableEffect(api,lifecycle){
        val observer=LifecycleEventObserver {_,event->foreground=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED);if(event==Lifecycle.Event.ON_STOP)cancel()}
        lifecycle.addObserver(observer)
        onDispose{job?.cancel();api.close();lifecycle.removeObserver(observer)}
    }
    var previousState by remember {mutableStateOf(Triple(state.connected,state.snapshot?.state,state.generation))}
    LaunchedEffect(state.connected,state.snapshot?.state,state.generation){val next=Triple(state.connected,state.snapshot?.state,state.generation);if(next!=previousState){cancel();previousState=next}}
    val enabled=foreground&&state.connected&&state.snapshot?.ready==true&&state.snapshot.state in setOf("standby","complete","cancelled")&&!state.busy&&!busy
    AlertDialog(onDismissRequest={cancel();close()},title={Text("Printer file changes")},confirmButton={TextButton({cancel();close()}){Text("Close")}},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Printer: ${state.address}")
            Text(if(operation==LiveFileChanges.Operation.DELETE)"Delete a G-code file from this printer. Review the exact path before confirming." else "Uploads and renames use a unique filename. The full destination appears before confirmation. No print is started.")
            FlowRow{LiveFileChanges.Operation.entries.forEach{op->FilterChip(operation==op,{invalidate();operation=op},enabled=!busy,label={Text(op.name.lowercase().replaceFirstChar{it.uppercase()})})}}
            if(operation==LiveFileChanges.Operation.UPLOAD)Text("Workspace: ${workspace.name.ifBlank{"Import or download a G-code file first"}}")
            else OutlinedTextField(source,{source=it;invalidate()},enabled=!busy,label={Text("Existing printer file path")},modifier=Modifier.testTag("live-file-source"))
            if(operation!=LiveFileChanges.Operation.DELETE)OutlinedTextField(name,{name=it;invalidate()},enabled=!busy,label={Text("Preferred destination filename")},modifier=Modifier.testTag("live-file-name"))
            Button({
                invalidate();busy=true;val ticket=epoch;val op=operation;val src=source;val requested=name;val local=workspace.localFile
                job=scope.launch{try{val result=withContext(Dispatchers.IO){api.prepare(op,src,requested,local)};ensureActive();if(ticket==epoch)draft=result}
                    catch(e:CancellationException){throw e}catch(e:Exception){if(ticket==epoch)notice=e.message?:"Cannot review file change."}finally{if(ticket==epoch)busy=false}}
            },enabled=enabled&&!workspace.loading&&(operation!=LiveFileChanges.Operation.UPLOAD||workspace.localFile!=null),modifier=Modifier.testTag("live-file-review")){Text("Review file change")}
            draft?.let{d->
                Text("${d.operation.name.lowercase().replaceFirstChar{it.uppercase()}} ${if(d.operation==LiveFileChanges.Operation.UPLOAD)workspace.name else d.source}"+(if(d.operation==LiveFileChanges.Operation.DELETE)"" else "\nTo: ${d.destination}")+"\n${d.bytes} bytes",modifier=Modifier.testTag("live-file-draft"))
                if(d.operation!=LiveFileChanges.Operation.DELETE)Text("Unique names reduce collisions. The printer cannot guarantee protection against another client writing the exact same destination concurrently.")
                if(d.operation==LiveFileChanges.Operation.RENAME)Text("Renaming removes the old path. A file changed by another client during the operation may require recovery.")
                if(d.operation==LiveFileChanges.Operation.DELETE){
                    Text("This permanently removes the file from the printer. There is no undo. Do not let another app modify this path during deletion; the printer cannot lock the reviewed version.")
                    Row {Checkbox(deleteConsent,{deleteConsent=it},enabled=!busy,modifier=Modifier.testTag("live-file-delete-consent"));Text("I understand this permanently deletes the reviewed file.")}
                }
                Button({draft=null;busy=true;val ticket=epoch;notice="Sending once; keep this screen open.";job=scope.launch{
                    try{val result=withContext(Dispatchers.IO){api.confirm(d.id)};ensureActive();if(ticket==epoch){notice=result;refresh()}}
                    catch(e:CancellationException){throw e}catch(e:Exception){if(ticket==epoch){notice=e.message?:"Outcome unknown; inspect files.";refresh()}}finally{if(ticket==epoch)busy=false}
                }},enabled=enabled&&(d.operation!=LiveFileChanges.Operation.DELETE||deleteConsent),modifier=Modifier.testTag("live-file-confirm")){Text(if(d.operation==LiveFileChanges.Operation.DELETE)"Confirm deletion" else "Confirm file change")}
            }
            if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(::cancel){Text("Cancel file change")}}
            TextButton({refresh();notice=api.recoveryNotice()},enabled=!busy){Text("Refresh printer files")}
            if(notice.isNotBlank())Text(notice,modifier=Modifier.testTag("live-file-notice"))
        }
    })
}
