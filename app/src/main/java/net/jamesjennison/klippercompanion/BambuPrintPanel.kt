package net.jamesjennison.klippercompanion

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The shared document's own name if a Bambu printer can print it, otherwise "".
 *
 * Only an already-sliced .gcode.3mf is accepted: this app does not slice, and a plain .gcode or a
 * design .3mf would be uploaded and then rejected by the printer. The name also becomes the remote
 * filename, so it has to be a safe single path segment.
 */
internal fun bambuPrintName(raw: String?): String {
    val name = raw?.trim().orEmpty()
    if(name.length !in 1..200 || '/' in name || '\\' in name || name.any { it.code < 32 }) return ""
    return if(name.endsWith(".gcode.3mf", true)) name else ""
}

/**
 * Confirms printing a shared .gcode.3mf on a Bambu Lab printer.
 *
 * The file is copied into app storage only after the user confirms (BambuPrintRequest needs a real
 * file: FTPS wants a length and the printer checks an MD5 over exactly those bytes), and the print
 * itself goes through PrinterModel.execute like every other mutating action, so it gets the same
 * re-verification of printer state immediately before sending. Single-material, printer defaults:
 * bed type, levelling, flow calibration and timelapse are BambuPrintRequest's own defaults with no
 * UI, and there is no AMS mapping.
 */
@Composable fun BambuPrintPanel(uri: Uri, state: ScreenState, execute: (PrinterCommand, Int)->Unit, close: ()->Unit) {
    val context = LocalContext.current
    val name = remember(uri) { bambuPrintName(runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if(it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()) }
    var note by remember(uri) { mutableStateOf("") }
    var sending by remember(uri) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if(name.isEmpty()) {
        AlertDialog(onDismissRequest=close,title={Text("Unsupported file")},
            text={Text("A Bambu Lab printer prints already-sliced .gcode.3mf files. Slice this model in Bambu Studio or Orca first, then share the exported file.")},
            confirmButton={TextButton(close){Text("Close")}})
        return
    }
    AlertDialog(onDismissRequest={ if(!sending) close() },title={Text("Print on this printer?")},
        text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(name,style=MaterialTheme.typography.titleSmall)
            Text("This uploads the file to ${state.address} and starts printing it. Confirm only when the plate is clear and the printer is ready.")
            Text("Single material from the external spool, with the printer's default bed type, levelling and flow calibration.",style=MaterialTheme.typography.bodySmall)
            if(note.isNotBlank()) Text(note,color=MaterialTheme.colorScheme.error)
        } },
        confirmButton={ TextButton({
            sending=true;note="Copying the file…"
            scope.launch {
                val target = try { withContext(Dispatchers.IO) { copyForPrint(context.applicationContext, uri, name) } }
                    catch(e: Exception) { sending=false;note=e.message ?: "Could not read the shared file.";return@launch }
                execute(PrinterCommand("Print $name", "", bambuPrintRequest=BambuPrintRequest(target, name),
                    allowedStates=setOf("standby","complete","cancelled","error")), state.generation)
                close()
            }
        },enabled=!sending && state.connected && !state.busy,modifier=Modifier.testTag("bambu-print-confirm")){Text("Upload and print")} },
        dismissButton={TextButton({ if(!sending) close() },enabled=!sending){Text("Cancel")}})
}

/** Copies the shared document into app storage under its own name, replacing any earlier copy. */
private fun copyForPrint(context: android.content.Context, uri: Uri, name: String): File {
    val dir = File(context.cacheDir, "bambu-prints")
    check(dir.isDirectory || dir.mkdirs()) { "Cannot create local storage for the print file." }
    dir.listFiles()?.forEach { it.delete() }
    val target = File(dir, name)
    val input = context.contentResolver.openInputStream(uri) ?: throw ApiFailure("Cannot open the shared file.")
    input.use { FileTransfer.copyBounded(it, target) }
    return target
}
