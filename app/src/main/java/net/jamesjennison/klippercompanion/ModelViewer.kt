package net.jamesjennison.klippercompanion

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import kotlinx.coroutines.launch
import org.orcaslicer.engine.NativeEngine
import kotlinx.coroutines.withContext
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pre-slice half of the "visual, on-device, in-app slicer" (owner request, 2026-09-22,
 * PrusaSlicer's EasyPrint mode as the reference point): a real, rotatable/zoomable 3D view of
 * the shared model on a virtual bed, before any slicing setting is touched. The post-slice half
 * (a per-layer toolpath review of the actual sliced G-code) reuses the already-existing
 * LayerPreview/GcodePreview - this file is only the new piece, a real-time GL renderer.
 *
 * Architecture (validated against this codebase's own precedents before building - see the
 * approved plan): raw android.opengl (GLES30 + GLSurfaceView), not Filament/Sceneform (Sceneform
 * is dead; Filament is a second, unrelated native dependency this scoped a feature doesn't
 * justify). GLSurfaceView is embedded via AndroidView the same way LiveCamera.kt already embeds
 * a WebView. Flat Lambertian shading deliberately reuses thumbnail_render.cpp's exact lighting
 * formula and color, for visual consistency between the G-code thumbnail and this viewer.
 */

data class CameraOrbit(val azimuthDeg: Float, val elevationDeg: Float, val distance: Float)

data class MeshGeometry(val vertexData: FloatArray, val triangleCount: Int, val center: FloatArray, val radius: Float)

object MeshLoader {
    // Off the GL thread entirely - NativeEngine.nativeLoadMeshPreview() and this bounding-sphere
    // pass are both plain CPU work, dispatched the same way slicing itself already is.
    suspend fun load(path: String): MeshGeometry = withContext(Dispatchers.Default) {
        val data = NativeEngine.nativeLoadMeshPreview(path)
        require(data.isNotEmpty() && data.size % 18 == 0) { "Unexpected mesh preview data." }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < data.size) {
            val x = data[i]; val y = data[i + 1]; val z = data[i + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            i += 6
        }
        val center = floatArrayOf((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
        val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
        val radius = (sqrt(dx * dx + dy * dy + dz * dz) / 2f).coerceAtLeast(1f)
        MeshGeometry(data, data.size / 18, center, radius)
    }
}

private const val VERTEX_SHADER = """#version 300 es
uniform mat4 uMVP;
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
out vec3 vNormal;
void main() {
    vNormal = aNormal;
    gl_Position = uMVP * vec4(aPosition, 1.0);
}
"""

// Deliberately the same ambient+directional formula and light direction as
// thumbnail_render.cpp's CPU rasterizer (shade = ambient + (1-ambient)*max(dot(n,l),0)) - visual
// consistency between the embedded G-code thumbnail and this interactive viewer, not a separate
// lighting model invented for this file.
private const val FRAGMENT_SHADER = """#version 300 es
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

private const val GRID_VERTEX_SHADER = """#version 300 es
uniform mat4 uMVP;
layout(location = 0) in vec3 aPosition;
void main() { gl_Position = uMVP * vec4(aPosition, 1.0); }
"""

private const val GRID_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
uniform vec3 uColor;
out vec4 fragColor;
void main() { fragColor = vec4(uColor, 1.0); }
"""

// Shader compile/link/buffer-pack helpers moved to GLSupport.kt - shared with
// ToolpathGLRenderer (SlicedPreview.kt), which needs the identical machinery.

/**
 * GLSurfaceView.Renderer. Cross-thread contract (validated architecture review, see the plan):
 * [pendingMesh] is a volatile handoff written by [MeshLoader]'s caller (UI thread) and consumed
 * once, on the GL thread, at the top of [onDrawFrame] - no GL call ever happens off the GL
 * thread. [cameraState] is a single volatile immutable value (not three separate volatiles,
 * which could let onDrawFrame observe a torn mid-gesture read) written by the orbit/zoom gesture
 * handler (UI thread) and read once per frame here.
 */
class MeshGLRenderer : GLSurfaceView.Renderer {
    @Volatile var pendingMesh: MeshGeometry? = null
    @Volatile var cameraState: CameraOrbit = CameraOrbit(45f, 25f, 100f)
    // Paint overlay (WO-14 part D): the currently enforcer-painted triangles, position-only,
    // world-space (see engine::get_painted_facets's own comment - already in registration with
    // the base mesh, no extra transform needed here). Same volatile-handoff contract as
    // [pendingMesh].
    @Volatile var pendingOverlay: FloatArray? = null
    // A read-only snapshot of the view-projection matrix and viewport size, refreshed at the end
    // of every onDrawFrame, so the UI thread's paint-stroke touch handler can unproject a touch
    // point into a world-space ray without racing the GL thread for the live matrices above
    // (those are GL-thread-only; this copy is the volatile, safe-to-read-anywhere snapshot).
    @Volatile var lastVpMatrix: FloatArray? = null
    @Volatile var viewportWidth = 1
    @Volatile var viewportHeight = 1

    private var meshProgram = 0
    private var gridProgram = 0
    private var vbo = 0
    private var gridVbo = 0
    private var overlayVbo = 0
    private var vertexCount = 0
    private var gridVertexCount = 0
    private var overlayVertexCount = 0
    // GL-thread-only (never touched from the volatile handoff path): kept so onSurfaceCreated
    // can re-upload after a context recreation (e.g. after onPause()/onResume() across a
    // backgrounding), since the old VBO handle is invalid once the EGL context is torn down but
    // the raw vertex data is still cheap to re-upload from here.
    private var currentGeometry: MeshGeometry? = null
    private var currentOverlay: FloatArray? = null
    private var width = 1
    private var height = 1
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.06f, 0.06f, 0.07f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        meshProgram = buildGLProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        gridProgram = buildGLProgram(GRID_VERTEX_SHADER, GRID_FRAGMENT_SHADER)
        val buffers = IntArray(3)
        GLES30.glGenBuffers(3, buffers, 0)
        vbo = buffers[0]; gridVbo = buffers[1]; overlayVbo = buffers[2]
        vertexCount = 0; gridVertexCount = 0; overlayVertexCount = 0
        currentGeometry?.let { pendingMesh = it } // force re-upload into the fresh context
        currentOverlay?.let { pendingOverlay = it }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        viewportWidth = w; viewportHeight = h
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingMesh?.let { mesh ->
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val buffer = directFloatBuffer(mesh.vertexData)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertexData.size * 4, buffer, GLES30.GL_STATIC_DRAW)
            vertexCount = mesh.vertexData.size / 6

            val grid = buildGrid(mesh.center, mesh.radius)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gridVbo)
            val gridBuffer = directFloatBuffer(grid)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, grid.size * 4, gridBuffer, GLES30.GL_STATIC_DRAW)
            gridVertexCount = grid.size / 3

            currentGeometry = mesh
            pendingMesh = null
        }
        pendingOverlay?.let { overlay ->
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, overlayVbo)
            if (overlay.isNotEmpty()) {
                val buffer = directFloatBuffer(overlay)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, overlay.size * 4, buffer, GLES30.GL_STATIC_DRAW)
            }
            overlayVertexCount = overlay.size / 3
            currentOverlay = overlay
            pendingOverlay = null
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (vertexCount == 0) return

        val geometry = currentGeometry ?: return
        val cam = cameraState
        val azRad = Math.toRadians(cam.azimuthDeg.toDouble())
        val elRad = Math.toRadians(cam.elevationDeg.toDouble())
        val horizontal = cam.distance * cos(elRad)
        val eyeX = geometry.center[0] + (horizontal * cos(azRad)).toFloat()
        val eyeY = geometry.center[1] + (horizontal * sin(azRad)).toFloat()
        val eyeZ = geometry.center[2] + (cam.distance * sin(elRad)).toFloat()
        // Z is "up" (libslic3r's own convention - print height axis), matching
        // thumbnail_render.cpp's camera and every profile pack's bed_shape being an XY plane.
        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, geometry.center[0], geometry.center[1], geometry.center[2], 0f, 0f, 1f)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 45f, aspect, (cam.distance * 0.02f).coerceAtLeast(0.1f), cam.distance * 10f)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
        lastVpMatrix = vpMatrix.copyOf() // published after computing, for the UI thread's unprojection

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
        // This app's print-orange accent (matches thumbnail_render.cpp's base color).
        GLES30.glUniform3f(GLES30.glGetUniformLocation(meshProgram, "uBaseColor"), 242f / 255f, 117f / 255f, 78f / 255f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(meshProgram, "uAmbient"), 0.35f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)

        if (overlayVertexCount > 0) {
            // Reuses the grid's plain position+color shader (no lighting needed for a flat
            // highlight). glDepthMask(false): tested against the depth buffer so occluded paint
            // doesn't show through the model, but never written, so it can't corrupt the depth
            // buffer for whatever draws next (or next frame).
            GLES30.glDepthMask(false)
            GLES30.glUseProgram(gridProgram)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(gridProgram, "uMVP"), 1, false, vpMatrix, 0)
            // A bright, unmistakably "selection" cyan - nothing else in this view uses it.
            GLES30.glUniform3f(GLES30.glGetUniformLocation(gridProgram, "uColor"), 0.25f, 0.95f, 0.85f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, overlayVbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, overlayVertexCount)
            GLES30.glDepthMask(true)
        }
    }

    // A simple reference grid sized off the model's own footprint (no real per-printer bed size
    // is known this early in the flow - the slicing profile isn't chosen yet - an accepted v1
    // approximation, see the plan). Flat at the model's own lowest Z (its bed contact plane).
    private fun buildGrid(center: FloatArray, radius: Float): FloatArray {
        val half = radius * 1.6f
        val step = (half * 2f / 10f).coerceAtLeast(0.1f)
        val z = center[2] - radius // ensure_on_bed() in the native loader already put the model's own low point near its own local origin; this stays a visual reference, not a physical claim.
        val lines = ArrayList<Float>()
        var x = -half
        while (x <= half + 1e-4f) {
            lines.addAll(listOf(center[0] + x, center[1] - half, z, center[0] + x, center[1] + half, z))
            x += step
        }
        var y = -half
        while (y <= half + 1e-4f) {
            lines.addAll(listOf(center[0] - half, center[1] + y, z, center[0] + half, center[1] + y, z))
            y += step
        }
        return lines.toFloatArray()
    }
}

