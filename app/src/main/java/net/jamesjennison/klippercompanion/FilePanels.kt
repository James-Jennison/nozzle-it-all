package net.jamesjennison.klippercompanion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

@Composable fun FileWorkspacePanel(workspace:FileWorkspace) {
    var fileChanges by remember {mutableStateOf(false)}
    if(fileChanges) FileChangePanel {fileChanges=false}
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(workspace::import)}
    var exportSource by remember(workspace) {mutableStateOf<java.io.File?>(null)}
    var exportUri by remember(workspace) {mutableStateOf<android.net.Uri?>(null)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){exportUri=it}
    exportUri?.let {uri->AlertDialog(onDismissRequest={exportUri=null},title={Text("Write the selected document?")},text={Text("Save ${workspace.name} to the document you selected. Its provider may replace existing content. An interrupted export may leave a partial document.")},confirmButton={TextButton({exportUri=null;workspace.export(uri,exportSource)}){Text("Write copy")}},dismissButton={TextButton({exportUri=null}){Text("Cancel")}})}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton({fileChanges=true}){Text("Preview file management")}
            OutlinedButton({pick.launch(arrayOf("application/octet-stream","text/*","application/x-gcode"))},enabled=!workspace.loading){Text("Import G-code")}
            if(workspace.localFile!=null) {
                OutlinedButton(workspace::render,enabled=!workspace.loading){Text("Preview layers")}
                OutlinedButton({exportSource=workspace.localFile;save.launch(workspace.name.substringAfterLast('/'))},enabled=!workspace.loading){Text("Save a copy")}
            }
            if(workspace.loading)TextButton(workspace::cancel){Text("Cancel transfer / preview")}
        }
        Text("Temporary workspace; old cache is reclaimed on the next import/download. Printer uploads, renames and deletes await idle-printer acceptance.",style=MaterialTheme.typography.bodySmall)
        if(workspace.name.isNotBlank())Text(workspace.name)
        if(workspace.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(workspace.note.isNotBlank())Text(workspace.note,style=MaterialTheme.typography.bodySmall)
        workspace.preview?.let { LayerPreview(it) }
    }
}
