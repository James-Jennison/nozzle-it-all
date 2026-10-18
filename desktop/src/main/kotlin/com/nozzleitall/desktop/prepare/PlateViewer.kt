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
class ViewCamera {
    var yaw by mutableStateOf(-35f); var pitch by mutableStateOf(55f)
    var distance by mutableStateOf(600f); var pan by mutableStateOf(Offset.Zero)
    private var fittedDistance = 600f
    fun fitBed(width: Float, depth: Float) { fittedDistance = (maxOf(width, depth) * 1.9f).coerceIn(200f, 1500f); distance = fittedDistance }
    /** Continuous orbit through both poles and underneath; bounded angles avoid precision loss after many turns. */
    fun orbit(dx: Float, dy: Float) { yaw = (yaw - dx * 0.4f) % 360f; pitch = (pitch - dy * 0.3f) % 360f }
    fun reset() { yaw = -35f; pitch = 55f; pan = Offset.Zero; distance = fittedDistance }
}

/** An object to draw. [paintColors] gives the colour of each painted filament number (index N for the file's filament N). */
class PlateObject(val id: Int, val name: String, val mesh: Mesh, val color: Color, val x: Float, val y: Float, val rotZ: Float, val scale: Float, val selected: Boolean,
                  val paintColors: List<Color?> = emptyList(), val paintKind: Int = 0,
                  /** Per-axis size on top of [scale]; a negative one mirrors that axis. */
                  val sx: Float = 1f, val sy: Float = 1f, val sz: Float = 1f,
                  /** Extra volumes (negative volumes, modifiers, support blockers / enforcers, parts), drawn as outlines. */
                  val parts: List<com.nozzleitall.project.ProjectManifest.ObjectPart> = emptyList(), val selectedPart: Int = -1,
                  /** How far the footprint reaches from (x, y): min x, max x, min y, max y. Empty = no direct handling. */
                  val reach: FloatArray = FloatArray(0), /** Height of the object in mm, so a press on its upper part counts as on it. */ val height: Float = 0f) {
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
class DisplayMesh(val mesh: Mesh, val normals: FloatArray, val bounds: FloatArray, val states: IntArray? = null) {
    val tris = IntArray(mesh.triangleCount) { it }
    internal val lightingGroups by lazy { LightingGroups(this) }
}

// Values must also be weak: DisplayMesh can contain the key Mesh itself.
// A strong value would keep a WeakHashMap key alive forever across imports/Undo.
private val displayCache = java.util.WeakHashMap<Mesh, MutableMap<Long,java.lang.ref.WeakReference<DisplayMesh>>>()

/**
 * The display version of [mesh], computed once per mesh and kind. Slicing always uses the full mesh. [kind] picks which paint shows:
 * 0 colour, 1 support, 2 seam, 3 fuzzy skin (the state numbers of the others index [PlateObject.paintColors]).
 */
fun displayMesh(mesh: Mesh, budget: Int = 150_000, kind: Int = 0): DisplayMesh = synchronized(displayCache) {
    val slots=displayCache.getOrPut(mesh) { object:LinkedHashMap<Long,java.lang.ref.WeakReference<DisplayMesh>>(8,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Long,java.lang.ref.WeakReference<DisplayMesh>>?)=size>8
    } }
    val key=(budget.toLong() shl 32) or (kind.toLong() and 0xffffffffL)
    slots[key]?.get() ?: run {
        val (painted, states) = expandPaint(mesh, when (kind) { 1 -> mesh.supportPaint; 2 -> mesh.seamPaint; 3 -> mesh.fuzzyPaint; else -> mesh.paint })
        val (m, kept) = simplifyWithStates(painted, states, budget)
        DisplayMesh(m, cornerNormals(m), mesh.bounds(), kept)
    }.also { slots[key] = java.lang.ref.WeakReference(it) }
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

internal class Projector(val w: Float, val h: Float, cam: ViewCamera, val cx: Float, val cy: Float) {
    private val yaw = Math.toRadians(cam.yaw.toDouble()); private val pitch = Math.toRadians(cam.pitch.toDouble())
    private val cyw = cos(yaw).toFloat(); private val syw = sin(yaw).toFloat(); private val cp = cos(pitch).toFloat(); private val sp = sin(pitch).toFloat()
    private val scale = min(w, h) / cam.distance * 1.6f; private val pan = cam.pan
    // A gentle perspective: the eye sits well back from the scene, so near parts are only slightly larger.
    internal val eye = cam.distance * 1.8f
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
    /** Where the line of sight through screen point ([sx], [sy]) meets the bed (z = 0), or null if it runs parallel to it. */
    fun bedPoint(sx: Float, sy: Float, z: Float = 0f): Offset? {
        val a = DoubleArray(3); val b = DoubleArray(3)
        unproject(sx, sy, depthRange, a); unproject(sx, sy, -depthRange, b)
        val dz = a[2] - b[2]; if (abs(dz) < 1e-9) return null
        val t = (a[2] - z) / dz
        return Offset((a[0] + t * (b[0] - a[0])).toFloat(), (a[1] + t * (b[1] - a[1])).toFloat())
    }

    /** A world-space direction in view space: x right, y down the screen, z toward the viewer. */
    fun dir(x: Float, y: Float, z: Float, out: FloatArray) {
        val rx = x * cyw - y * syw; val ry = x * syw + y * cyw
        out[0] = rx; out[1] = ry * cp - z * sp; out[2] = ry * sp + z * cp
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlateViewer(bedW: Float, bedD: Float, objects: List<PlateObject>, camera: ViewCamera, bedColor: Color, lineColor: Color, modifier: Modifier = Modifier,
                preview: GcodePreview? = null, previewLayer: Int = 0, toolColors: List<Color> = emptyList(), previewView: PreviewView = PreviewView.TOOL, paint: PaintInput? = null, onSelect: (Int?) -> Unit = {},
                accent: Color = Color(0xFF3E7BFA), onMove: (Int, Float, Float) -> Unit = { _, _, _ -> }, onTurn: (Int, Float) -> Unit = { _, _ -> }, onResize: (Int, Float) -> Unit = { _, _ -> },
                onSelection: ((Int?, Boolean, Boolean) -> Unit)? = null, onTransformStart: (Int) -> Unit = {}, onTransformEnd: () -> Unit = {}) {
    val prepared = remember(objects) { objects.map { o -> o to displayMesh(o.mesh, kind = o.paintKind) } }
    val depthFrames = remember { DepthSurfaceCache() }
    val paintRef = rememberUpdatedState(paint); val objectsRef = rememberUpdatedState(objects); val preparedRef = rememberUpdatedState(prepared)
    val selectRef = rememberUpdatedState<(Int?, Boolean, Boolean) -> Unit>({ id, ctrl, shift -> onSelection?.invoke(id, ctrl, shift) ?: onSelect(id) })
    val moveRef = rememberUpdatedState(onMove); val turnRef = rememberUpdatedState(onTurn); val resizeRef = rememberUpdatedState(onResize)
    val startRef = rememberUpdatedState(onTransformStart); val endRef = rememberUpdatedState(onTransformEnd)
    Canvas(modifier.semantics { contentDescription = if (preview != null) "Layer preview, layer ${previewLayer + 1} of ${preview.layers.size}" else "Build plate with ${objects.size} objects. Drag an object to move it, drag empty space to orbit, scroll to zoom." }
        .pointerInput(camera, preview != null) { awaitPointerEventScope {
            // Left-drag orbits; right-drag or Shift+drag pans.
            var last: Offset? = null
            val strokes = ArrayList<PaintRay>()
            // Direct handling: a press on an object moves it, on the turn knob turns it, on the corner knob resizes it.
            var grab: Grab? = null; var grabObj: PlateObject? = null; var grabFrom = Offset.Zero; var grabZ = 0f
            var pressedAt = Offset.Zero; var moved = false; var clickId: Int? = null; var primaryPress = false
            var modifierClick = false; var pressCtrl = false; var pressShift = false
            var grabStarted = false
            try { while (true) {
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
                val proj = Projector(size.width.toFloat(), size.height.toFloat(), camera, bedW / 2, bedD / 2)
                if (ch.pressed && prev != null && (ch.position - pressedAt).getDistance() > 4f) moved = true
                if (grab != null) {
                    val o = grabObj!!
                    if (!ch.pressed) {
                        if (grabStarted) endRef.value()
                        if (!moved && grab == Grab.MOVE) selectRef.value(clickId, false, false)
                        grab = null; grabObj = null; last = null; primaryPress = false; grabStarted = false; continue
                    }
                    val at = proj.bedPoint(ch.position.x, ch.position.y, grabZ)
                    if (at != null && moved && ev.type == PointerEventType.Move) {
                        if (!grabStarted) {
                            if (!o.selected) selectRef.value(o.id, false, false)
                            startRef.value(o.id); grabStarted = true
                        }
                        when (grab) {
                        Grab.MOVE -> moveRef.value(o.id, o.x + (at.x - grabFrom.x), o.y + (at.y - grabFrom.y))
                        Grab.ROTATE -> turnRef.value(o.id, PlateHandles.rotationAfterDrag(o.rotZ, o.x, o.y, grabFrom.x, grabFrom.y, at.x, at.y, if (ev.keyboardModifiers.isCtrlPressed) 15f else 0f))
                        Grab.SCALE -> resizeRef.value(o.id, PlateHandles.scaleAfterDrag(o.scale, o.x, o.y, grabFrom.x, grabFrom.y, at.x, at.y))
                        null -> {}
                        }
                    }
                    continue
                }
                if (ch.pressed && prev == null && paintRef.value == null && preview == null && ev.buttons.isPrimaryPressed) {
                    pressedAt = ch.position; moved = false; primaryPress = true
                    val start = pickGrab(proj, objectsRef.value, ch.position)
                    clickId = start?.obj?.id
                    pressCtrl = ev.keyboardModifiers.isCtrlPressed; pressShift = ev.keyboardModifiers.isShiftPressed
                    modifierClick = pressShift || (pressCtrl && start?.grab != Grab.ROTATE)
                    if (modifierClick) {
                        // Resolve click vs pan/cancel before changing selection. Shared knobs aren't object bodies.
                        clickId = start?.takeIf { it.grab == Grab.MOVE }?.obj?.id
                        last = ch.position; continue
                    }
                    if (start != null) {
                        grab = start.grab; grabObj = start.obj; grabFrom = start.from; grabZ = start.z; last = ch.position
                        continue
                    }
                }
                if (ev.type == PointerEventType.Move && ch.pressed && prev != null) {
                    val d = ch.position - prev
                    if (ev.buttons.isSecondaryPressed || ev.keyboardModifiers.isShiftPressed) camera.pan += d
                    else if (!modifierClick) camera.orbit(d.x, d.y)
                }
                if (!ch.pressed && primaryPress) {
                    if (!moved) selectRef.value(clickId, modifierClick && pressCtrl, modifierClick && pressShift)
                    primaryPress = false; modifierClick = false
                }
                last = if (ch.pressed) ch.position else null
            } } finally { if (grabStarted) endRef.value() }
        } }
        .onPointerEvent(PointerEventType.Scroll) { e -> e.changes.firstOrNull()?.let { camera.distance = (camera.distance * (1 + it.scrollDelta.y * 0.1f)).coerceIn(80f, 2500f) } }
        ) {
        val proj = Projector(size.width, size.height, camera, bedW / 2, bedD / 2)
        drawBed(proj, bedW, bedD, bedColor, lineColor)
        if (preview != null) drawPreview(proj, preview, previewLayer, toolColors, lineColor, previewView)
        else {
            prepared.forEach { (o, d) -> drawShadow(proj, o, d, depthFrames.shadows) }
            val surface = depthFrames.frame(size.width, size.height)
            prepared.forEach { (o, d) -> drawObject(proj, o, d, surface) }
            val info = org.jetbrains.skia.ImageInfo(surface.width, surface.height, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL)
            org.jetbrains.skia.Image.makeRaster(info, surface.pixels, surface.width*4).use { image ->
                drawIntoCanvas { it.nativeCanvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(size.width,size.height)) }
            }
            prepared.forEach { (o, d) -> if (o.parts.isNotEmpty()) drawParts(proj, o, d) }
            if (paint == null) selectionHandle(objects)?.let { drawHandles(proj, it, accent) }
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

private class Pick(val grab: Grab, val obj: PlateObject, val from: Offset, val z: Float)

/** What a press at [at] grabs: a knob of the selected object first, then whatever object the line of sight passes through (seen from above or from the side). */
private fun pickGrab(proj: Projector, objects: List<PlateObject>, at: Offset): Pick? {
    val tmp = FloatArray(3)
    fun near(x: Float, y: Float) = run { proj.p(x, y, 0f, tmp, 0); hypot(tmp[0] - at.x, tmp[1] - at.y) < 14f }
    selectionHandle(objects)?.let { o ->
        val bed = proj.bedPoint(at.x, at.y) ?: return@let
        val (rx, ry) = PlateHandles.rotateKnob(o.reach, o.x, o.y, o.rotZ); if (near(rx, ry)) return Pick(Grab.ROTATE, o, bed, 0f)
        val (sx, sy) = PlateHandles.scaleKnob(o.reach, o.x, o.y); if (near(sx, sy)) return Pick(Grab.SCALE, o, bed, 0f)
    }
    var best: Pick? = null; var bestDist = Float.MAX_VALUE
    for (o in objects) for (k in 0..4) {
        val z = o.height * k / 4f
        val p = proj.bedPoint(at.x, at.y, z) ?: continue
        if (PlateHandles.footprintHit(o.reach, o.x, o.y, p.x, p.y)) { val d = hypot(o.x - p.x, o.y - p.y); if (d < bestDist) { bestDist = d; best = Pick(Grab.MOVE, o, p, z) }; break }
    }
    return best
}

/** One set of handles around the selection, with a shared centre for rotation and scale. */
private fun selectionHandle(objects: List<PlateObject>): PlateObject? {
    val selected = objects.filter { it.selected && it.reach.size == 4 }
    val first = selected.firstOrNull() ?: return null
    if (selected.size == 1) return first
    val x = selected.map { it.x }.average().toFloat(); val y = selected.map { it.y }.average().toFloat()
    val reach = floatArrayOf(selected.minOf { it.x + it.reach[0] } - x, selected.maxOf { it.x + it.reach[1] } - x,
        selected.minOf { it.y + it.reach[2] } - y, selected.maxOf { it.y + it.reach[3] } - y)
    return PlateObject(first.id, "Selection", first.mesh, first.color, x, y, 0f, 1f, true, reach = reach)
}

/** The selected object's footprint, the ring with its turn knob, and the corner resize knob. */
private fun DrawScope.drawHandles(proj: Projector, o: PlateObject, accent: Color) {
    val t = FloatArray(3)
    fun pt(x: Float, y: Float): Offset { proj.p(x, y, 0.2f, t, 0); return Offset(t[0], t[1]) }
    val box = listOf(pt(o.x + o.reach[0], o.y + o.reach[2]), pt(o.x + o.reach[1], o.y + o.reach[2]), pt(o.x + o.reach[1], o.y + o.reach[3]), pt(o.x + o.reach[0], o.y + o.reach[3]))
    for (i in 0..3) drawLine(accent.copy(alpha = 0.55f), box[i], box[(i + 1) % 4], 1.2f)
    val r = PlateHandles.ringRadius(o.reach)
    var prev = pt(o.x + r, o.y)
    for (i in 1..64) { val a = i * 2 * PI / 64; val p = pt(o.x + r * cos(a).toFloat(), o.y + r * sin(a).toFloat()); drawLine(accent.copy(alpha = 0.7f), prev, p, 1.6f); prev = p }
    val (rx, ry) = PlateHandles.rotateKnob(o.reach, o.x, o.y, o.rotZ); pt(rx, ry).let { drawCircle(Color.White, 8f, it); drawCircle(accent, 6f, it) }
    val (sx, sy) = PlateHandles.scaleKnob(o.reach, o.x, o.y); pt(sx, sy).let { drawRect(Color.White, it - Offset(8f, 8f), androidx.compose.ui.geometry.Size(16f, 16f)); drawRect(accent, it - Offset(6f, 6f), androidx.compose.ui.geometry.Size(12f, 12f)) }
}

private fun hitTest(tap: Offset, objects: List<PlateObject>, cam: ViewCamera, w: Float, h: Float, bedW: Float, bedD: Float): Int? {
    val proj = Projector(w, h, cam, bedW / 2, bedD / 2); val tmp = FloatArray(3)
    return objects.minByOrNull { o -> proj.p(o.x, o.y, 0f, tmp, 0); hypot(tmp[0] - tap.x, tmp[1] - tap.y) }?.takeIf { o ->
        proj.p(o.x, o.y, 0f, tmp, 0); hypot(tmp[0] - tap.x, tmp[1] - tap.y) < 90f }?.id
}

internal fun DrawScope.drawBed(proj: Projector, w: Float, d: Float, bed: Color, line: Color) {
    val a = FloatArray(12)
    proj.p(0f, 0f, 0f, a, 0); proj.p(w, 0f, 0f, a, 3); proj.p(w, d, 0f, a, 6); proj.p(0f, d, 0f, a, 9)
    val path = Path().apply { moveTo(a[0], a[1]); lineTo(a[3], a[4]); lineTo(a[6], a[7]); lineTo(a[9], a[10]); close() }
    drawPath(path, bed); drawPath(path, line, style = Stroke(2f))
    val t = FloatArray(6)
    var g = 0f
    while (g <= w) { proj.p(g, 0f, 0f, t, 0); proj.p(g, d, 0f, t, 3); drawLine(line.copy(alpha = 0.5f), Offset(t[0], t[1]), Offset(t[3], t[4]), 1f); g += 25f }
    g = 0f
    while (g <= d) { proj.p(0f, g, 0f, t, 0); proj.p(w, g, 0f, t, 3); drawLine(line.copy(alpha = 0.5f), Offset(t[0], t[1]), Offset(t[3], t[4]), 1f); g += 25f }
}

/** The display colour of a material: very dark ones are lifted just enough for their shape to show. */
private fun displayBase(c: Color): Color {
    val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    return if (lum < 0.18f) lerpColor(c, Color(0.34f, 0.36f, 0.40f), 0.55f * (1f - lum / 0.18f)) else c
}

/** A soft shadow straight down onto the bed, drawn as one translucent layer so overlaps don't darken. */
internal fun DrawScope.drawShadow(proj: Projector, o: PlateObject, d: DisplayMesh, scratch: ShadowScratch) = rasterShadow(proj,o,d,scratch)

internal fun DrawScope.drawObject(proj: Projector, o: PlateObject, d: DisplayMesh, surface: DepthSurface) = rasterObject(proj,o,d,surface)

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

private fun DrawScope.drawPreview(proj: Projector, preview: GcodePreview, layer: Int, toolColors: List<Color>, line: Color, view: PreviewView) {
    val a = FloatArray(6)
    val upTo = layer.coerceIn(0, preview.layers.size - 1)
    val (lo, hi) = preview.speedRange
    // Earlier layers dimmer, the current layer fully, so the shape and the current path both read.
    val start = max(0, upTo - 80)
    for (li in start..upTo) {
        val l = preview.layers[li]; val current = li == upTo
        val stride = if (current) 1 else max(1, l.count / 2500)
        var i = 0
        while (i < l.count) {
            val s = l.segments
            proj.p(s[i * 4], s[i * 4 + 1], l.z, a, 0); proj.p(s[i * 4 + 2], s[i * 4 + 3], l.z, a, 3)
            val col = when (view) {
                PreviewView.TOOL -> toolColors.getOrElse(l.tools[i].toInt()) { line }
                PreviewView.LINE_TYPE -> Color(GcodePreview.FEATURES.getOrNull(l.kinds[i].toInt())?.second ?: 0xFF8C8C8C)
                PreviewView.SPEED -> Color(GcodePreview.speedColour(if (hi > lo) (l.speeds[i] - lo) / (hi - lo) else 0.5f))
            }
            drawLine(if (current) col else col.copy(alpha = 0.5f), Offset(a[0], a[1]), Offset(a[3], a[4]), if (current) 1.8f else 1.2f)
            i += stride
        }
    }
}

fun lerpColor(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)
