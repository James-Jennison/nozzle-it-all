package com.nozzleitall.desktop.prepare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nozzleitall.project.Mesh
import kotlin.math.*

/** Camera looking at the bed centre: yaw/pitch in degrees, distance in mm, and a pan offset in screen pixels. */
class ViewCamera { var yaw by mutableStateOf(-35f); var pitch by mutableStateOf(55f); var distance by mutableStateOf(600f); var pan by mutableStateOf(Offset.Zero) }

class PlateObject(val id: Int, val name: String, val mesh: Mesh, val color: Color, val x: Float, val y: Float, val rotZ: Float, val scale: Float, val selected: Boolean)

/** Reduces very large meshes for display only (slicing always uses the full mesh). */
fun displayTriangles(mesh: Mesh, budget: Int = 120_000): IntArray {
    if (mesh.triangleCount <= budget) return IntArray(mesh.triangleCount) { it }
    val step = mesh.triangleCount.toDouble() / budget
    return IntArray(budget) { (it * step).toInt() }
}

private class Projector(val w: Float, val h: Float, cam: ViewCamera, val cx: Float, val cy: Float) {
    private val yaw = Math.toRadians(cam.yaw.toDouble()); private val pitch = Math.toRadians(cam.pitch.toDouble())
    private val cyw = cos(yaw).toFloat(); private val syw = sin(yaw).toFloat(); private val cp = cos(pitch).toFloat(); private val sp = sin(pitch).toFloat()
    private val scale = min(w, h) / cam.distance * 1.6f; private val pan = cam.pan
    /** Returns screen x, y and a depth (larger = farther). */
    fun p(x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
        val dx = x - cx; val dy = y - cy
        val rx = dx * cyw - dy * syw; val ry = dx * syw + dy * cyw
        val sy = ry * cp - z * sp; val depth = ry * sp + z * cp
        out[o] = w / 2 + rx * scale + pan.x; out[o + 1] = h / 2 + sy * scale + pan.y; out[o + 2] = -depth
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlateViewer(bedW: Float, bedD: Float, objects: List<PlateObject>, camera: ViewCamera, bedColor: Color, lineColor: Color, modifier: Modifier = Modifier,
                preview: GcodePreview? = null, previewLayer: Int = 0, toolColors: List<Color> = emptyList(), onSelect: (Int?) -> Unit = {}) {
    val prepared = remember(objects) { objects.map { it to displayTriangles(it.mesh) } }
    Canvas(modifier.semantics { contentDescription = if (preview != null) "Layer preview, layer ${previewLayer + 1} of ${preview.layers.size}" else "Build plate with ${objects.size} objects. Drag to orbit, scroll to zoom." }
        .pointerInput(camera) { awaitPointerEventScope {
            // Left-drag orbits; right-drag or Shift+drag pans.
            var last: Offset? = null
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull() ?: continue
                val prev = last
                if (ev.type == PointerEventType.Move && ch.pressed && prev != null) {
                    val d = ch.position - prev
                    if (ev.buttons.isSecondaryPressed || ev.keyboardModifiers.isShiftPressed) camera.pan += d
                    else { camera.yaw -= d.x * 0.4f; camera.pitch = (camera.pitch + d.y * 0.3f).coerceIn(5f, 89f) }
                }
                last = if (ch.pressed) ch.position else null
            }
        } }
        .onPointerEvent(PointerEventType.Scroll) { e -> e.changes.firstOrNull()?.let { camera.distance = (camera.distance * (1 + it.scrollDelta.y * 0.1f)).coerceIn(80f, 2500f) } }
        .pointerInput(objects) { detectTapGestures { tap -> onSelect(hitTest(tap, objects, camera, size.width.toFloat(), size.height.toFloat(), bedW, bedD)) } }) {
        val proj = Projector(size.width, size.height, camera, bedW / 2, bedD / 2)
        drawBed(proj, bedW, bedD, bedColor, lineColor)
        if (preview != null) drawPreview(proj, preview, previewLayer, toolColors, lineColor)
        else prepared.forEach { (o, tris) -> drawObject(proj, o, tris) }
    }
}

private fun hitTest(tap: Offset, objects: List<PlateObject>, cam: ViewCamera, w: Float, h: Float, bedW: Float, bedD: Float): Int? {
    val proj = Projector(w, h, cam, bedW / 2, bedD / 2); val tmp = FloatArray(3)
    return objects.minByOrNull { o -> proj.p(o.x, o.y, 0f, tmp, 0); hypot(tmp[0] - tap.x, tmp[1] - tap.y) }?.takeIf { o ->
        proj.p(o.x, o.y, 0f, tmp, 0); hypot(tmp[0] - tap.x, tmp[1] - tap.y) < 90f }?.id
}

private fun DrawScope.drawBed(proj: Projector, w: Float, d: Float, bed: Color, line: Color) {
    val a = FloatArray(12)
    proj.p(0f, 0f, 0f, a, 0); proj.p(w, 0f, 0f, a, 3); proj.p(w, d, 0f, a, 6); proj.p(0f, d, 0f, a, 9)
    val path = Path().apply { moveTo(a[0], a[1]); lineTo(a[3], a[4]); lineTo(a[6], a[7]); lineTo(a[9], a[10]); close() }
    drawPath(path, bed); drawPath(path, line, style = Stroke(1.5f))
    val t = FloatArray(6)
    var g = 0f
    while (g <= w) { proj.p(g, 0f, 0f, t, 0); proj.p(g, d, 0f, t, 3); drawLine(line.copy(alpha = 0.35f), Offset(t[0], t[1]), Offset(t[3], t[4]), 1f); g += 25f }
    g = 0f
    while (g <= d) { proj.p(0f, g, 0f, t, 0); proj.p(w, g, 0f, t, 3); drawLine(line.copy(alpha = 0.35f), Offset(t[0], t[1]), Offset(t[3], t[4]), 1f); g += 25f }
}

private fun DrawScope.drawObject(proj: Projector, o: PlateObject, tris: IntArray) {
    val v = o.mesh.vertices; val t = o.mesh.triangles
    val b = o.mesh.bounds(); val mx = (b[0] + b[3]) / 2; val my = (b[1] + b[4]) / 2; val minZ = b[2]
    val cr = cos(Math.toRadians(o.rotZ.toDouble())).toFloat(); val sr = sin(Math.toRadians(o.rotZ.toDouble())).toFloat()
    val n = tris.size
    val screen = FloatArray(n * 9); val shade = FloatArray(n); val depth = FloatArray(n)
    val light = floatArrayOf(-0.35f, -0.45f, 0.82f)
    for ((k, tri) in tris.withIndex()) {
        val w = FloatArray(9)
        for (c in 0..2) {
            val vi = t[tri * 3 + c] * 3
            val lx = (v[vi] - mx) * o.scale; val ly = (v[vi + 1] - my) * o.scale; val lz = (v[vi + 2] - minZ) * o.scale
            w[c * 3] = o.x + lx * cr - ly * sr; w[c * 3 + 1] = o.y + lx * sr + ly * cr; w[c * 3 + 2] = lz
            proj.p(w[c * 3], w[c * 3 + 1], w[c * 3 + 2], screen, k * 9 + c * 3)
        }
        val ux = w[3] - w[0]; val uy = w[4] - w[1]; val uz = w[5] - w[2]; val vx = w[6] - w[0]; val vy = w[7] - w[1]; val vz = w[8] - w[2]
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0 } ?: 1f; nx /= len; ny /= len; nz /= len
        shade[k] = 0.35f + 0.65f * max(0f, nx * light[0] + ny * light[1] + nz * light[2])
        depth[k] = (screen[k * 9 + 2] + screen[k * 9 + 5] + screen[k * 9 + 8]) / 3
    }
    val order = (0 until n).sortedByDescending { depth[it] }
    val positions = ArrayList<Offset>(n * 3); val colors = ArrayList<Color>(n * 3)
    val base = if (o.selected) lerpColor(o.color, Color.White, 0.18f) else o.color
    for (k in order) {
        val c = Color(base.red * shade[k], base.green * shade[k], base.blue * shade[k])
        for (i in 0..2) { positions += Offset(screen[k * 9 + i * 3], screen[k * 9 + i * 3 + 1]); colors += c }
    }
    drawIntoCanvas { it.drawVertices(Vertices(VertexMode.Triangles, positions, positions, colors, emptyList()), BlendMode.SrcOver, Paint()) }
}

private fun DrawScope.drawPreview(proj: Projector, preview: GcodePreview, layer: Int, toolColors: List<Color>, line: Color) {
    val a = FloatArray(6)
    val upTo = layer.coerceIn(0, preview.layers.size - 1)
    // Earlier layers faintly, the current layer fully, so the shape and the current path both read.
    val start = max(0, upTo - 60)
    for (li in start..upTo) {
        val l = preview.layers[li]; val current = li == upTo
        val stride = if (current) 1 else max(1, l.count / 3000)
        var i = 0
        while (i < l.count) {
            val s = l.segments
            proj.p(s[i * 4], s[i * 4 + 1], l.z, a, 0); proj.p(s[i * 4 + 2], s[i * 4 + 3], l.z, a, 3)
            val col = toolColors.getOrElse(l.tools[i].toInt()) { line }
            drawLine(if (current) col else col.copy(alpha = 0.25f), Offset(a[0], a[1]), Offset(a[3], a[4]), if (current) 1.6f else 1f)
            i += stride
        }
    }
}

fun lerpColor(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)
