package net.jamesjennison.klippercompanion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue


class ModelTransformUiState {
    var transform by mutableStateOf(ModelTransform())
    // Real build-volume bounds check result (ModelViewer.kt's computeOutOfBounds), kept here -
    // not just inside ModelViewer - so the caller (SliceAndPrintPanel) can gate the Slice action
    // on it without ModelViewer needing to be mounted; false (fits) until a real bed shape and
    // geometry are both known, so a printer with no bundled bed data never falsely blocks.
    var outOfBounds by mutableStateOf(false)
}
