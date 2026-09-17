package net.jamesjennison.klippercompanion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** A bounded draw list; omitted segments are disclosed separately from parser sampling. */
internal fun previewDrawList(segments:List<ToolpathSegment>,layer:Int):List<ToolpathSegment> {
    val selected=segments.filter {it.layer==layer}
    val stride=ceil(selected.size/5000.0).toInt().coerceAtLeast(1)
    return selected.filterIndexed {i,_->i%stride==0}
}

@Composable fun LayerPreview(path:Toolpath,reportedPosition:Long?=null,liveMode:Boolean=false) {
    var layer by remember(path) {mutableIntStateOf(0)}
    var zoom by remember(path) {mutableFloatStateOf(1f)}
    var pan by remember(path) {mutableStateOf(Offset.Zero)}
    var showTravel by remember(path) {mutableStateOf(false)}
    var position by remember(path,layer) {mutableIntStateOf(0)}
    val marker=remember(path,reportedPosition){reportedPosition?.let(path::segmentAt)}
    LaunchedEffect(marker,liveMode){if(liveMode&&marker!=null)layer=marker.layer}
    val selected=remember(path,layer) {previewDrawList(path.segments,layer)}
    val travel=remember(path,layer) {previewDrawList(path.travels,layer)}
    val layerCount=remember(path,layer){path.segments.count{it.layer==layer}}
    val travelCount=remember(path,layer){path.travels.count{it.layer==layer}}
    // Fixed extrusion bounds across every layer; travel moves cannot shrink the model.
    val bounds=remember(path) {floatArrayOf(path.segments.minOf{minOf(it.x1,it.x2)},path.segments.maxOf{maxOf(it.x1,it.x2)},path.segments.minOf{minOf(it.y1,it.y2)},path.segments.maxOf{maxOf(it.y1,it.y2)})}
    val extrusionColor=MaterialTheme.colorScheme.primary
    val travelColor=MaterialTheme.colorScheme.onSurfaceVariant
    val highlight=MaterialTheme.colorScheme.tertiary
    Text("Extrusion layer ${layer+1} / ${path.heights.size} · Z ${path.heights[layer]} mm")
    if(!liveMode&&path.heights.size>1)Slider(layer.toFloat(),{layer=it.toInt().coerceIn(path.heights.indices)},valueRange=0f..path.heights.lastIndex.toFloat(),modifier=Modifier.testTag("preview-layer"))
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton({layer--},enabled=!liveMode&&layer>0){Text("Previous layer")}
        OutlinedButton({layer++},enabled=!liveMode&&layer<path.heights.lastIndex){Text("Next layer")}
        FilterChip(showTravel,{showTravel=!showTravel},label={Text("Travel paths")},modifier=Modifier.testTag("preview-travel"))
    }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton({zoom=(zoom/1.5f).coerceAtLeast(1f)},enabled=zoom>1f){Text("Zoom out")}
        OutlinedButton({zoom=(zoom*1.5f).coerceAtMost(12f)},enabled=zoom<12f){Text("Zoom in")}
        TextButton({zoom=1f;pan=Offset.Zero}){Text("Fit model")}
    }
    Text("Zoom ${"%.1f".format(java.util.Locale.ROOT,zoom)}× · Pinch to zoom, drag to pan")
    Canvas(Modifier.fillMaxWidth().height(280.dp).testTag("preview-canvas")
        .semantics {contentDescription="Layer ${layer+1}: ${selected.size} displayed extrusion paths, ${if(showTravel)travel.size else 0} travel paths"}
        .pointerInput(path){detectTransformGestures {_,delta,scale,_->
            zoom=(zoom*scale).coerceIn(1f,12f)
            pan=Offset((pan.x+delta.x).coerceIn(-size.width.toFloat(),size.width.toFloat()),(pan.y+delta.y).coerceIn(-size.height.toFloat(),size.height.toFloat()))
        }}) {
        val scale=minOf((size.width-24)/(bounds[1]-bounds[0]).coerceAtLeast(1f),(size.height-24)/(bounds[3]-bounds[2]).coerceAtLeast(1f))*zoom
        fun point(x:Float,y:Float)=Offset(size.width/2+(x-(bounds[0]+bounds[1])/2)*scale+pan.x,size.height/2-(y-(bounds[2]+bounds[3])/2)*scale+pan.y)
        // Clip panned/zoomed paths to the viewport.
        clipRect {
            if(showTravel)travel.forEach {drawLine(travelColor,point(it.x1,it.y1),point(it.x2,it.y2),1.5f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(7f,5f)))}
            selected.forEach {drawLine(extrusionColor,point(it.x1,it.y1),point(it.x2,it.y2),2f)}
            (if(liveMode)marker?.takeIf{it.layer==layer} else selected.getOrNull(position))?.let {drawLine(highlight,point(it.x1,it.y1),point(it.x2,it.y2),5f);drawCircle(highlight,5f,point(it.x2,it.y2))}
        }
    }
    if(liveMode) {
        Text(if(marker==null)"No consumed extrusion command at the reported position." else "Following buffered file progress · command ending at byte ${marker.byteEnd}",modifier=Modifier.testTag("preview-live-marker"))
    } else if(selected.isNotEmpty()) {
        Text("Selected displayed path ${position+1} / ${selected.size}")
        if(selected.size>1)Slider(position.toFloat(),{position=it.toInt().coerceIn(selected.indices)},valueRange=0f..selected.lastIndex.toFloat(),modifier=Modifier.testTag("preview-position"))
    }else Text("No retained extrusion paths on this layer.")
    if(path.sampled||layerCount>selected.size||travelCount>travel.size)Text("Large file: displayed paths are sampled; small details may be omitted.",style=MaterialTheme.typography.bodySmall)
    if(path.ignoredMotion)Text("Some commands or travel arcs are not represented.",style=MaterialTheme.typography.bodySmall)
    Text("Solid: extrusion · Dashed: travel · Highlight: selected path. Travels belong to the preceding extrusion layer (initial travels to layer 1).",style=MaterialTheme.typography.bodySmall)
    Text(if(liveMode)"Approximate buffered file progress, not the physical nozzle position. Custom macros, machine transforms and queued motion are not simulated." else "Approximate preview only — selected path is not live print position. Custom macros and machine transforms are not simulated.",style=MaterialTheme.typography.bodySmall)
}
