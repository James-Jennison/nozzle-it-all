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
import kotlin.math.abs
import kotlin.math.*

/** Camera looking at the bed centre: yaw/pitch in degrees, distance in mm, and a pan offset in screen pixels. */
class ViewCamera { var yaw by mutableStateOf(-35f); var pitch by mutableStateOf(55f); var distance by mutableStateOf(600f); var pan by mutableStateOf(Offset.Zero) }

/** An object to draw. [paintColors] gives the colour of each painted filament number (index N for the file's filament N). */
class PlateObject(val id: Int, val name: String, val mesh: Mesh, val color: Color, val x: Float, val y: Float, val rotZ: Float, val scale: Float, val selected: Boolean,
                  val paintColors: List<Color?> = emptyList(), val paintKind: Int = 0,
                  /** Per-axis size on top of [scale]; a negative one mirrors that axis. */
                  val sx: Float = 1f, val sy: Float = 1f, val sz: Float = 1f,
                  /** Extra volumes (negative volumes, modifiers, support blockers / enforcers, parts), drawn as outlines. */
                  val parts: List<com.nozzleitall.project.ProjectManifest.ObjectPart> = emptyList(), val selectedPart: Int = -1) {
    val ex get() = scale * sx; val ey get() = scale * sy; val ez get() = scale * sz
}

/** A ray in an object's own mesh coordinates (where the engine paints), with the brush radius there. */
class PaintRay(val origin: DoubleArray, val dir: DoubleArray, val radius: Double)

/** Painting on the plate: strokes on object [objectId] with a brush of [radiusMm] are handed to [onStrokes] in batches. */
class PaintInput(val objectId: Int, val radiusMm: Float, val onStrokes: (List<PaintRay>) -> Unit)

/**
 * A mesh ready to draw: possibly simplified, with smooth normals, and the original's bounds (so placement never shifts).
 * [states] is each triangle's painted filament (0 = the object's own), when the model is painted.
 */
class DisplayMesh(val mesh: Mesh, val normals: FloatArray, val bounds: FloatArray, val states: IntArray? = null) { val tris = IntArray(mesh.triangleCount) { it } }

private val displayCache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Mesh, Array<DisplayMesh?>>())

/**
 * The display version of [mesh], computed once per mesh and kind. Slicing always uses the full mesh. [kind] picks which paint shows:
 * 0 colour, 1 support, 2 seam, 3 fuzzy skin (the state numbers of the others index [PlateObject.paintColors]).
 */
fun displayMesh(mesh: Mesh, budget: Int = 150_000, kind: Int = 0): DisplayMesh = displayCache.getOrPut(mesh) { arrayOfNulls(4) }.let { slots ->
    slots[kind] ?: run {
        val (painted, states) = expandPaint(mesh, when (kind) { 1 -> mesh.supportPaint; 2 -> mesh.seamPaint; 3 -> mesh.fuzzyPaint; else -> mesh.paint })
        val (m, kept) = simplifyWithStates(painted, states, budget)
        DisplayMesh(m, vertexNormals(m, IntArray(m.triangleCount) { it }), mesh.bounds(), kept)
    }.also { slots[kind] = it }
}

/**
 * The mesh with each painted triangle replaced by its painted pieces (as the engine subdivides it), and each triangle's
 * filament. Unpainted meshes come back unchanged with no states.
 */
fun expandPaint(mesh: Mesh, kindPaint: Array<String?>? = mesh.paint): Pair<Mesh, IntArray?> {
    val paint = kindPaint ?: return mesh to null
    val v = mesh.vertices; val t = mesh.triangles
    val verts = PaintVerts(v.size + 1024); verts.addAll(v)
    val tris = PaintInts(t.size + 1024); val states = PaintInts(t.size / 3 + 1024)
    fun corner(i: Int) = floatArrayOf(v[i * 3], v[i * 3 + 1], v[i * 3 + 2])
    for (k in 0 until mesh.triangleCount) {
        val s = paint[k]
        val node = s?.let { runCatching { com.nozzleitall.project.Paint.decode(it) }.getOrNull() }
        if (node == null || node is com.nozzleitall.project.Paint.Node.Leaf) {
            tris.add(t[k * 3]); tris.add(t[k * 3 + 1]); tris.add(t[k * 3 + 2]); states.add((node as? com.nozzleitall.project.Paint.Node.Leaf)?.state ?: 0)
            continue
        }
        com.nozzleitall.project.Paint.leaves(node, corner(t[k * 3]), corner(t[k * 3 + 1]), corner(t[k * 3 + 2])) { a, b, c, state ->
            for (p in arrayOf(a, b, c)) { tris.add(verts.size / 3); verts.add(p[0]); verts.add(p[1]); verts.add(p[2]) }
            states.add(state)
        }
    }
    return Mesh(verts.toArray(), tris.toArray()) to states.toArray()
}

