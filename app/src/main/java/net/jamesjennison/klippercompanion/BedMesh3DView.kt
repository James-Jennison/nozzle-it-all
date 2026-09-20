package net.jamesjennison.klippercompanion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.max

/**
 * A gesture-orbited 3D surface for a probed bed mesh: drag to orbit, pinch to zoom. The probed
 * grid is smoothed with BedMeshGeometry's Catmull-Rom subdivision so a coarse (e.g. 5x5) probe
 * grid still reads as a continuous surface rather than a blocky one. Colors match the existing 2D
 * heatmap's blue-to-red scale, so both views agree on what "high" and "low" mean.
 *
 * Clean-room: Helix's own bed-mesh view is a JS/Skia canvas, nothing here is derived from it.
 */
@Composable fun BedMesh3DView(mesh: BedMeshStatus, modifier: Modifier = Modifier) {
    var yaw by remember(mesh) { mutableFloatStateOf(-0.7f) }
    var pitch by remember(mesh) { mutableFloatStateOf(0.55f) }
    var zoom by remember(mesh) { mutableFloatStateOf(1f) }
    val rows = mesh.probedMatrix.size
    val cols = mesh.probedMatrix.firstOrNull()?.size ?: 0
    if (rows < 2 || cols < 2) {
        Text("At least a 2×2 probed grid is needed for a 3D surface; showing the heatmap only.", style = MaterialTheme.typography.bodySmall)
        return
    }
    // One subdivision step per probe interval already reads as smooth on a phone-sized canvas;
    // a denser mesh would cost more per-frame triangle sorting for a difference too small to see.
    val subdivisions = 3
    val smoothed = remember(mesh) { BedMeshGeometry.subdivideGrid(mesh.probedMatrix, subdivisions) }
    val values = mesh.flatValues
    val low = values.min(); val high = values.max(); val span = (high - low).takeIf { it > 0.0 } ?: 1.0
    Canvas(modifier.fillMaxWidth().height(260.dp).testTag("mesh-3d-canvas")
        .semantics { contentDescription = "3D bed mesh surface, drag to orbit, pinch to zoom" }
        .pointerInput(mesh) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
                yaw += pan.x * 0.01f
                pitch = (pitch - pan.y * 0.01f).coerceIn(0.05f, (PI / 2 - 0.05).toFloat())
                zoom = (zoom * gestureZoom).coerceIn(0.5f, 3f)
            }
        }) {
        val sRows = smoothed.size; val sCols = smoothed.first().size
        // The bed's own footprint sets X/Y scale; Z is normalized to its own probed range and
        // then exaggerated relative to that footprint, since real Z variance (fractions of a
        // millimeter) is invisible next to a bed that's hundreds of millimeters wide otherwise.
        val footprint = 1.0
        val zExaggeration = 0.35
        fun bedPoint(r: Int, c: Int): BedMeshGeometry.Vec3 {
            val x = (c.toDouble() / (sCols - 1) - 0.5) * footprint
            val y = (r.toDouble() / (sRows - 1) - 0.5) * footprint
            val z = ((smoothed[r][c] - low) / span - 0.5) * zExaggeration
            return BedMeshGeometry.Vec3(x, y, z)
        }
        val screenScale = (max(size.width, size.height) * 0.85 * zoom).toFloat()
        val center = Offset(size.width / 2f, size.height / 2f)
        fun toScreen(v: BedMeshGeometry.Vec3): Pair<Offset, Double> {
            val rotated = BedMeshGeometry.orbit(v, yaw.toDouble(), pitch.toDouble())
            val p = BedMeshGeometry.project(rotated)
            return Offset(center.x + (p.screenX * screenScale).toFloat(), center.y + (p.screenY * screenScale).toFloat()) to p.depth
        }
        // One quad per smoothed cell, colored by its four corners' average height, drawn
        // back-to-front by average screen depth (painter's algorithm - correct for a heightfield,
        // which never self-occludes in a way that ordering alone can't resolve).
        data class Quad(val points: List<Offset>, val depth: Double, val color: Color)
        val quads = ArrayList<Quad>((sRows - 1) * (sCols - 1))
        for (r in 0 until sRows - 1) for (c in 0 until sCols - 1) {
            val corners = listOf(r to c, r to c + 1, r + 1 to c + 1, r + 1 to c)
            val projected = corners.map { (pr, pc) -> toScreen(bedPoint(pr, pc)) }
            val avgHeight = corners.map { (pr, pc) -> smoothed[pr][pc] }.average()
            val t = ((avgHeight - low) / span).coerceIn(0.0, 1.0).toFloat()
            quads += Quad(projected.map { it.first }, projected.sumOf { it.second } / 4, lerp(Color(0xFF3B6FE0), Color(0xFFE0473B), t))
        }
        quads.sortByDescending { it.depth }
        quads.forEach { quad ->
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(quad.points[0].x, quad.points[0].y)
                quad.points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, quad.color)
        }
    }
    Text("Drag to orbit, pinch to zoom. Height is exaggerated for visibility, not to physical scale.", style = MaterialTheme.typography.bodySmall)
}
