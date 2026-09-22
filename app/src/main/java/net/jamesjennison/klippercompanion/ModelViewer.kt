package net.jamesjennison.klippercompanion

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
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

    private var meshProgram = 0
    private var gridProgram = 0
    private var vbo = 0
    private var gridVbo = 0
    private var vertexCount = 0
    private var gridVertexCount = 0
    // GL-thread-only (never touched from the volatile handoff path): kept so onSurfaceCreated
    // can re-upload after a context recreation (e.g. after onPause()/onResume() across a
    // backgrounding), since the old VBO handle is invalid once the EGL context is torn down but
    // the raw vertex data is still cheap to re-upload from here.
    private var currentGeometry: MeshGeometry? = null
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
        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        vbo = buffers[0]; gridVbo = buffers[1]
        vertexCount = 0; gridVertexCount = 0
        currentGeometry?.let { pendingMesh = it } // force re-upload into the fresh context
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
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

@Composable fun ModelViewer(modelFile: File?, modifier: Modifier = Modifier) {
    val path = modelFile?.absolutePath
    if (path == null) return
    key(path) {
        var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
        val renderer = remember { MeshGLRenderer() }
        var minDistance by remember { mutableFloatStateOf(1f) }
        var maxDistance by remember { mutableFloatStateOf(1000f) }
        var loading by remember { mutableStateOf(true) }
        var loadError by remember { mutableStateOf<String?>(null) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle

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

        Box(modifier) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(260.dp).testTag("model-viewer")
                    .pointerInput(path) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val cam = renderer.cameraState
                            renderer.cameraState = CameraOrbit(
                                azimuthDeg = cam.azimuthDeg - pan.x * 0.4f,
                                elevationDeg = (cam.elevationDeg + pan.y * 0.4f).coerceIn(-89f, 89f),
                                distance = (cam.distance / zoom).coerceIn(minDistance, maxDistance),
                            )
                            glView?.requestRender()
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
    }
}