private class PaintVerts(cap: Int) { var a = FloatArray(cap); var size = 0
    fun add(x: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = x }
    fun addAll(x: FloatArray) { if (size + x.size > a.size) a = a.copyOf(maxOf(a.size * 2, size + x.size)); x.copyInto(a, size); size += x.size }
    fun toArray() = a.copyOf(size) }
private class PaintInts(cap: Int) { var a = IntArray(cap); var size = 0
    fun add(x: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = x }
    fun toArray() = a.copyOf(size) }

/** [simplifyForDisplay], keeping each surviving triangle's state. */
fun simplifyWithStates(mesh: Mesh, states: IntArray?, budget: Int): Pair<Mesh, IntArray?> {
    if (states == null) return simplifyForDisplay(mesh, budget) to null
    val kept = IntArray(mesh.triangleCount); var n = 0
    val m = simplifyForDisplay(mesh, budget) { k -> kept[n++] = states[k] }
    return if (m === mesh) mesh to states else m to kept.copyOf(m.triangleCount)
}

/**
 * Vertex clustering: points that fall in the same small grid cell merge into one, and triangles that collapse are
 * dropped. The shell stays closed (unlike skipping triangles, which leaves holes), just with less detail. The cell size
 * shrinks until the result fits [budget] triangles.
 */
fun simplifyForDisplay(mesh: Mesh, budget: Int, keep: ((Int) -> Unit)? = null): Mesh {
    if (mesh.triangleCount <= budget) return mesh
    val v = mesh.vertices; val t = mesh.triangles
    val b = mesh.bounds()
    val extent = maxOf(b[3] - b[0], b[4] - b[1], b[5] - b[2]).coerceAtLeast(1e-3f)
    var resolution = 512
    var best: Mesh = mesh
    repeat(8) {
        val cell = extent / resolution
        val index = HashMap<Long, Int>(mesh.vertexCount / 2)
        val sums = ArrayList<Float>(); val counts = ArrayList<Int>()
        val remap = IntArray(mesh.vertexCount)
        for (i in 0 until mesh.vertexCount) {
            val ix = ((v[i * 3] - b[0]) / cell).toLong(); val iy = ((v[i * 3 + 1] - b[1]) / cell).toLong(); val iz = ((v[i * 3 + 2] - b[2]) / cell).toLong()
            val key = (ix shl 42) or (iy shl 21) or iz
            val id = index.getOrPut(key) { sums.add(0f); sums.add(0f); sums.add(0f); counts.add(0); counts.size - 1 }
            sums[id * 3] += v[i * 3]; sums[id * 3 + 1] += v[i * 3 + 1]; sums[id * 3 + 2] += v[i * 3 + 2]; counts[id] = counts[id] + 1
            remap[i] = id
        }
        val tris = ArrayList<Int>(t.size); val survivors = ArrayList<Int>()
        for (k in 0 until mesh.triangleCount) {
            val a = remap[t[k * 3]]; val bb = remap[t[k * 3 + 1]]; val c = remap[t[k * 3 + 2]]
            if (a != bb && bb != c && a != c) { tris += a; tris += bb; tris += c; survivors += k }
        }
        val verts = FloatArray(counts.size * 3) { sums[it] / counts[it / 3] }
        best = Mesh(verts, tris.toIntArray())
        if (best.triangleCount <= budget || it == 7) { keep?.let { f -> survivors.forEach(f) }; return best }
        resolution = (resolution * 0.75).toInt().coerceAtLeast(16)
    }
    return best
}

