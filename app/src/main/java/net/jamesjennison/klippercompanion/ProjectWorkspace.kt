package net.jamesjennison.klippercompanion

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jamesjennison.klippercompanion.project.ProjectObject
import net.jamesjennison.klippercompanion.project.transform
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phase 1 (Consumer Slicer Plan §16): the real multi-object build-plate view - a genuinely
 * separate renderer from ModelViewer.kt's MeshGLRenderer (single-object, share-intent-triggered,
 * still real and unchanged), matching this codebase's own existing precedent of SlicedPreview.kt
 * having its own ToolpathGLRenderer rather than retrofitting MeshGLRenderer - both share
 * GLSupport.kt's shader/buffer helpers, not each other's renderer class. Renders every object in
 * a project's ProjectObject list at once, each with its own real transform (the same
 * move/rotate/scale/pivot math ModelViewer.kt already uses and the native engine already applies
 * at slice time - see RenderableObject's own comment), with one object at a time selectable for
 * active transform editing (tap to select, matching a real multi-object editor's own convention -
 * dragging/rotating/scaling always acts on "whichever object is currently selected", the same
 * mental model auto-arrange/collision detection will build on in a later increment).
 */

// One object on the plate, ready to render: its already-loaded geometry (MeshLoader.load, the
// same real per-vertex data ModelViewer.kt's single-object path uses) paired with its own real
// ModelTransform and whether it's currently selected/out-of-bounds. `id` is the owning
// ProjectObject's own id - the renderer diffs its internal per-object GL state by this id, not by
// list position, so reordering/adding/removing objects doesn't force a full re-upload of
// everything.
// Never a real ProjectObject id (those are UUID.randomUUID().toString()) - safe as a sentinel in
// the same `framedIds: Set<String>` the real per-object camera-framing logic already uses.
private const val EMPTY_PLATE_FRAMED_KEY = "__empty_plate__"

// Extra geometry drawn over an object in its own model matrix (painted regions, modifier/blocker outlines):
// [vertices] are position + normal (6 floats each), in the same frame as the object's mesh; [lines] draws
// GL_LINES instead of triangles.
class OverlayGroup(val color: FloatArray, val vertices: FloatArray, val lines: Boolean = false)

data class RenderableObject(
    val id: String,
    val geometry: MeshGeometry,
    val transform: ModelTransform,
    val selected: Boolean = false,
    val outOfBounds: Boolean = false,
    val overlays: List<OverlayGroup> = emptyList(),
)

private class PerObjectGLState {
    var vbo = 0
    var vertexCount = 0
    var uploaded = false
    var overlaySource: List<OverlayGroup>? = null
    var overlayVbos = IntArray(0)
    var overlayCounts = IntArray(0)
}

class ProjectGLRenderer : GLSurfaceView.Renderer {
    // Same volatile-handoff contract as MeshGLRenderer.pendingMesh: written by the UI thread,
    // consumed once at the top of onDrawFrame, never touched from the GL thread's own state.
    @Volatile var pendingObjects: List<RenderableObject>? = null
    // WO-30 follow-up (owner: unhappy with the empty-plate viewport - "Add a model to start..."
    // over a flat black box, no real bed visible at all): the real per-printer bed polygon
    // (BedShape.kt, the same one ProjectWorkspace already reads for out-of-bounds tinting), read
    // here too so onDrawFrame can draw a real bed grid even with zero objects on the plate, rather
    // than only ever drawing a grid sized around at least one already-loaded object's geometry.
    @Volatile var bedShape: BedShape? = null
    @Volatile var cameraState: CameraOrbit = CameraOrbit(45f, 30f, 200f)
    @Volatile var lastVpMatrix: FloatArray? = null
    @Volatile var viewportWidth = 1
    @Volatile var viewportHeight = 1

