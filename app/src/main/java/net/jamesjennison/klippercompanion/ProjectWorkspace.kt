package net.jamesjennison.klippercompanion

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
data class RenderableObject(
    val id: String,
    val geometry: MeshGeometry,
    val transform: ModelTransform,
    val selected: Boolean = false,
    val outOfBounds: Boolean = false,
)

private class PerObjectGLState {
    var vbo = 0
    var vertexCount = 0
    var uploaded = false
}

class ProjectGLRenderer : GLSurfaceView.Renderer {
    // Same volatile-handoff contract as MeshGLRenderer.pendingMesh: written by the UI thread,
    // consumed once at the top of onDrawFrame, never touched from the GL thread's own state.
    @Volatile var pendingObjects: List<RenderableObject>? = null
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
        // Force re-upload of every currently-known object into the fresh EGL context (e.g. after
        // onPause()/onResume() across backgrounding tore the old context/VBOs down).
        if (currentObjects.isNotEmpty()) pendingObjects = currentObjects
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
                perObjectGeometry.remove(id)
            }
            for (obj in objects) {
                val previousGeometry = perObjectGeometry[obj.id]
                if (previousGeometry === obj.geometry) continue // same geometry reference - only the transform changed, no re-upload needed
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
            val grid = buildPlateGrid(objects)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gridVbo)
            val gridBuffer = directFloatBuffer(grid)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, grid.size * 4, gridBuffer, GLES30.GL_STATIC_DRAW)
            gridVertexCount = grid.size / 3
            currentObjects = objects
            pendingObjects = null
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (currentObjects.isEmpty()) return

        val plateCenter = plateCenterOf(currentObjects)
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
data class WorkspaceObject(val projectObject: ProjectObject, val geometry: MeshGeometry)

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

    LaunchedEffect(objects, selectedId, bedShape) {
        val renderables = withContext(Dispatchers.Default) {
            objects.map { wo ->
                val t = wo.projectObject.transform()
                val outOfBounds = bedShape?.let { computeOutOfBounds(wo.geometry, t, it) } ?: false
                RenderableObject(wo.projectObject.id, wo.geometry, t, selected = wo.projectObject.id == selectedId, outOfBounds = outOfBounds)
            }
        }
        renderer.pendingObjects = renderables
        val liveIds = objects.map { it.projectObject.id }.toSet()
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
                    .pointerInput(objects.map { it.projectObject.id }, selectedId) {
                        detectTapGestures { offset ->
                            val vp = renderer.lastVpMatrix ?: return@detectTapGestures
                            val w = renderer.viewportWidth; val h = renderer.viewportHeight
                            if (w <= 1 || h <= 1) return@detectTapGestures
                            val ray = unprojectRay(vp, w, h, offset.x, offset.y)
                            val hit = pickObject(objects, floatArrayOf(ray[0], ray[1], ray[2]), floatArrayOf(ray[3], ray[4], ray[5]))
                            onSelect(hit)
                        }
                    }
                    .pointerInput(objects.map { it.projectObject.id }, selectedId) {
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
                            val current = selected.projectObject.transform()
                            var offsetX = current.offsetXMm
                            var offsetY = current.offsetYMm
                            val vp = renderer.lastVpMatrix
                            val w = renderer.viewportWidth; val h = renderer.viewportHeight
                            if (vp != null && w > 1 && h > 1 && (pan.x != 0f || pan.y != 0f)) {
                                val from = rayPlaneXY(vp, w, h, centroid.x - pan.x, centroid.y - pan.y, 0f)
                                val to = rayPlaneXY(vp, w, h, centroid.x, centroid.y, 0f)
                                offsetX += to[0] - from[0]
                                offsetY += to[1] - from[1]
                            }
                            onTransformChange(
                                selected.projectObject.id,
                                ModelTransform(
                                    offsetXMm = offsetX,
                                    offsetYMm = offsetY,
                                    rotationZDeg = (current.rotationZDeg + rotation) % 360f,
                                    scale = (current.scale * zoom).coerceIn(0.1f, 10f),
                                ),
                            )
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
                Text("Add a model to start this project's build plate.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("project-workspace-empty"))
            }
        }
    }
}
