package net.jamesjennison.klippercompanion

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.File

internal object WorkspaceCache {
    private val live=java.util.concurrent.ConcurrentHashMap.newKeySet<File>()
    private val cleanup=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Synchronized fun reserve(context:Context):File {
        val dir=File(context.cacheDir,"gcode-workspaces");check(dir.isDirectory||dir.mkdirs()) {"Cannot create local workspace."}
        dir.listFiles()?.filter {it.isFile && it !in live}?.forEach {check(it.delete()||!it.exists()) {"Cannot reclaim an old workspace."}}
        check(live.size<2) {"Wait for the previous workspace operation to stop."}
        return File(dir,"${java.util.UUID.randomUUID()}.gcode").also {live.add(it)}
    }
    fun release(file:File?) {if(file!=null){live.remove(file);cleanup.launch {file.delete()}}}
}

class FileWorkspace(private val context: Context,private val scope: CoroutineScope): AutoCloseable {
    var name by mutableStateOf("");private set
    var note by mutableStateOf("");private set
    var loading by mutableStateOf(false);private set
    var preview by mutableStateOf<Toolpath?>(null);private set
    var localFile: File?=null;private set
    private var job: Job?=null;private var transfer:FileTransfer?=null;private var serial=0
    fun cancel() {serial++;job?.cancel();transfer?.close();transfer=null;loading=false;note="Operation cancelled. An export destination may be incomplete."}
    private fun load(label:String,reader:suspend (File)->Unit) {
        cancel();val ticket=serial;loading=true;note="Reading file…";preview=null
        WorkspaceCache.release(localFile);localFile=null;name=label
        job=scope.launch {
            var target:File?=null
            try {withContext(Dispatchers.IO) {target=WorkspaceCache.reserve(context)};val destination=requireNotNull(target)
                withContext(Dispatchers.IO) { reader(destination) };ensureActive()
                if(ticket==serial) {localFile=target;note="Ready to preview or save a copy."}
            } catch(e:CancellationException) {throw e} catch(e:Exception) {if(ticket==serial)note=e.message?:"File operation failed."}
            finally {if(localFile!=target)WorkspaceCache.release(target);if(ticket==serial)loading=false}
        }
    }
    fun download(address:String,path:String,apiKey:String="") {
        runCatching {FileTransfer.validate(path)}.onFailure {note=it.message?:"Invalid path";return}
        load(path) { target ->
            val current=currentCoroutineContext();val api=FileTransfer(address,apiKey);transfer=api
            try {api.download(path,target){!current.isActive}} finally {api.close();if(transfer===api)transfer=null}
        }
    }
    fun import(uri:Uri) {
        if(uri.scheme!="content") {note="Choose a file through Android’s document picker.";return}
        load("Imported G-code") {target ->
            val current=currentCoroutineContext()
            val label=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0) else null}?:"imported.gcode"
            require(label.length in 1..200 && !label.contains('/') && !label.contains('\\') && label.none {it.code<32} && (label.endsWith(".gcode",true)||label.endsWith(".gco",true))) {"Choose a .gcode or .gco file with a valid name."}
            current.ensureActive()
            context.contentResolver.openInputStream(uri)?.use {FileTransfer.copyBounded(it,target){!current.isActive}} ?: throw ApiFailure("Cannot open the selected document.")
            withContext(Dispatchers.Main) {name=label.take(200)}
        }
    }
    fun render() {
        val source=localFile ?: return;if(loading)return
        val ticket=serial;loading=true;note="Building read-only layer preview…"
        job=scope.launch {try {
            val current=currentCoroutineContext();val result=withContext(Dispatchers.Default) {source.inputStream().buffered().use {GcodePreview.parse(it){!current.isActive}}};ensureActive()
            if(ticket==serial){preview=result;note="Approximate XY extrusion paths. Custom macros and machine transforms are not simulated."}
        }catch(e:CancellationException){throw e}catch(e:Exception){if(ticket==serial)note=e.message?:"Preview unavailable."}finally{if(ticket==serial)loading=false}}
    }
    fun export(uri:Uri,expected:File?=localFile) {
        val source=localFile
        if(source==null||source!=expected){note="Workspace changed. No copy written; the chosen destination may be empty.";return}
        if(loading)return
        val ticket=serial;loading=true
        job=scope.launch {try {withContext(Dispatchers.IO) {
            val current=currentCoroutineContext()
            context.contentResolver.openOutputStream(uri,"wt")?.use {out->source.inputStream().use {input->val bytes=ByteArray(32768);while(true){current.ensureActive();val n=input.read(bytes);if(n<0)break;out.write(bytes,0,n)}}}?:throw ApiFailure("Cannot write the chosen document.")
        };if(ticket==serial)note="Copy saved to the selected document."}catch(e:CancellationException){throw e}catch(_:Exception){if(ticket==serial)note="Export incomplete. Remove the partial destination before retrying."}finally{if(ticket==serial)loading=false}}
    }
    override fun close() {cancel();WorkspaceCache.release(localFile);localFile=null}
}