    private var meshProgram = 0
    private var gridProgram = 0
    private var gridVbo = 0
    private var gridVertexCount = 0
    // GL-thread-only. Keyed by ProjectObject id - re-uploaded only when that object's geometry
    // reference actually changes (a new object, or a reload), not on every transform-only update
    // (a transform change only touches the model matrix uniform, no VBO re-upload needed).
    private val perObject = HashMap<String, PerObjectGLState>()
    private val perObjectGeometry = HashMap<String, MeshGeometry>()
    private var currentObjects: List<RenderableObject> = emptyList()
    private var width = 1
    private var height = 1
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val normalMatrix = FloatArray(9)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.06f, 0.06f, 0.07f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        meshProgram = buildGLProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
        gridProgram = buildGLProgram(GRID_VERTEX_SHADER_MULTI, GRID_FRAGMENT_SHADER_MULTI)
        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        gridVbo = buffers[0]
        gridVertexCount = 0
        perObject.clear()
        // Force re-upload of every currently-known object (and the bed/plate grid) into the
        // fresh EGL context (e.g. after onPause()/onResume() across backgrounding tore the old
        // context/VBOs down) - real bug caught live: backgrounding then resuming this screen with
        // zero objects on an otherwise-known bed left the grid gone (gridVbo is a fresh, empty
        // buffer in the new context) because this only ever re-armed pendingObjects for a
        // non-empty plate. bedShape != null covers the empty-plate-with-a-known-bed case too.
        if (currentObjects.isNotEmpty() || bedShape != null) pendingObjects = currentObjects
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        viewportWidth = w; viewportHeight = h
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingObjects?.let { objects ->
            val liveIds = objects.map { it.id }.toSet()
            // Drop GL state for any object no longer on the plate (removed since the last
            // upload) - otherwise its VBO leaks for the renderer's lifetime.
            val stale = perObject.keys - liveIds
            for (id in stale) {
                val state = perObject.remove(id) ?: continue
                GLES30.glDeleteBuffers(1, intArrayOf(state.vbo), 0)
                if (state.overlayVbos.isNotEmpty()) GLES30.glDeleteBuffers(state.overlayVbos.size, state.overlayVbos, 0)
                perObjectGeometry.remove(id)
            }
            for (obj in objects) {
                val previousGeometry = perObjectGeometry[obj.id]
                if (previousGeometry !== obj.geometry) { // same geometry reference - only the transform changed, no re-upload needed
                    val state = perObject.getOrPut(obj.id) {
                        val handles = IntArray(1)
                        GLES30.glGenBuffers(1, handles, 0)
                        PerObjectGLState().also { it.vbo = handles[0] }
                    }
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, state.vbo)
                    val buffer = directFloatBuffer(obj.geometry.vertexData)
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, obj.geometry.vertexData.size * 4, buffer, GLES30.GL_STATIC_DRAW)
                    state.vertexCount = obj.geometry.vertexData.size / 6
                    state.uploaded = true
                    perObjectGeometry[obj.id] = obj.geometry
                }
                val state = perObject[obj.id] ?: continue
                if (state.overlaySource !== obj.overlays) {
                    if (state.overlayVbos.isNotEmpty()) GLES30.glDeleteBuffers(state.overlayVbos.size, state.overlayVbos, 0)
                    val vbos = IntArray(obj.overlays.size)
                    if (vbos.isNotEmpty()) GLES30.glGenBuffers(vbos.size, vbos, 0)
                    obj.overlays.forEachIndexed { i, group ->
                        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[i])
                        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, group.vertices.size * 4, directFloatBuffer(group.vertices), GLES30.GL_STATIC_DRAW)
                    }
                    state.overlayVbos = vbos
                    state.overlayCounts = IntArray(obj.overlays.size) { obj.overlays[it].vertices.size / 6 }
                    state.overlaySource = obj.overlays
                }
            }
            // WO-30 follow-up: an empty plate still draws the real bed's own grid (bedShape),
            // instead of buildPlateGrid's own object-footprint fallback, which has nothing to
            // measure with zero objects and previously left the whole viewport blank.
            val grid = if (objects.isNotEmpty()) buildPlateGrid(objects) else bedShape?.let { buildBedGrid(it) } ?: FloatArray(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gridVbo)
            val gridBuffer = directFloatBuffer(grid)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, grid.size * 4, gridBuffer, GLES30.GL_STATIC_DRAW)
            gridVertexCount = grid.size / 3
            currentObjects = objects
            pendingObjects = null
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        // WO-30 follow-up: only bail out with nothing drawn when there's truly nothing to show -
        // no objects AND no known bed shape yet (the very first frame or two, before the real
        // per-printer profile has loaded). Once a bed shape is known, its grid (uploaded above)
        // still deserves a real camera and a real draw call even with zero objects on it.
        if (currentObjects.isEmpty() && bedShape == null) return

        val plateCenter = if (currentObjects.isNotEmpty()) plateCenterOf(currentObjects) else bedShape?.let { bedCenterOf(it) } ?: floatArrayOf(0f, 0f, 0f)
        val cam = cameraState
        val azRad = Math.toRadians(cam.azimuthDeg.toDouble())
        val elRad = Math.toRadians(cam.elevationDeg.toDouble())
        val horizontal = cam.distance * cos(elRad)
        val eyeX = plateCenter[0] + (horizontal * cos(azRad)).toFloat()
        val eyeY = plateCenter[1] + (horizontal * sin(azRad)).toFloat()
        val eyeZ = plateCenter[2] + (cam.distance * sin(elRad)).toFloat()
        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, plateCenter[0], plateCenter[1], plateCenter[2], 0f, 0f, 1f)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 45f, aspect, (cam.distance * 0.02f).coerceAtLeast(0.1f), cam.distance * 10f)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
        lastVpMatrix = vpMatrix.copyOf()

        GLES30.glUseProgram(gridProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(gridProgram, "uMVP"), 1, false, vpMatrix, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(gridProgram, "uColor"), 0.42f, 0.45f, 0.47f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gridVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, gridVertexCount)

        GLES30.glUseProgram(meshProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(meshProgram, "uMVP"), 1, false, vpMatrix, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(meshProgram, "uLightDir"), 0.35f, 0.35f, 0.87f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(meshProgram, "uAmbient"), 0.35f)

        for (obj in currentObjects) {
            val state = perObject[obj.id] ?: continue
            if (!state.uploaded || state.vertexCount == 0) continue
            val t = obj.transform
            val pivot = obj.geometry.origin
            // Same T(offset) * T(pivot) * Rz * S * T(-pivot) order as MeshGLRenderer/the native
            // engine's own load_and_place_model - what's shown here is what would actually slice.
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, t.offsetXMm, t.offsetYMm, 0f)
            Matrix.translateM(modelMatrix, 0, pivot[0], pivot[1], pivot[2])
            Matrix.rotateM(modelMatrix, 0, t.rotationZDeg, 0f, 0f, 1f)
            Matrix.scaleM(modelMatrix, 0, t.scale, t.scale, t.scale)
            Matrix.translateM(modelMatrix, 0, -pivot[0], -pivot[1], -pivot[2])
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(meshProgram, "uModel"), 1, false, modelMatrix, 0)
            normalMatrix3x3From4x4Multi(modelMatrix, normalMatrix)
            GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(meshProgram, "uNormalMatrix"), 1, false, normalMatrix, 0)
            val color = when {
                obj.outOfBounds -> floatArrayOf(0.92f, 0.24f, 0.24f)
                obj.selected -> floatArrayOf(0.35f, 0.75f, 0.95f) // selection accent, distinct from the print-orange default
                else -> floatArrayOf(242f / 255f, 117f / 255f, 78f / 255f)
            }
            GLES30.glUniform3f(GLES30.glGetUniformLocation(meshProgram, "uBaseColor"), color[0], color[1], color[2])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, state.vbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, state.vertexCount)
            // Painted regions / region outlines, in the same model matrix, nudged toward the camera so they win the depth test.
            if (state.overlayVbos.isNotEmpty()) {
                GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
                GLES30.glPolygonOffset(-2f, -2f)
                obj.overlays.forEachIndexed { i, group ->
                    if (i >= state.overlayVbos.size || state.overlayCounts[i] == 0) return@forEachIndexed
                    GLES30.glUniform3f(GLES30.glGetUniformLocation(meshProgram, "uBaseColor"), group.color[0], group.color[1], group.color[2])
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, state.overlayVbos[i])
                    GLES30.glEnableVertexAttribArray(0)
                    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
                    GLES30.glEnableVertexAttribArray(1)
                    GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
                    if (group.lines) { GLES30.glDisable(GLES30.GL_CULL_FACE); GLES30.glDrawArrays(GLES30.GL_LINES, 0, state.overlayCounts[i]); GLES30.glEnable(GLES30.GL_CULL_FACE) }
                    else GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, state.overlayCounts[i])
                }
                GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            }
        }
    }

    private fun normalMatrix3x3From4x4Multi(m4: FloatArray, out3: FloatArray) {
        out3[0] = m4[0]; out3[1] = m4[1]; out3[2] = m4[2]
        out3[3] = m4[4]; out3[4] = m4[5]; out3[5] = m4[6]
        out3[6] = m4[8]; out3[7] = m4[9]; out3[8] = m4[10]
    }

    // The plate's own real-world center, as the union of every object's *live-transformed*
    // world-space position (pivot + offset) - not just the first object, and not a fixed origin,
    // so the camera frames the whole build plate regardless of how objects are arranged.
    private fun plateCenterOf(objects: List<RenderableObject>): FloatArray {
        var sumX = 0f; var sumY = 0f; var sumZ = 0f
        for (obj in objects) {
            sumX += obj.geometry.origin[0] + obj.transform.offsetXMm
            sumY += obj.geometry.origin[1] + obj.transform.offsetYMm
            sumZ += obj.geometry.origin[2]
        }
        val n = objects.size.coerceAtLeast(1)
        return floatArrayOf(sumX / n, sumY / n, sumZ / n)
    }

    // A reference grid sized around every object's live-transformed footprint (the union, not
    // just one object) - same v1 approximation as MeshGLRenderer.buildGrid (no real per-printer
    // bed size is known this early in the flow), generalized to cover the whole plate.
    private fun buildPlateGrid(objects: List<RenderableObject>): FloatArray {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        for (obj in objects) {
            val ox = obj.geometry.origin[0] + obj.transform.offsetXMm
            val oy = obj.geometry.origin[1] + obj.transform.offsetYMm
            val halfSpan = (obj.geometry.radius * obj.transform.scale).coerceAtLeast(1f)
            minX = minOf(minX, ox - halfSpan); maxX = maxOf(maxX, ox + halfSpan)
            minY = minOf(minY, oy - halfSpan); maxY = maxOf(maxY, oy + halfSpan)
            minZ = minOf(minZ, obj.geometry.minZ)
        }
        val centerX = (minX + maxX) / 2f; val centerY = (minY + maxY) / 2f
        val half = (maxOf(maxX - minX, maxY - minY) / 2f * 1.3f).coerceAtLeast(20f)
        val step = (half * 2f / 10f).coerceAtLeast(0.1f)
        val z = minZ
        val lines = ArrayList<Float>()
        var x = -half
        while (x <= half + 1e-4f) {
            lines.addAll(listOf(centerX + x, centerY - half, z, centerX + x, centerY + half, z))
            x += step
        }
        var y = -half
        while (y <= half + 1e-4f) {
            lines.addAll(listOf(centerX - half, centerY + y, z, centerX + half, centerY + y, z))
            y += step
        }
        return lines.toFloatArray()
    }

    // WO-30 follow-up: the real bed polygon's own bounding box, centered and stepped the same way
    // buildPlateGrid already does for an object-footprint grid - drawn when the plate has zero
    // objects, so the viewport shows the actual printer's bed instead of nothing at all.
    private fun buildBedGrid(bed: BedShape): FloatArray {
        val xs = bed.points.map { it.first }; val ys = bed.points.map { it.second }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val centerX = (minX + maxX) / 2f; val centerY = (minY + maxY) / 2f
        val half = (maxOf(maxX - minX, maxY - minY) / 2f).coerceAtLeast(20f)
        val step = (half * 2f / 10f).coerceAtLeast(0.1f)
        val lines = ArrayList<Float>()
        var x = -half
        while (x <= half + 1e-4f) {
            lines.addAll(listOf(centerX + x, centerY - half, 0f, centerX + x, centerY + half, 0f))
            x += step
        }
        var y = -half
        while (y <= half + 1e-4f) {
            lines.addAll(listOf(centerX - half, centerY + y, 0f, centerX + half, centerY + y, 0f))
            y += step
        }
        return lines.toFloatArray()
    }

    private fun bedCenterOf(bed: BedShape): FloatArray {
        val centerX = bed.points.map { it.first }.average().toFloat()
        val centerY = bed.points.map { it.second }.average().toFloat()
        return floatArrayOf(centerX, centerY, 0f)
    }

}

