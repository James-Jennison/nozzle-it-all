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

@Composable fun LivePrintPreviewPanel(state:ScreenState,close:()->Unit) {
    val cache=LocalContext.current.cacheDir
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var foreground by remember{mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    var progress by remember(state.address){mutableStateOf<PrintFileProgress?>(null)}
    var path by remember(state.address){mutableStateOf<Toolpath?>(null)}
    var note by remember{mutableStateOf("Reading active file…")}
    var retry by remember{mutableIntStateOf(0)}
    var observed by remember{mutableLongStateOf(0)}
    val api=remember(state.address,foreground,state.connected){LivePrintPreview(state.address)}
    DisposableEffect(api,lifecycle){
        val observer=LifecycleEventObserver{_,_->
            foreground=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(!foreground){progress=null;api.close()}
        }
        lifecycle.addObserver(observer)
        onDispose{api.close();lifecycle.removeObserver(observer)}
    }
    // Expire the marker independently of a stalled network request.
    LaunchedEffect(observed){if(observed!=0L){delay(12_000);progress=null;note="File progress is stale; waiting for a fresh read."}}
    LaunchedEffect(api,state.connected,foreground,retry){
        progress=null;path=null
        if(!state.connected||!foreground){note="Tracking paused while disconnected or in the background.";return@LaunchedEffect}
        var identity:PrintFileIdentity?=null
        var failedIdentity:PrintFileIdentity?=null
        while(isActive){
            try {
                var fresh=withContext(Dispatchers.IO){api.progress()}
                if(identity!=fresh.identity){
                    require(fresh.identity!=failedIdentity){"Use Retry to download this file again."}
                    failedIdentity=fresh.identity
                    progress=null;path=null;note="Downloading active G-code for preview…"
                    val expected=fresh.identity;val job=currentCoroutineContext()
                    val loaded=withContext(Dispatchers.IO){api.load(expected,cache){!job.isActive}}
                    fresh=withContext(Dispatchers.IO){api.progress()}
                    require(fresh.identity==expected){"Active file changed while parsing."}
                    path=loaded;identity=expected;failedIdentity=null
                }
                progress=fresh;observed=android.os.SystemClock.elapsedRealtime()
                note="${fresh.state} · reported byte ${fresh.position} / ${fresh.identity.size}"
            } catch(e:CancellationException){throw e}
            catch(_:Exception){progress=null;path=null;identity=null;note="Live preview unavailable: printer idle, unreachable, file changed, or unsupported G-code. Status reads continue; use Retry to download the same file again."}
            delay(2_000)
        }
    }
    AlertDialog(onDismissRequest=close,title={Text("Follow active print")},text={
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Read-only · tracks the printer's file progress, which may lead queued physical motion.")
            Text(note,modifier=Modifier.testTag("live-preview-status"))
            TextButton({api.close();retry++}){Text("Retry preview")}
            progress?.takeIf{foreground&&state.connected}?.let{Text(it.identity.filename,modifier=Modifier.testTag("live-preview-file"))}
            path?.let{LayerPreview(it,progress?.takeIf{foreground&&state.connected}?.position,liveMode=true)}
            Text("File match: filename, byte size and modification time. Same-size replacements that preserve modification time cannot be detected.",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(close){Text("Close")}})
}
