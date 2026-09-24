package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File

// WO-13 follow-up (owner request, 2026-09-22): "a visual, on-device, in-app slicer", PrusaSlicer's
// EasyPrint mode as the reference point. Proves the new mesh-preview JNI function (the pre-slice
// 3D viewer's data source, ModelViewer.kt) works against the real engine and real device, not
// just that it compiles.
@RunWith(AndroidJUnit4::class)
class MeshPreviewDeviceTest {
    private fun cube(): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "mesh_preview_cube.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        return input
    }
    @Test fun realCubeProducesAWellFormedInterleavedVertexBuffer() {
        val raw = NativeEngine.nativeLoadMeshPreview(cube().absolutePath)
        assertTrue("expected at least the 3-float transform-pivot header", raw.size > 3)
        // First 3 floats are the real transform pivot (WO-15 part E) - the vertex buffer proper
        // starts after that header, see engine::load_mesh_preview.
        val data = raw.copyOfRange(3, raw.size)
        assertTrue("expected a non-empty buffer", data.isNotEmpty())
        assertEquals("expected a whole number of (pos+normal) vertices", 0, data.size % 6)
        assertEquals("expected a whole number of triangles (3 vertices each)", 0, (data.size / 6) % 3)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var i = 0
        while (i < data.size) { minX = minOf(minX, data[i]); maxX = maxOf(maxX, data[i]); i += 6 }
        assertTrue("expected real, non-degenerate geometry (some X extent)", maxX - minX > 0.5f)
        // Every normal should already be unit length (or zero for a degenerate triangle) -
        // confirms the native side is really computing face normals, not leaving them zeroed.
        var sawNonzeroNormal = false
        i = 0
        while (i < data.size) {
            val nx = data[i + 3]; val ny = data[i + 4]; val nz = data[i + 5]
            val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            assertTrue("normal should be unit length or zero, was $len", len < 1e-4f || kotlin.math.abs(len - 1f) < 1e-3f)
            if (len > 1e-4f) sawNonzeroNormal = true
            i += 6
        }
        assertTrue("expected at least one real face normal", sawNonzeroNormal)
    }
    @Test fun realCubeMeshLoaderComputesASaneBoundingSphere() = runBlocking {
        val geometry = MeshLoader.load(cube().absolutePath)
        assertTrue("expected real triangles", geometry.triangleCount > 0)
        assertTrue("expected a real, positive bounding radius", geometry.radius > 0.5f)
    }
    @Test fun missingFileFailsWithARealException() {
        assertThrows(RuntimeException::class.java) {
            NativeEngine.nativeLoadMeshPreview("/does/not/exist.stl")
        }
    }

    // A ~1.4M-triangle model crashed the app (OutOfMemoryError building the preview: ~100 MB of vertex floats, copied twice). The preview
    // is now bounded; slicing still reads the full file.
    @Test fun aHugeModelPreviewStaysWithinTheMemoryBudgetAndKeepsItsShape() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "huge_sphere.stl")
        val cols = 1200; val rows = 600; val r = 20.0
        fun p(i: Int, j: Int): FloatArray { val th = Math.PI * j / rows; val ph = 2 * Math.PI * (i % cols) / cols; return floatArrayOf((r * Math.sin(th) * Math.cos(ph)).toFloat(), (r * Math.sin(th) * Math.sin(ph)).toFloat(), (r * Math.cos(th) + 20).toFloat()) }
        try {
            java.io.DataOutputStream(java.io.BufferedOutputStream(file.outputStream(), 1 shl 20)).use { out ->
                fun le(v: Int) { out.writeInt(Integer.reverseBytes(v)) }
                fun lef(v: Float) = le(java.lang.Float.floatToRawIntBits(v))
                out.write(ByteArray(80)); le(cols * rows * 2)
                for (j in 0 until rows) for (i in 0 until cols) {
                    val a = p(i, j); val b = p(i + 1, j); val c = p(i, j + 1); val d = p(i + 1, j + 1)
                    for (tri in listOf(arrayOf(a, b, c), arrayOf(b, d, c))) { repeat(3) { lef(0f) }; tri.forEach { v -> v.forEach(::lef) }; out.writeShort(0) }
                }
            }
            assertTrue("test model is really big", file.length() > 60_000_000L)
            System.gc()
            val geometry = runBlocking { MeshLoader.load(file.absolutePath) }
            assertTrue("triangles ${geometry.triangleCount} within the preview budget", geometry.triangleCount in 10_000..300_000)
            assertTrue("preview floats ${geometry.vertexData.size * 4L / 1_000_000} MB", geometry.vertexData.size * 4L < 40_000_000L)
            assertEquals("shape preserved: width", 2 * r, (geometry.maxX - geometry.minX).toDouble(), 1.5)
            assertEquals("shape preserved: height", 2 * r, (geometry.maxZ - geometry.minZ).toDouble(), 1.5)
        } finally { file.delete() }
    }
}