// The real bed's own diagonal span - the empty-plate camera's own default framing distance,
// matching the same "* 3f" multiplier ProjectWorkspace's own compose-side auto-frame effect uses
// for a populated plate (CameraOrbit(45f, 25f, maxSpan * 3f)). Top-level (not a member of
// ProjectGLRenderer) since it's called from the composable side, before any renderer instance's
// own per-frame state is relevant.
private fun defaultDistanceFor(bed: BedShape): Float {
    val xs = bed.points.map { it.first }; val ys = bed.points.map { it.second }
    val span = maxOf((xs.max() - xs.min()), (ys.max() - ys.min())).coerceAtLeast(20f)
    return span * 1.6f
}

private const val MESH_VERTEX_SHADER = """#version 300 es
uniform mat4 uMVP;
uniform mat4 uModel;
uniform mat3 uNormalMatrix;
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
out vec3 vNormal;
void main() {
    vNormal = normalize(uNormalMatrix * aNormal);
    gl_Position = uMVP * uModel * vec4(aPosition, 1.0);
}
"""

private const val MESH_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in vec3 vNormal;
uniform vec3 uLightDir;
uniform vec3 uBaseColor;
uniform float uAmbient;
out vec4 fragColor;
void main() {
    float ndotl = max(dot(normalize(vNormal), uLightDir), 0.0);
    float shade = uAmbient + (1.0 - uAmbient) * ndotl;
    fragColor = vec4(uBaseColor * shade, 1.0);
}
"""

private const val GRID_VERTEX_SHADER_MULTI = """#version 300 es
uniform mat4 uMVP;
layout(location = 0) in vec3 aPosition;
void main() { gl_Position = uMVP * vec4(aPosition, 1.0); }
"""

private const val GRID_FRAGMENT_SHADER_MULTI = """#version 300 es
precision mediump float;
uniform vec3 uColor;
out vec4 fragColor;
void main() { fragColor = vec4(uColor, 1.0); }
"""

// One ProjectObject paired with its already-loaded MeshGeometry - the shape the workspace
// composable actually needs (a caller/ViewModel loads geometry per object, e.g. via
// MeshLoader.load(object.sourceFileUri's path), once per distinct file, same as ModelViewer's own
// MeshLoader.load call).
data class WorkspaceObject(val projectObject: ProjectObject, val geometry: MeshGeometry, val overlays: List<OverlayGroup> = emptyList())

// Nearest-hit test for tap-to-select: a real ray/sphere intersection against each object's own
// bounding sphere (geometry.center/radius, live-transformed by offset+scale) - a deliberate v1
// approximation (not per-triangle) matching how cheap/fast a *selection* test needs to be; the
// precise per-triangle ray test (AABBMesh::query_ray_hit) is reserved for support painting, a
// single-object operation where that precision actually matters (see ModelViewer's Paint mode).
private fun pickObject(objects: List<WorkspaceObject>, rayOrigin: FloatArray, rayDir: FloatArray): String? {
    var bestId: String? = null
    var bestT = Float.MAX_VALUE
    for (wo in objects) {
        val t = wo.projectObject.transform()
        val g = wo.geometry
        // An earlier version used `g.center + offset` directly - mathematically identical to
        // this for the zero-rotation/unit-scale case (the rotate-about-pivot terms cancel out),
        // which is why a same-numbers real-device test didn't catch it, but silently wrong for
        // any rotated or scaled object, since it ignores the pivot the renderer's own model
        // matrix (and computeOutOfBounds's bed-polygon check) actually rotate/scale about. This
        // applies the identical rotate-about-pivot-then-translate math both of those already use,
        // so the picked sphere is centered on the point that's actually on screen at any
        // transform, not just the identity one.
        val pivot = g.origin
        val rad = Math.toRadians(t.rotationZDeg.toDouble())
        val cosR = kotlin.math.cos(rad).toFloat(); val sinR = kotlin.math.sin(rad).toFloat()
        val lx = (g.center[0] - pivot[0]) * t.scale
        val ly = (g.center[1] - pivot[1]) * t.scale
        val lz = (g.center[2] - pivot[2]) * t.scale
        val worldCenter = floatArrayOf(
            pivot[0] + lx * cosR - ly * sinR + t.offsetXMm,
            pivot[1] + lx * sinR + ly * cosR + t.offsetYMm,
            pivot[2] + lz,
        )
        val cx = worldCenter[0] - rayOrigin[0]
        val cy = worldCenter[1] - rayOrigin[1]
        val cz = worldCenter[2] - rayOrigin[2]
        val proj = cx * rayDir[0] + cy * rayDir[1] + cz * rayDir[2]
        if (proj < 0f) continue
        val closestX = rayOrigin[0] + rayDir[0] * proj
        val closestY = rayOrigin[1] + rayDir[1] * proj
        val closestZ = rayOrigin[2] + rayDir[2] * proj
        val dx = worldCenter[0] - closestX; val dy = worldCenter[1] - closestY; val dz = worldCenter[2] - closestZ
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        val radius = g.radius * t.scale
        if (dist <= radius && proj < bestT) {
            bestT = proj
            bestId = wo.projectObject.id
        }
    }
    return bestId
}

// WO-30 follow-up (owner: "I should be able to rotate the model just by swiping around the box,
// not having to necessarily pinch and rotate"): which real action a one-finger drag on the
// selected object performs. MOVE (the default, unchanged) repositions it on the bed; ROTATE spins
// it around Z, driven by the drag's own horizontal pixel distance (a real, ordinary swipe -
// tuned so a full-width swipe is roughly a half turn) rather than requiring a genuine two-finger
// twist, which stays available in both modes for anyone who prefers it.
enum class WorkspaceInteractionMode { MOVE, ROTATE }

/**
 * The real multi-object build-plate view: renders every object in [objects] at once (via
 * [ProjectGLRenderer]), tap to select one (highlighted, drives [onSelect]), drag to move the
 * currently selected object on the bed plane (same real ray/plane math as ModelViewer's own
 * Transform mode - [rayPlaneXY], made internal there for exactly this reuse - so a drag here moves
 * the object exactly where the finger points, not a proportional screen-pixel guess), pinch/twist
 * to rotate/scale it, and orbit the camera with a one-finger drag outside any object (matching
 * ModelViewer's own Select-mode camera gesture). Per-object out-of-bounds highlighting reuses
 * ModelViewer's own [computeOutOfBounds] - the exact same bed-polygon check, not a second one that
 * could quietly diverge.
 */
@Composable fun ProjectWorkspace(
    objects: List<WorkspaceObject>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onTransformChange: (String, ModelTransform) -> Unit,
    bedShape: BedShape? = null,
    // WO-30 follow-up: MOVE by default so every existing caller (and ProjectWorkspaceDeviceTest's
    // own drag test) keeps today's real, tested behavior unchanged.
    interactionMode: WorkspaceInteractionMode = WorkspaceInteractionMode.MOVE,
    // Phase 1 (Consumer Slicer Plan §16): object ids the caller has already determined are
    // colliding with at least one other object (ProjectEditorScreen's own real footprintsOverlap
    // check, the same rotate-about-pivot math this file's pickObject/plateCenterOf already use) -
    // rendered with the same out-of-bounds red tint, since both are "this can't be sliced safely
    // as placed" signals. Computed by the caller, not here, so ProjectEditorScreen's Slice gate
    // and this render tint can never disagree about which objects are flagged.
    collidingIds: Set<String> = emptySet(),
    // Face-pick / measure tools: when non-null, a tap hands the world-space ray (origin, direction) to the
    // caller instead of selecting.
    onRayTap: ((FloatArray, FloatArray) -> Unit)? = null,
    // Paint tool: when non-null a one-finger drag reports rays (origin, direction, isStart) instead of moving the
    // object or orbiting the camera.
    onRayDrag: ((FloatArray, FloatArray, Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val renderer = remember { ProjectGLRenderer() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Which object ids the camera has already been auto-framed for - re-framing on every
    // transform-only update would fight a manual orbit/zoom the owner is mid-gesture on, but a
    // *newly added* object (not yet in this set) should still pull the camera back far enough to
    // see it, the same real problem ModelViewer.kt solves for its own single object via
    // `renderer.cameraState = CameraOrbit(45f, 25f, loaded.radius * 3f)` on load - fixed distance
    // here would put a 200mm-plate project's far objects off-screen (or a single small object lost
    // in a huge default view), a real usability gap this closes.
    var framedIds by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(objects, selectedId, bedShape, collidingIds) {
        val renderables = withContext(Dispatchers.Default) {
            objects.map { wo ->
                val t = wo.projectObject.transform()
                val outOfBounds = (bedShape?.let { computeOutOfBounds(wo.geometry, t, it) } ?: false) || wo.projectObject.id in collidingIds
                RenderableObject(wo.projectObject.id, wo.geometry, t, selected = wo.projectObject.id == selectedId, outOfBounds = outOfBounds, overlays = wo.overlays)
            }
        }
        renderer.bedShape = bedShape
        renderer.pendingObjects = renderables
        val liveIds = objects.map { it.projectObject.id }.toSet()
        // WO-30 follow-up: an empty plate still gets a real, one-time camera framing around the
        // real bed (once bedShape is known) instead of sitting at the renderer's own fixed
        // CameraOrbit(45, 30, 200) default, which is wildly wrong for most real bed sizes. The
        // sentinel key (never a real object id) marks this framing as already done so it doesn't
        // refight a manual orbit/zoom gesture on every later recomposition.
        if (liveIds.isEmpty() && bedShape != null && EMPTY_PLATE_FRAMED_KEY !in framedIds) {
            renderer.cameraState = CameraOrbit(45f, 25f, defaultDistanceFor(bedShape))
            framedIds = setOf(EMPTY_PLATE_FRAMED_KEY)
        }
        if (liveIds.isNotEmpty() && !framedIds.containsAll(liveIds)) {
            var centerX = 0f; var centerY = 0f; var centerZ = 0f
            for (wo in objects) {
                val t = wo.projectObject.transform()
                centerX += wo.geometry.origin[0] + t.offsetXMm
                centerY += wo.geometry.origin[1] + t.offsetYMm
                centerZ += wo.geometry.origin[2]
            }
            val n = objects.size
            centerX /= n; centerY /= n; centerZ /= n
            var maxSpan = 1f
            for (wo in objects) {
                val t = wo.projectObject.transform()
                val ox = wo.geometry.origin[0] + t.offsetXMm
                val oy = wo.geometry.origin[1] + t.offsetYMm
                val dist = sqrt((ox - centerX) * (ox - centerX) + (oy - centerY) * (oy - centerY)) + wo.geometry.radius * t.scale
                if (dist > maxSpan) maxSpan = dist
            }
            renderer.cameraState = CameraOrbit(45f, 25f, maxSpan * 3f)
            framedIds = liveIds
        }
        glView?.requestRender()
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> glView?.onPause()
                Lifecycle.Event.ON_START -> glView?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(modifier) {
        Box {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(320.dp).testTag("project-workspace")
                    .pointerInput(objects.map { it.projectObject.id }, selectedId, onRayTap != null) {
                        detectTapGestures { offset ->
                            val vp = renderer.lastVpMatrix ?: return@detectTapGestures
                            val w = renderer.viewportWidth; val h = renderer.viewportHeight
                            if (w <= 1 || h <= 1) return@detectTapGestures
                            val ray = unprojectRay(vp, w, h, offset.x, offset.y)
                            if (onRayTap != null) { onRayTap(floatArrayOf(ray[0], ray[1], ray[2]), floatArrayOf(ray[3], ray[4], ray[5])); return@detectTapGestures }
                            val hit = pickObject(objects, floatArrayOf(ray[0], ray[1], ray[2]), floatArrayOf(ray[3], ray[4], ray[5]))
                            onSelect(hit)
                        }
                    }
                    // interactionMode is a real key here (not just read from the closure) so
                    // toggling Move/Rotate mid-idle (no gesture in progress) relaunches this
                    // coroutine and picks up the new mode immediately - the same staleness class
                    // of bug this file's own runningTransform fix (below) exists to avoid.
                    .pointerInput(onRayDrag != null) {
                        if (onRayDrag == null) return@pointerInput
                        fun report(pos: androidx.compose.ui.geometry.Offset, start: Boolean) {
                            val vp = renderer.lastVpMatrix ?: return
                            val w = renderer.viewportWidth; val h = renderer.viewportHeight
                            if (w <= 1 || h <= 1) return
                            val ray = unprojectRay(vp, w, h, pos.x, pos.y)
                            onRayDrag(floatArrayOf(ray[0], ray[1], ray[2]), floatArrayOf(ray[3], ray[4], ray[5]), start)
                        }
                        detectDragGestures(
                            onDragStart = { report(it, true) },
                            onDrag = { change, _ -> change.consume(); report(change.position, false) },
                        )
                    }
                    .pointerInput(objects.map { it.projectObject.id }, selectedId, interactionMode, onRayDrag != null) {
                        if (onRayDrag != null) return@pointerInput
                        // Real bug fix (owner-reported, live on the Razr 2026: "it allows me to
                        // pinch momentarily then jumps back to full size. Attempted rotation has
                        // no effect"): detectTransformGestures' own pan/zoom/rotation are each
                        // INCREMENTAL since the *previous* callback, not cumulative since the
                        // gesture started (confirmed against Compose foundation's own
                        // TransformGestureDetector.kt - onGesture is fed event.calculateZoom()/
                        // calculateRotation() per pointer event, not an accumulated total). The
                        // old code re-read `current = selected.projectObject.transform()` from
                        // this composable's own `objects` parameter on every single callback -
                        // but this `pointerInput` coroutine keeps running unchanged (its keys are
                        // just the object id list + selectedId) for the *whole* gesture, so that
                        // parameter is frozen at whatever it was when the gesture began; the real,
                        // just-written value from `onTransformChange`'s own async Room round trip
                        // never reaches this closure mid-gesture. Every callback was therefore
                        // computing base(frozen at gesture start) * only-the-latest-increment
                        // instead of accumulating increment-over-increment, so the reported scale/
                        // rotation tracked only the single most recent (often tiny,
                        // near-identity) delta rather than the real cumulative pinch/twist - a
                        // real, cumulative local running transform, not the possibly-stale
                        // `objects` parameter, is the actual fix.
                        var runningTransform: ModelTransform? = null
                        detectTransformGestures { centroid, pan, zoom, rotation ->
                            val selected = objects.find { it.projectObject.id == selectedId }
                            if (selected == null) {
                                val cam = renderer.cameraState
                                renderer.cameraState = CameraOrbit(
                                    azimuthDeg = cam.azimuthDeg - pan.x * 0.4f,
                                    elevationDeg = (cam.elevationDeg + pan.y * 0.4f).coerceIn(-89f, 89f),
                                    distance = (cam.distance / zoom).coerceIn(1f, 4000f),
                                )
                                glView?.requestRender()
                                return@detectTransformGestures
                            }
                            val current = runningTransform ?: selected.projectObject.transform()
                            var offsetX = current.offsetXMm
                            var offsetY = current.offsetYMm
                            // WO-30 follow-up (owner: "I should be able to rotate the model just
                            // by swiping around the box, not having to necessarily pinch and
                            // rotate"): in ROTATE mode, the drag's own horizontal pixel distance
                            // becomes a rotation instead of an offset - a plain one-finger swipe,
                            // not a two-finger twist. A genuine two-finger twist (`rotation`,
                            // below) still applies in both modes for anyone who prefers it; the
                            // two never conflict since one-finger drags never produce a nonzero
                            // `rotation` in the first place (it needs two points to measure an
                            // angle between).
                            var swipeRotationDeg = 0f
                            if (interactionMode == WorkspaceInteractionMode.MOVE) {
                                val vp = renderer.lastVpMatrix
                                val w = renderer.viewportWidth; val h = renderer.viewportHeight
                                if (vp != null && w > 1 && h > 1 && (pan.x != 0f || pan.y != 0f)) {
                                    val from = rayPlaneXY(vp, w, h, centroid.x - pan.x, centroid.y - pan.y, 0f)
                                    val to = rayPlaneXY(vp, w, h, centroid.x, centroid.y, 0f)
                                    offsetX += to[0] - from[0]
                                    offsetY += to[1] - from[1]
                                }
                            } else {
                                // Same sensitivity as the no-selection camera-orbit azimuth above
                                // (pan.x * 0.4f) - a familiar, already-tuned feel, not a new
                                // invented constant.
                                swipeRotationDeg = pan.x * 0.4f
                            }
                            // Real bug fix (owner-reported, live on the Razr 2026: "rotation is
                            // rotating the opposite direction than intended - trying to rotate
                            // left rotates right and vice versa"): Compose's own `rotation` here
                            // is a screen-space angle (positive = clockwise as drawn, standard
                            // Android/Compose convention, Y axis down), but rotationZDeg feeds
                            // Matrix.rotateM's rotation about +Z (OpenGL's right-hand rule -
                            // positive = counterclockwise when viewed from the +Z side looking
                            // back toward the origin, the same side this plate's default camera
                            // orbit views it from). Adding the raw value directly span the two
                            // opposite conventions - negating it here is the real fix, not a
                            // cosmetic sign flip elsewhere. swipeRotationDeg is defined with the
                            // same sign convention (matches the camera-orbit azimuth's own
                            // `cam.azimuthDeg - pan.x * 0.4f` above) so it's negated the same way.
                            val updated = ModelTransform(
                                offsetXMm = offsetX,
                                offsetYMm = offsetY,
                                rotationZDeg = (current.rotationZDeg - rotation - swipeRotationDeg) % 360f,
                                scale = (current.scale * zoom).coerceIn(0.1f, 10f),
                            )
                            runningTransform = updated
                            onTransformChange(selected.projectObject.id, updated)
                        }
                    },
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(3)
                        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        glView = this
                    }
                },
                onRelease = { it.onPause() },
            )
            if (objects.isEmpty()) {
                // WO-30 follow-up: now floats centered over the real bed grid instead of pinned
                // top-left over what used to be a flat black box - a real background scrim keeps
                // it legible against the grid lines behind it.
                Text(
                    "Add a model to start this project's build plate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("project-workspace-empty"),
                )
            }
        }
    }
}
