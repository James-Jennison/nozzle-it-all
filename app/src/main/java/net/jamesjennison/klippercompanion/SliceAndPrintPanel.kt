package net.jamesjennison.klippercompanion

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
 * WO-13 Phases 2/3: shares a model file (STL/3MF/OBJ) in, slices it on-device for the currently
 * selected printer, then stages the resulting G-code into the same review-then-confirm machinery
 * every other mutating command already uses - this panel itself only ever gets as far as an
 * enabled Confirm button; nothing prints without that separate, explicit tap.
 *
 * Deliberately targets the currently selected printer (state.address), the same convention
 * BambuPrintPanel already uses for its own share-intent flow, rather than adding a separate
 * printer-picker UI.
 */
@Composable fun SliceAndPrintPanel(uri: Uri, state: ScreenState, execute: (PrinterCommand, Int)->Unit, close: ()->Unit) {
    val context = LocalContext.current
    val name = remember(uri) { sliceableModelName(runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if(it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()) }
    val profile = remember(state.address, state.profiles) { state.profiles.find { it.address == state.address } }
    var stage by remember(uri) { mutableStateOf("Slicing…") }
    var working by remember(uri) { mutableStateOf(true) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var sliced by remember(uri) { mutableStateOf<File?>(null) }
    var stagedFilename by remember(uri) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if(name.isEmpty()) {
        AlertDialog(onDismissRequest=close,title={Text("Unsupported file")},
            text={Text("On-device slicing accepts STL, 3MF or OBJ model files.")},
            confirmButton={TextButton(close){Text("Close")}})
        return
    }
    if(profile == null) {
        AlertDialog(onDismissRequest=close,title={Text("No printer selected")},
            text={Text("Select a saved printer before sharing a model to slice.")},
            confirmButton={TextButton(close){Text("Close")}})
        return
    }
    if(profile.kind == PrinterKind.BAMBU_LAB) {
        // Real, honest gap (see SlicingCoordinator.kt / docs/WORK_ORDER.md's WO-13 entry): the
        // engine's export_gcode() produces plain .gcode, not the .gcode.3mf bundle
        // BambuPrintRequest/bambuPrintName require. Slicing itself would succeed; the handoff to
        // this printer's own print-start path is what's not built yet - say so plainly rather
        // than attempt something that would fail bambuPrintName's own validation.
        AlertDialog(onDismissRequest=close,title={Text("Not yet supported for Bambu Lab")},
            text={Text("On-device slicing for Bambu Lab printers isn't wired up yet - it needs a .gcode.3mf bundle, and this app's slicing engine currently only produces plain .gcode. Slice in Bambu Studio or Orca and share the exported .gcode.3mf instead.")},
            confirmButton={TextButton(close){Text("Close")}})
        return
    }

    LaunchedEffect(uri, profile.address) {
        working = true; stage = "Slicing…"; error = null
        val localModel = try {
            withContext(Dispatchers.IO) { copySharedModel(context.applicationContext, uri, name) }
        } catch(e: Exception) { working = false; error = e.message ?: "Could not read the shared file."; return@LaunchedEffect }
        when(val outcome = SlicingCoordinator.slice(context.applicationContext, localModel, profile)) {
            is SliceOutcome.Success -> { sliced = outcome.gcode; stage = "Uploading…" }
            is SliceOutcome.FirmwareBlocked -> { working = false; error = outcome.reason }
            is SliceOutcome.Failed -> { working = false; error = outcome.message }
        }
    }
    // A second effect rather than folding into the one above: the upload step needs a live,
    // connected printer (LiveFileChanges.ready() requires it), so it only starts once slicing
    // itself has already succeeded, and re-runs independently if the user backgrounds/returns.
    LaunchedEffect(sliced, state.connected) {
        val gcode = sliced ?: return@LaunchedEffect
        if(!state.connected) { working = false; error = "Connect to ${state.address} to upload the sliced file."; return@LaunchedEffect }
        working = true
        val backend = LiveFileChanges(state.address, File(context.cacheDir, "live-file"), rawApiKey = state.apiKeyFor(state.address))
        try {
            val requested = gcode.name
            val draft = withContext(Dispatchers.IO) { backend.prepare(LiveFileChanges.Operation.UPLOAD, "", requested, gcode) }
            withContext(Dispatchers.IO) { backend.confirm(draft.id) }
            stagedFilename = draft.destination
            working = false
        } catch(e: Exception) { working = false; error = e.message ?: "Could not upload the sliced file." }
        finally { backend.close() }
    }

    AlertDialog(onDismissRequest={ if(!working) close() }, title={Text("Slice and print on this printer?")},
        text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            KilnFrame(accent=true) { Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(name, style=MaterialTheme.typography.titleSmall)
                Text("Slices on-device for ${profile.label}, uploads the result to ${state.address}, then waits for you to confirm the print separately.")
            } }
            if(working) Text(stage)
            error?.let { Text(it, color=MaterialTheme.colorScheme.error) }
            stagedFilename?.let { Text("Ready: $it", style=MaterialTheme.typography.bodySmall) }
        } },
        confirmButton={ TextButton({
            val filename = stagedFilename ?: return@TextButton
            execute(Moonraker.start(filename), state.generation)
            close()
        }, enabled=!working && error==null && stagedFilename!=null, modifier=Modifier.testTag("slice-and-print-confirm")) { Text("Start print") } },
        dismissButton={TextButton({ if(!working) close() }, enabled=!working){Text("Cancel")}})
}

private fun copySharedModel(context: android.content.Context, uri: Uri, name: String): File {
    val dir = File(context.cacheDir, "slice-inputs")
    check(dir.isDirectory || dir.mkdirs()) { "Cannot create local storage for the shared model." }
    dir.listFiles()?.forEach { it.delete() }
    val target = File(dir, name)
    val input = context.contentResolver.openInputStream(uri) ?: throw ApiFailure("Cannot open the shared file.")
    input.use { FileTransfer.copyBounded(it, target) }
    return target
}