// Owned by SliceAndPrintPanel (remember(uri) { PaintUiState() }), read after slicing starts to
// decide whether to slice through the paint session (NativeEngine.nativeSlicePaintSession) or
// the plain file path (nativeSliceFile) - see SlicingCoordinator.kt. [painted] is a real signal
// ("at least one stroke produced a non-empty enforcer set"), not just "the user opened Paint
// mode" - opening the session but never actually painting anything must fall back to the plain
// path, same output as if painting didn't exist.
class PaintUiState {
    var handle by mutableStateOf<Long?>(null)
    var painted by mutableStateOf(false)
}

// Standard screen-to-world ray unprojection: the two NDC-space points at the near/far planes for
// this (touchX, touchY), each carried through the inverse view-projection matrix and perspective-
// divided, give two real world-space points on the same ray - their difference is the ray
// direction. No third-party math library needed; android.opengl.Matrix already has everything.
private fun unprojectRay(vp: FloatArray, viewportWidth: Int, viewportHeight: Int, touchX: Float, touchY: Float): FloatArray {
    val invVp = FloatArray(16)
    Matrix.invertM(invVp, 0, vp, 0)
    val ndcX = (touchX / viewportWidth) * 2f - 1f
    val ndcY = 1f - (touchY / viewportHeight) * 2f
    val nearClip = floatArrayOf(ndcX, ndcY, -1f, 1f)
    val farClip = floatArrayOf(ndcX, ndcY, 1f, 1f)
    val nearWorld = FloatArray(4)
    val farWorld = FloatArray(4)
    Matrix.multiplyMV(nearWorld, 0, invVp, 0, nearClip, 0)
    Matrix.multiplyMV(farWorld, 0, invVp, 0, farClip, 0)
    val nx = nearWorld[0] / nearWorld[3]; val ny = nearWorld[1] / nearWorld[3]; val nz = nearWorld[2] / nearWorld[3]
    val fx = farWorld[0] / farWorld[3]; val fy = farWorld[1] / farWorld[3]; val fz = farWorld[2] / farWorld[3]
    val dx = fx - nx; val dy = fy - ny; val dz = fz - nz
    val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
    return floatArrayOf(nx, ny, nz, dx / len, dy / len, dz / len)
}

