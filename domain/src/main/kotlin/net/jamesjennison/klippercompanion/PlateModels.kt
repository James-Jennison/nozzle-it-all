package net.jamesjennison.klippercompanion

// Phase 9S: plain data shared by the editor, the slicer bridge and tests (split out of the Android view files).

data class MeshGeometry(
    val vertexData: FloatArray, val triangleCount: Int, val center: FloatArray, val radius: Float,
    val origin: FloatArray, val minZ: Float,
    val minX: Float, val maxX: Float, val minY: Float, val maxY: Float, val maxZ: Float,
)

// Extra geometry drawn over an object in its own model matrix (painted regions, modifier/blocker outlines):
// [vertices] are position + normal (6 floats each), in the same frame as the object's mesh; [lines] draws
// GL_LINES instead of triangles.
class OverlayGroup(val color: FloatArray, val vertices: FloatArray, val lines: Boolean = false)

// WO-15 part E: a real placement on the bed - move (XY), rotate (about Z only - a turntable
// gesture; arbitrary/place-on-face rotation isn't supported yet), and uniform scale. The same
// numbers drive both the live GL preview (as a model matrix, ModelViewer.kt) and the actual
// libslic3r instance transform applied at slice time (engine::ModelTransform,
// slic3r_engine.cpp) - what's on screen is what gets sliced, not two independent
// representations. No build-volume bounds/collision checking yet - see WORK_ORDER.md's WO-15
// entry for what's intentionally still open.
data class ModelTransform(
    val offsetXMm: Float = 0f,
    val offsetYMm: Float = 0f,
    val rotationZDeg: Float = 0f,
    val scale: Float = 1f,
)