private class Projector(val w: Float, val h: Float, cam: ViewCamera, val cx: Float, val cy: Float) {
    private val yaw = Math.toRadians(cam.yaw.toDouble()); private val pitch = Math.toRadians(cam.pitch.toDouble())
    private val cyw = cos(yaw).toFloat(); private val syw = sin(yaw).toFloat(); private val cp = cos(pitch).toFloat(); private val sp = sin(pitch).toFloat()
    private val scale = min(w, h) / cam.distance * 1.6f; private val pan = cam.pan
    // A gentle perspective: the eye sits well back from the scene, so near parts are only slightly larger.
    private val eye = cam.distance * 1.8f
    /** Returns screen x, y and a depth (larger = farther). */
    fun p(x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
        val dx = x - cx; val dy = y - cy
        val rx = dx * cyw - dy * syw; val ry = dx * syw + dy * cyw
        val sy = ry * cp - z * sp; val toward = ry * sp + z * cp
        val f = eye / (eye - toward.coerceAtMost(eye * 0.8f))
        out[o] = w / 2 + rx * scale * f + pan.x; out[o + 1] = h / 2 + sy * scale * f + pan.y; out[o + 2] = -toward
    }
    /** The world point on the line of sight through screen point ([sx], [sy]) at view depth [toward] (larger = nearer the viewer). */
    fun unproject(sx: Float, sy: Float, toward: Float, out: DoubleArray) {
        val f = eye / (eye - toward.coerceAtMost(eye * 0.8f))
        val rx = (sx - w / 2 - pan.x) / (scale * f); val syy = (sy - h / 2 - pan.y) / (scale * f)
        val ry = cp * syy + sp * toward; val z = -sp * syy + cp * toward
        out[0] = (cx + rx * cyw + ry * syw).toDouble(); out[1] = (cy - rx * syw + ry * cyw).toDouble(); out[2] = z.toDouble()
    }
    val depthRange get() = eye * 0.5f

