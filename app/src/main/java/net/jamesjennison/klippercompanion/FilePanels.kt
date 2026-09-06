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
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(workspace::import)}
    var exportSource by remember(workspace) {mutableStateOf<java.io.File?>(null)}
    var exportUri by remember(workspace) {mutableStateOf<android.net.Uri?>(null)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){exportUri=it}
    exportUri?.let {uri->AlertDialog(onDismissRequest={exportUri=null},title={Text("Write the selected document?")},text={Text("Save ${workspace.name} to the document you selected. Its provider may replace existing content. An interrupted export may leave a partial document.")},confirmButton={TextButton({exportUri=null;workspace.export(uri,exportSource)}){Text("Write copy")}},dismissButton={TextButton({exportUri=null}){Text("Cancel")}})}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
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
@Composable fun LayerPreview(path:Toolpath) {
    var layer by remember(path) {mutableIntStateOf(0)}
    val selected=remember(path,layer) {path.segments.filter {it.layer==layer}.let {all->val step=(all.size/5000+1);all.filterIndexed {i,_->i%step==0}}}
    val color=MaterialTheme.colorScheme.primary
    Text("Extrusion layer ${layer+1} / ${path.heights.size} · Z ${path.heights[layer]} mm")
    if(path.heights.size>1)Slider(layer.toFloat(),{layer=it.toInt().coerceIn(path.heights.indices)},valueRange=0f..path.heights.lastIndex.toFloat())
    if(path.sampled)Text("Large file: path segments are sampled.",style=MaterialTheme.typography.bodySmall)
    if(path.ignoredMotion)Text("Some commands are not represented.",style=MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(260.dp)) {
        if(selected.isNotEmpty()) {
            val minX=selected.minOf {minOf(it.x1,it.x2)};val maxX=selected.maxOf {maxOf(it.x1,it.x2)}
            val minY=selected.minOf {minOf(it.y1,it.y2)};val maxY=selected.maxOf {maxOf(it.y1,it.y2)}
            val scale=minOf((size.width-24)/(maxX-minX).coerceAtLeast(1f),(size.height-24)/(maxY-minY).coerceAtLeast(1f))
            fun point(x:Float,y:Float)=Offset(12+(x-minX)*scale,size.height-12-(y-minY)*scale)
            selected.forEach {drawLine(color,point(it.x1,it.y1),point(it.x2,it.y2),1.5f)}
        }
    }
    Text("Preview only — not a motion simulation or live tool position.",style=MaterialTheme.typography.bodySmall)
}
