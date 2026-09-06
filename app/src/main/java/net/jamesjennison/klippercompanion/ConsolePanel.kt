package net.jamesjennison.klippercompanion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*

@Composable fun ConsolePanel(address:String,connected:Boolean,close:()->Unit,factory:(String)->ConsoleReader={Moonraker(it)},copyOverride:((String)->Unit)?=null) {
    var batch by remember(address) {mutableStateOf(ConsoleBatch(emptyList()))}
    var note by remember(address) {mutableStateOf("Reading recent messages…")}
    var paused by remember(address) {mutableStateOf(false)}
    var query by remember(address) {mutableStateOf("")}
    var errorsOnly by remember(address) {mutableStateOf(false)}
    var copied by remember(address) {mutableStateOf(false)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val context=LocalContext.current
    val list=rememberLazyListState()
    val visible=remember(batch,query,errorsOnly){ConsoleLog.filter(batch.entries,query,errorsOnly)}
    LaunchedEffect(address,connected,paused,lifecycle) {
        if(!connected){batch=ConsoleBatch(emptyList());note="Disconnected. Connect to read console messages.";return@LaunchedEffect}
        if(paused)return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val api=try{factory(address)}catch(_:Exception){note="Console unavailable. Check the printer connection.";return@repeatOnLifecycle}
            try {
                while(isActive) {
                    try {
                        val result=withContext(Dispatchers.IO){api.console()};ensureActive()
                        batch=result;note="Recent cache · refreshed ${java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))}"
                    }catch(e:CancellationException){throw e}catch(_:Exception){note="Console unavailable. Displayed messages may be stale; retrying while visible."}
                    delay(3000)
                }
            }finally{api.close()}
        }
    }
    LaunchedEffect(batch,query,errorsOnly){copied=false;if(!paused && visible.isNotEmpty())list.scrollToItem(0)}
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Read-only console",style=MaterialTheme.typography.titleLarge)
                Text("$address\nRecent Moonraker cache, up to 200 entries. Not a complete log. Newest first.",style=MaterialTheme.typography.bodySmall)
                Text(if(paused && connected)"Paused view — updates and automatic scrolling stopped." else note,modifier=Modifier.testTag("console-status"),style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(query,{query=it.take(128)},label={Text("Search messages")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("console-search"))
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(errorsOnly,{errorsOnly=!errorsOnly},label={Text("Errors only")},modifier=Modifier.testTag("console-errors"))
                    OutlinedButton({paused=!paused},enabled=connected){Text(if(paused)"Resume updates" else "Pause updates")}
                }
                Text("${visible.size} matching messages · Error filter matches response text containing ‘error’ or starting with !!.",style=MaterialTheme.typography.bodySmall)
                if(batch.truncated)Text("Messages truncated for display limits.",style=MaterialTheme.typography.bodySmall)
                if(visible.isEmpty())Text("No matching cached messages.")
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("console-list"),state=list,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    items(visible){entry->Text(ConsoleLog.line(entry),fontFamily=FontFamily.Monospace,color=if(entry.error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.bodySmall)}
                }
                if(copied)Text("Copied matching messages (up to 65,536 characters).",style=MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton({val text=ConsoleLog.copy(visible);if(copyOverride!=null)copyOverride(text)else(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Printer console",text));copied=true},enabled=visible.isNotEmpty(),modifier=Modifier.testTag("console-copy")){Text("Copy matching")}
                    TextButton(close){Text("Close")}
                }
            }
        }
    }
}