    /** A world-space direction in view space: x right, y down the screen, z toward the viewer. */
    fun dir(x: Float, y: Float, z: Float, out: FloatArray) {
        val rx = x * cyw - y * syw; val ry = x * syw + y * cyw
        out[0] = rx; out[1] = ry * cp - z * sp; out[2] = ry * sp + z * cp
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlateViewer(bedW: Float, bedD: Float, objects: List<PlateObject>, camera: ViewCamera, bedColor: Color, lineColor: Color, modifier: Modifier = Modifier,
                preview: GcodePreview? = null, previewLayer: Int = 0, toolColors: List<Color> = emptyList(), paint: PaintInput? = null, onSelect: (Int?) -> Unit = {}) {
    val prepared = remember(objects) { objects.map { o -> o to displayMesh(o.mesh, kind = o.paintKind) } }
    val paintRef = rememberUpdatedState(paint); val objectsRef = rememberUpdatedState(objects); val preparedRef = rememberUpdatedState(prepared)
    Canvas(modifier.semantics { contentDescription = if (preview != null) "Layer preview, layer ${previewLayer + 1} of ${preview.layers.size}" else "Build plate with ${objects.size} objects. Drag to orbit, scroll to zoom." }
        .pointerInput(camera) { awaitPointerEventScope {
            // Left-drag orbits; right-drag or Shift+drag pans.
            var last: Offset? = null
            val strokes = ArrayList<PaintRay>()
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull() ?: continue
                val prev = last
                val painting = paintRef.value?.takeIf { !ev.buttons.isSecondaryPressed && !ev.keyboardModifiers.isShiftPressed && !ev.keyboardModifiers.isAltPressed }
                if (painting != null && (ch.pressed || strokes.isNotEmpty())) {
                    // Painting: the left button lays a stroke on the selected object (Alt-drag orbits, Shift-drag pans).
                    val target = preparedRef.value.firstOrNull { (o, _) -> o.id == painting.objectId }
                    if (target != null && ch.pressed && (prev == null || (ch.position - prev).getDistance() >= 3f)) {
                        screenRay(ch.position, size.width.toFloat(), size.height.toFloat(), camera, bedW, bedD, target.first, target.second, painting.radiusMm)?.let { strokes += it }
                        last = ch.position
                    }
                    if (strokes.size >= 16 || (!ch.pressed && strokes.isNotEmpty())) { painting.onStrokes(strokes.toList()); strokes.clear() }
                    if (!ch.pressed) last = null
                    continue
                }
                if (ev.type == PointerEventType.Move && ch.pressed && prev != null) {
                    val d = ch.position - prev
                    if (ev.buttons.isSecondaryPressed || ev.keyboardModifiers.isShiftPressed) camera.pan += d
                    else { camera.yaw -= d.x * 0.4f; camera.pitch = (camera.pitch - d.y * 0.3f).coerceIn(5f, 89f) }
                }
                last = if (ch.pressed) ch.position else null
            }
        } }
        .onPointerEvent(PointerEventType.Scroll) { e -> e.changes.firstOrNull()?.let { camera.distance = (camera.distance * (1 + it.scrollDelta.y * 0.1f)).coerceIn(80f, 2500f) } }
        .pointerInput(objects) { detectTapGestures { tap -> if (paintRef.value == null) onSelect(hitTest(tap, objects, camera, size.width.toFloat(), size.height.toFloat(), bedW, bedD)) } }) {
        val proj = Projector(size.width, size.height, camera, bedW / 2, bedD / 2)
        drawBed(proj, bedW, bedD, bedColor, lineColor)
        if (preview != null) drawPreview(proj, preview, previewLayer, toolColors, lineColor)
        else {
            prepared.forEach { (o, d) -> drawShadow(proj, o, d) }
            prepared.forEach { (o, d) -> drawObject(proj, o, d) }
            prepared.forEach { (o, d) -> if (o.parts.isNotEmpty()) drawParts(proj, o, d) }
        }
    }
}

/** The ray through [tap] in [o]'s mesh coordinates (inverse of how [drawObject] places the mesh), or null if the view is degenerate. */
private fun screenRay(tap: Offset, w: Float, h: Float, cam: ViewCamera, bedW: Float, bedD: Float, o: PlateObject, d: DisplayMesh, radiusMm: Float): PaintRay? {
    val proj = Projector(w, h, cam, bedW / 2, bedD / 2)
    val near = DoubleArray(3); val far = DoubleArray(3)
    proj.unproject(tap.x, tap.y, proj.depthRange, near); proj.unproject(tap.x, tap.y, -proj.depthRange, far)
    val b = d.bounds; val mx = (b[0] + b[3]) / 2; val my = (b[1] + b[4]) / 2; val minZ = b[2]
    val cr = cos(Math.toRadians(o.rotZ.toDouble())); val sr = sin(Math.toRadians(o.rotZ.toDouble()))
    val kx = o.ex.toDouble(); val ky = o.ey.toDouble(); val kz = o.ez.toDouble(); val zBase = if (kz >= 0) b[2].toDouble() else b[5].toDouble()
    fun point(p: DoubleArray): DoubleArray { val x = p[0] - o.x; val y = p[1] - o.y; return doubleArrayOf(mx + (x * cr + y * sr) / kx, my + (-x * sr + y * cr) / ky, zBase + p[2] / kz) }
    val a = point(near); val c = point(far)
    val dir = doubleArrayOf(c[0] - a[0], c[1] - a[1], c[2] - a[2]); val len = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
    if (len < 1e-9) return null
    for (i in 0..2) dir[i] /= len
    return PaintRay(a, dir, (radiusMm / abs(o.scale)).toDouble())
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

/** Area-weighted vertex normals, so curved surfaces shade smoothly (sharp edges are kept per corner when drawing). */
fun vertexNormals(mesh: Mesh, tris: IntArray): FloatArray {
    val v = mesh.vertices; val t = mesh.triangles
    val out = FloatArray(v.size)
    for (tri in tris) {
        val a = t[tri * 3] * 3; val b = t[tri * 3 + 1] * 3; val c = t[tri * 3 + 2] * 3
        val ux = v[b] - v[a]; val uy = v[b + 1] - v[a + 1]; val uz = v[b + 2] - v[a + 2]
        val wx = v[c] - v[a]; val wy = v[c + 1] - v[a + 1]; val wz = v[c + 2] - v[a + 2]
        val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
        for (i in intArrayOf(a, b, c)) { out[i] += nx; out[i + 1] += ny; out[i + 2] += nz }
    }
    for (i in out.indices step 3) { val l = sqrt(out[i] * out[i] + out[i + 1] * out[i + 1] + out[i + 2] * out[i + 2]); if (l > 0) { out[i] /= l; out[i + 1] /= l; out[i + 2] /= l } }
    return out
}

/** The display colour of a material: very dark ones are lifted just enough for their shape to show. */
private fun displayBase(c: Color): Color {
    val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    return if (lum < 0.18f) lerpColor(c, Color(0.34f, 0.36f, 0.40f), 0.55f * (1f - lum / 0.18f)) else c
}

/** A soft shadow straight down onto the bed, drawn as one translucent layer so overlaps don't darken. */
private fun DrawScope.drawShadow(proj: Projector, o: PlateObject, d: DisplayMesh) {
    val v = d.mesh.vertices; val t = d.mesh.triangles; val tris = d.tris
    val b = d.bounds; val mx = (b[0] + b[3]) / 2; val my = (b[1] + b[4]) / 2
    val cr = cos(Math.toRadians(o.rotZ.toDouble())).toFloat(); val sr = sin(Math.toRadians(o.rotZ.toDouble())).toFloat()
    val step = max(1, tris.size / 20_000); val tmp = FloatArray(3)
    val positions = ArrayList<Offset>(tris.size / step * 3 + 3)
    var k = 0
    while (k < tris.size) {
        val tri = tris[k]
        for (c in 0..2) {
            val vi = t[tri * 3 + c] * 3
            val lx = (v[vi] - mx) * o.ex; val ly = (v[vi + 1] - my) * o.ey
            proj.p(o.x + lx * cr - ly * sr, o.y + lx * sr + ly * cr, 0f, tmp, 0); positions += Offset(tmp[0], tmp[1])
        }
        k += step
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = 0.28f })
        canvas.drawVertices(Vertices(VertexMode.Triangles, positions, positions, List(positions.size) { Color.Black }, emptyList()), BlendMode.Dst, Paint())
        canvas.restore()
    }
}

