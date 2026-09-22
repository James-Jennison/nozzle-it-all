package net.jamesjennison.klippercompanion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

class ModelTransformUiState {
    var transform by mutableStateOf(ModelTransform())
    // Real build-volume bounds check result (ModelViewer.kt's computeOutOfBounds), kept here -
    // not just inside ModelViewer - so the caller (SliceAndPrintPanel) can gate the Slice action
    // on it without ModelViewer needing to be mounted; false (fits) until a real bed shape and
    // geometry are both known, so a printer with no bundled bed data never falsely blocks.
    var outOfBounds by mutableStateOf(false)
}