@Composable fun ModelViewer(modelFile: File?, modifier: Modifier = Modifier, paintState: PaintUiState? = null) {
    val path = modelFile?.absolutePath
    if (path == null) return
    key(path) {
        var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
        val renderer = remember { MeshGLRenderer() }
        var minDistance by remember { mutableFloatStateOf(1f) }
        var maxDistance by remember { mutableFloatStateOf(1000f) }
        var loading by remember { mutableStateOf(true) }
        var loadError by remember { mutableStateOf<String?>(null) }
        var paintMode by remember { mutableStateOf(false) }
        var enforcerMode by remember { mutableStateOf(true) }
        var brushRadiusMm by remember { mutableFloatStateOf(4f) }
        var paintError by remember { mutableStateOf<String?>(null) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        // A dedicated single-thread dispatcher: TriangleSelector/AABBMesh have no thread-safety
        // of their own (the native side also holds a per-session mutex as defense in depth - see
        // slic3r_engine.cpp - but strokes should be applied in the order they were drawn, not
        // whatever order a multi-threaded dispatcher happens to schedule them in).
        val paintDispatcher = remember { Dispatchers.Default.limitedParallelism(1) }
        val composeScope = rememberCoroutineScope()

        LaunchedEffect(path) {
            loading = true; loadError = null
            try {
                val geometry = MeshLoader.load(path)
                minDistance = geometry.radius * 1.2f
                maxDistance = geometry.radius * 8f
                renderer.pendingMesh = geometry
                renderer.cameraState = CameraOrbit(45f, 25f, geometry.radius * 3f)
                glView?.requestRender()
            } catch (e: Exception) {
                loadError = e.message ?: "Could not load the model preview."
            } finally { loading = false }
        }

        // Lazily opened the first time Paint mode is switched on - not eagerly alongside the
        // mesh load above, since it re-loads the same file natively (AABBMesh/TriangleSelector
        // construction is real, non-trivial work) and most opens of this panel never paint.
        LaunchedEffect(paintMode) {
            if (paintMode && paintState != null && paintState.handle == null) {
                paintError = null
                try {
                    paintState.handle = withContext(paintDispatcher) { NativeEngine.nativeOpenPaintSession(path) }
                } catch (e: Exception) {
                    paintError = e.message ?: "Could not start support painting."
                    paintMode = false
                }
            }
        }

        suspend fun doPaintStroke(position: androidx.compose.ui.geometry.Offset) {
            val handle = paintState?.handle ?: return
            val vp = renderer.lastVpMatrix ?: return
            val w = renderer.viewportWidth; val h = renderer.viewportHeight
            if (w <= 1 || h <= 1) return
            val ray = unprojectRay(vp, w, h, position.x, position.y)
            try {
                NativeEngine.nativePaintStroke(handle, ray[0].toDouble(), ray[1].toDouble(), ray[2].toDouble(),
                    ray[3].toDouble(), ray[4].toDouble(), ray[5].toDouble(), brushRadiusMm.toDouble(), enforcerMode)
                val overlay = NativeEngine.nativeGetPaintedFacets(handle)
                renderer.pendingOverlay = overlay
                if (overlay.isNotEmpty()) paintState?.painted = true
                glView?.requestRender()
            } catch (e: Exception) { paintError = e.message ?: "Paint stroke failed." }
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
        // Deliberately NOT closed when this ModelViewer instance is torn down: the caller
        // (SliceAndPrintPanel) moves from its "customizing" step - where this composable actually
        // lives - to a "slicing" step that unmounts it, but still needs paintState.handle to
        // remain valid so SlicingCoordinator.slice() can pass it to
        // NativeEngine.nativeSlicePaintSession. The session's real lifetime is owned by whoever
        // holds the PaintUiState (the caller), not by however long the 3D view itself stays
        // mounted - see that caller's own DisposableEffect(uri) for where it's actually closed.

        Column(modifier) {
            Box {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(260.dp).testTag("model-viewer")
                        .pointerInput(path, paintMode) {
                            // detectDragGestures's own onDragStart/onDrag callbacks are plain,
                            // non-suspend lambdas with no CoroutineScope of their own -
                            // composeScope (rememberCoroutineScope(), tied to this composable's
                            // own lifecycle) is what actually launches paint strokes onto
                            // paintDispatcher.
                            if (paintMode) {
                                detectDragGestures(
                                    onDragStart = { offset -> composeScope.launch(paintDispatcher) { doPaintStroke(offset) } },
                                ) { change, _ ->
                                    change.consume()
                                    composeScope.launch(paintDispatcher) { doPaintStroke(change.position) }
                                }
                            } else {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val cam = renderer.cameraState
                                    renderer.cameraState = CameraOrbit(
                                        azimuthDeg = cam.azimuthDeg - pan.x * 0.4f,
                                        elevationDeg = (cam.elevationDeg + pan.y * 0.4f).coerceIn(-89f, 89f),
                                        distance = (cam.distance / zoom).coerceIn(minDistance, maxDistance),
                                    )
                                    glView?.requestRender()
                                }
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
                    // Dialog dismissed: GLSurfaceView has no destroy() - onPause() is what actually
                    // stops its render thread and releases the EGL context/surface. Skipping this
                    // leaks the render thread (and transitively the Activity Context used to
                    // construct it), since the thread isn't tied to the Activity lifecycle on its
                    // own - see the plan's architecture review.
                    onRelease = { it.onPause() },
                )
                if (loading) Text("Loading model preview…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("model-viewer-loading"))
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("model-viewer-error")) }
            }
            if (paintState != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!paintMode, { paintMode = false }, label = { Text("Select") }, modifier = Modifier.testTag("paint-mode-select"))
                    FilterChip(paintMode, { paintMode = true }, label = { Text("Paint supports") }, modifier = Modifier.testTag("paint-mode-paint"))
                }
                if (paintMode) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(enforcerMode, { enforcerMode = true }, label = { Text("Add support") }, modifier = Modifier.testTag("paint-enforcer"))
                        FilterChip(!enforcerMode, { enforcerMode = false }, label = { Text("Remove support") }, modifier = Modifier.testTag("paint-blocker"))
                    }
                    Text("Brush size: ${brushRadiusMm.toInt()}mm", style = MaterialTheme.typography.bodySmall)
                    Slider(brushRadiusMm, { brushRadiusMm = it }, valueRange = 1f..15f, modifier = Modifier.testTag("paint-brush-size"))
                    Text("Drag on the model to mark where it needs support - drop it into Select to keep rotating.", style = MaterialTheme.typography.bodySmall)
                }
                paintError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("paint-error")) }
            }
        }
    }
}