private fun DrawScope.drawObject(proj: Projector, o: PlateObject, d: DisplayMesh) {
    val v = d.mesh.vertices; val t = d.mesh.triangles; val tris = d.tris; val normals = d.normals
    val b = d.bounds; val mx = (b[0] + b[3]) / 2; val my = (b[1] + b[4]) / 2; val zBase = if (o.ez >= 0f) b[2] else b[5]
    val cr = cos(Math.toRadians(o.rotZ.toDouble())).toFloat(); val sr = sin(Math.toRadians(o.rotZ.toDouble())).toFloat()
    val n = tris.size
    val screen = FloatArray(n * 9); val depth = FloatArray(n); val cornerColor = IntArray(n * 3)
    // Lights live in view space so a model reads the same from any angle: a key light from the upper left, a softer fill
    // from the right, a highlight, and a rim on silhouettes that separates the model from the background.
    val key = norm(-0.45f, -0.60f, 0.66f); val fill = norm(0.70f, 0.05f, 0.45f)
    val half = norm(key[0], key[1], key[2] + 1f)
    fun shade(c: Color) = displayBase(if (o.selected) lerpColor(c, Color.White, 0.18f) else c)
    val own = shade(o.color)
    val palette = o.paintColors.map { it?.let(::shade) ?: own }
    val states = d.states
    val fn = FloatArray(3); val vn = FloatArray(3); val w = FloatArray(9)
    for ((k, tri) in tris.withIndex()) {
        val base = states?.get(tri)?.let { s -> if (s == 0) own else palette.getOrNull(s) ?: own } ?: own
        for (c in 0..2) {
            val vi = t[tri * 3 + c] * 3
            val lx = (v[vi] - mx) * o.ex; val ly = (v[vi + 1] - my) * o.ey; val lz = (v[vi + 2] - zBase) * o.ez
            w[c * 3] = o.x + lx * cr - ly * sr; w[c * 3 + 1] = o.y + lx * sr + ly * cr; w[c * 3 + 2] = lz
            proj.p(w[c * 3], w[c * 3 + 1], w[c * 3 + 2], screen, k * 9 + c * 3)
        }
        val ux = w[3] - w[0]; val uy = w[4] - w[1]; val uz = w[5] - w[2]; val qx = w[6] - w[0]; val qy = w[7] - w[1]; val qz = w[8] - w[2]
        var nx = uy * qz - uz * qy; var ny = uz * qx - ux * qz; var nz = ux * qy - uy * qx
        val len = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0 } ?: 1f; nx /= len; ny /= len; nz /= len
        proj.dir(nx, ny, nz, fn)
        // Two-sided: meshes with inconsistent winding still light from the side we see.
        val flip = if (fn[2] < 0f) -1f else 1f
        for (c in 0..2) {
            val vi = t[tri * 3 + c] * 3
            // A normal follows the inverse of the size, so a stretched or mirrored object keeps lighting right.
            var ax = normals[vi] / o.ex; var ay = normals[vi + 1] / o.ey; var az = normals[vi + 2] / o.ez
            val nl = sqrt(ax * ax + ay * ay + az * az).takeIf { it > 0 } ?: 1f; ax /= nl; ay /= nl; az /= nl
            val rx = ax * cr - ay * sr; val ry = ax * sr + ay * cr
            proj.dir(rx, ry, az, vn)
            // Smooth where the surface is curved; keep the face normal at sharp edges (over about 40 degrees).
            val dot = (vn[0] * fn[0] + vn[1] * fn[1] + vn[2] * fn[2])
            val sx: Float; val sy: Float; val sz: Float
            if (dot * dot > 0.58f && dot > 0) { sx = vn[0] * flip; sy = vn[1] * flip; sz = vn[2] * flip } else { sx = fn[0] * flip; sy = fn[1] * flip; sz = fn[2] * flip }
            val diffuse = max(0f, sx * key[0] + sy * key[1] + sz * key[2])
            val soft = max(0f, sx * fill[0] + sy * fill[1] + sz * fill[2])
            val spec = max(0f, sx * half[0] + sy * half[1] + sz * half[2]).let { s -> s * s * s * s * s * s * s * s * s * s * s * s * s * s * s * s }
            val rim = (1f - max(0f, sz)).let { r -> r * r * r }
            val light = 0.30f + 0.62f * diffuse + 0.20f * soft
            val red = (base.red * light + 0.22f * spec + 0.10f * rim).coerceIn(0f, 1f)
            val green = (base.green * light + 0.22f * spec + 0.10f * rim).coerceIn(0f, 1f)
            val blue = (base.blue * light + 0.22f * spec + 0.12f * rim).coerceIn(0f, 1f)
            cornerColor[k * 3 + c] = Color(red, green, blue).toArgbInt()
        }
        depth[k] = (screen[k * 9 + 2] + screen[k * 9 + 5] + screen[k * 9 + 8]) / 3
    }
    val order = (0 until n).sortedByDescending { depth[it] }
    val positions = ArrayList<Offset>(n * 3); val colors = ArrayList<Color>(n * 3)
    for (k in order) for (i in 0..2) { positions += Offset(screen[k * 9 + i * 3], screen[k * 9 + i * 3 + 1]); colors += Color(cornerColor[k * 3 + i]) }
    // Dst keeps the vertex colours as they are; SrcOver would blend them with the paint's default black.
    drawIntoCanvas { it.drawVertices(Vertices(VertexMode.Triangles, positions, positions, colors, emptyList()), BlendMode.Dst, Paint()) }
}

