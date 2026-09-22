package net.jamesjennison.klippercompanion

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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

/**
 * The post-slice half of the "visual, on-device, in-app slicer" (owner request, 2026-09-22): a
 * real 3D view of the actual sliced toolpath, not the flat 2D top-down view LayerPreview.kt
 * already provides (that one stays in place for the Bambu-share/live-tracking use cases it was
 * built for - this is a new, additional view specifically for the slice-review step). Reuses
 * GcodePreview.parse()'s existing Toolpath/ToolpathSegment model verbatim - only the rendering is
 * new. Shares ModelViewer.kt's CameraOrbit data class and GLSupport.kt's shader/buffer helpers.
 */

private const val LINE_VERTEX_SHADER = """#version 300 es
uniform mat4 uMVP;
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aColor;
out vec3 vColor;
void main() {
    vColor = aColor;
    gl_Position = uMVP * vec4(aPosition, 1.0);
}
"""

private const val LINE_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in vec3 vColor;
out vec4 fragColor;
void main() { fragColor = vec4(vColor, 1.0); }
"""

// This app's print-orange accent (matches ModelViewer.kt/thumbnail_render.cpp) for real
// extrusion moves; a muted gray for travel moves - the same 2-color distinction the existing 2D
// LayerPreview already draws (solid vs. dashed there; solid vs. dim here).
private val EXTRUSION_COLOR = floatArrayOf(242f / 255f, 117f / 255f, 78f / 255f)
private val TRAVEL_COLOR = floatArrayOf(0.45f, 0.47f, 0.49f)

private fun buildLineBuffer(toolpath: Toolpath, uptoLayer: Int, showTravel: Boolean): FloatArray {
    val lines = ArrayList<Float>()
    fun append(segments: List<ToolpathSegment>, color: FloatArray) {
        for (s in segments) {
            if (s.layer > uptoLayer) continue
            val z = toolpath.heights.getOrElse(s.layer) { 0f }
            lines.add(s.x1); lines.add(s.y1); lines.add(z); lines.add(color[0]); lines.add(color[1]); lines.add(color[2])
            lines.add(s.x2); lines.add(s.y2); lines.add(z); lines.add(color[0]); lines.add(color[1]); lines.add(color[2])
        }
    }
    append(toolpath.segments, EXTRUSION_COLOR)
    if (showTravel) append(toolpath.travels, TRAVEL_COLOR)
    return lines.toFloatArray()
}

private fun toolpathBounds(toolpath: Toolpath): Pair<FloatArray, Float> {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (s in toolpath.segments) {
        minX = minOf(minX, s.x1, s.x2); maxX = maxOf(maxX, s.x1, s.x2)
        minY = minOf(minY, s.y1, s.y2); maxY = maxOf(maxY, s.y1, s.y2)
    }
    val maxZ = toolpath.heights.maxOrNull() ?: 1f
    val center = floatArrayOf((minX + maxX) / 2f, (minY + maxY) / 2f, maxZ / 2f)
    val dx = maxX - minX; val dy = maxY - minY
    val radius = (kotlin.math.sqrt(dx * dx + dy * dy + maxZ * maxZ) / 2f).coerceAtLeast(1f)
    return center to radius
}

/** Same cross-thread contract as MeshGLRenderer (ModelViewer.kt) - see that file's own comment. */
class ToolpathGLRenderer : GLSurfaceView.Renderer {
    @Volatile var pendingLines: FloatArray? = null
    @Volatile var cameraState: CameraOrbit = CameraOrbit(45f, 25f, 100f)
    var center = floatArrayOf(0f, 0f, 0f)

    private var program = 0
    private var vbo = 0
    private var vertexCount = 0
    private var currentLines: FloatArray? = null
    private var width = 1
    private var height = 1
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES30.glClearColor(0.06f, 0.06f, 0.07f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        program = buildGLProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]
        vertexCount = 0
        currentLines?.let { pendingLines = it }
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        pendingLines?.let { data ->
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val buffer = directFloatBuffer(data)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES30.GL_STATIC_DRAW)
            vertexCount = data.size / 6
            currentLines = data
            pendingLines = null
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (vertexCount == 0) return

        val cam = cameraState
        val azRad = Math.toRadians(cam.azimuthDeg.toDouble())
        val elRad = Math.toRadians(cam.elevationDeg.toDouble())
        val horizontal = cam.distance * kotlin.math.cos(elRad)
        val eyeX = center[0] + (horizontal * kotlin.math.cos(azRad)).toFloat()
        val eyeY = center[1] + (horizontal * kotlin.math.sin(azRad)).toFloat()
        val eyeZ = center[2] + (cam.distance * kotlin.math.sin(elRad)).toFloat()
        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, center[0], center[1], center[2], 0f, 0f, 1f)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 45f, aspect, (cam.distance * 0.02f).coerceAtLeast(0.1f), cam.distance * 10f)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMVP"), 1, false, vpMatrix, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, vertexCount)
    }
}

@Composable fun SlicedPreview(toolpath: Toolpath, modifier: Modifier = Modifier) {
    var glView by remember(toolpath) { mutableStateOf<GLSurfaceView?>(null) }
    val renderer = remember(toolpath) { ToolpathGLRenderer() }
    var layer by remember(toolpath) { mutableIntStateOf(toolpath.heights.lastIndex.coerceAtLeast(0)) }
    var showTravel by remember(toolpath) { mutableStateOf(false) }
    var minDistance by remember(toolpath) { mutableFloatStateOf(1f) }
    var maxDistance by remember(toolpath) { mutableFloatStateOf(1000f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(toolpath) {
        val (center, radius) = toolpathBounds(toolpath)
        renderer.center = center
        minDistance = radius * 1.2f; maxDistance = radius * 8f
        renderer.cameraState = CameraOrbit(45f, 35f, radius * 3f)
    }
    LaunchedEffect(toolpath, layer, showTravel) {
        renderer.pendingLines = buildLineBuffer(toolpath, layer, showTravel)
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
        Text("Layer ${layer + 1} / ${toolpath.heights.size} · Z ${toolpath.heights.getOrElse(layer) { 0f }} mm", style = MaterialTheme.typography.bodySmall)
        if (toolpath.heights.size > 1) Slider(layer.toFloat(), { layer = it.toInt().coerceIn(toolpath.heights.indices) }, valueRange = 0f..toolpath.heights.lastIndex.toFloat(), modifier = Modifier.testTag("sliced-preview-layer"))
        Row { androidx.compose.material3.FilterChip(showTravel, { showTravel = !showTravel }, label = { Text("Travel paths") }, modifier = Modifier.testTag("sliced-preview-travel")) }
        Box {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(260.dp).testTag("sliced-preview")
                    .pointerInput(toolpath) {
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
                onRelease = { it.onPause() },
            )
        }
    }
}