/**
 * An object's extra volumes as outlines of their boxes (a cylinder or sphere shows its bounding box): red for a negative volume,
 * green and orange for support enforcers and blockers, blue for modifiers, the part colour for added parts. Part coordinates are
 * relative to the centre of the mesh's bounding box and scale, mirror and turn with the object.
 */
private fun DrawScope.drawParts(proj: Projector, o: PlateObject, d: DisplayMesh) {
    val b = d.bounds
    val half = abs((b[5] - b[2]) * o.ez) / 2f
    val cr = cos(Math.toRadians(o.rotZ.toDouble())).toFloat(); val sr = sin(Math.toRadians(o.rotZ.toDouble())).toFloat()
    val tmp = FloatArray(3)
    fun world(px: Float, py: Float, pz: Float): Offset {
        val lx = px * o.ex; val ly = py * o.ey
        proj.p(o.x + lx * cr - ly * sr, o.y + lx * sr + ly * cr, pz * o.ez + half, tmp, 0)
        return Offset(tmp[0], tmp[1])
    }
    o.parts.forEachIndexed { i, part ->
        val colour = when (part.kind) { "negative" -> Color(0xFFE5484D); "enforcer" -> Color(0xFF3BA55D); "blocker" -> Color(0xFFF5A623); "modifier" -> Color(0xFF3E7BFA); else -> Color(0xFF9B59B6) }
        val cx = part.center[0].toFloat(); val cy = part.center[1].toFloat(); val cz = part.center[2].toFloat()
        val hx = part.size[0].toFloat() / 2; val hy = part.size[1].toFloat() / 2; val hz = part.size[2].toFloat() / 2
        val pts = Array(8) { k -> world(cx + (if (k and 1 == 0) -hx else hx), cy + (if (k and 2 == 0) -hy else hy), cz + (if (k and 4 == 0) -hz else hz)) }
        val width = if (i == o.selectedPart) 2.6f else 1.4f
        for ((a, e) in listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7, 0 to 2, 1 to 3, 4 to 6, 5 to 7, 0 to 4, 1 to 5, 2 to 6, 3 to 7))
            drawLine(colour, pts[a], pts[e], width)
    }
}

private fun norm(x: Float, y: Float, z: Float): FloatArray { val l = sqrt(x * x + y * y + z * z); return floatArrayOf(x / l, y / l, z / l) }
private fun Color.toArgbInt(): Int = toArgb()

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
