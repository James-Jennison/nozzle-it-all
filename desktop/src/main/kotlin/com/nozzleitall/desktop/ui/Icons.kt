package com.nozzleitall.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Nozzle It All's own line icons, drawn on a 24-unit grid with a 1.75-unit rounded stroke. Shapes borrow from the
 * Infill mark (rhombus lattice, nozzle tip) so the family is recognisable without copying any icon set.
 */
enum class NzIcon { FLEET, PROJECTS, PREPARE, MONITOR, MATERIALS, SPECTRUM, WORKSPACE, SETTINGS, CAMERA, ADD, PLAY, PAUSE, STOP, HEAT, HOME, MOVE,
    CHECK, ALERT, QUESTION, OFFLINE, NETWORK, PRIVATE_NETWORK, CLOUD, FOLDER, IMPORT, SLICE, SEND, ARROW_RIGHT, CLOSE, TOOLHEAD, LAYERS, EXPORT }

@Composable
fun Icon(icon: NzIcon, tint: Color, size: Dp = 20.dp, description: String? = null, modifier: Modifier = Modifier) {
    val m = if (description != null) modifier.semantics { contentDescription = description } else modifier
    Canvas(m.size(size)) { drawIcon(icon, tint) }
}

fun DrawScope.drawIcon(icon: NzIcon, tint: Color) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 1.75f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(vararg pts: Float, close: Boolean = false) = Path().apply {
        moveTo(pts[0] * u, pts[1] * u); var i = 2
        while (i < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
        if (close) close()
    }
    fun line(vararg pts: Float, close: Boolean = false) = drawPath(p(*pts, close = close), tint, style = stroke)
    fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) = if (fill) drawCircle(tint, r * u, Offset(cx * u, cy * u)) else drawCircle(tint, r * u, Offset(cx * u, cy * u), style = stroke)
    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) =
        drawRoundRect(tint, Offset(x * u, y * u), Size(w * u, h * u), androidx.compose.ui.geometry.CornerRadius(r * u), style = stroke)
    when (icon) {
        // A printer seen from the front, with the nozzle over the plate.
        NzIcon.FLEET -> { rect(3f, 4f, 18f, 16f); line(3f, 8f, 21f, 8f); line(10f, 8f, 12f, 12f, 14f, 8f); line(6f, 17f, 18f, 17f) }
        NzIcon.PROJECTS -> { line(3f, 7f, 9f, 7f, 11f, 9f, 21f, 9f, 21f, 19f, 3f, 19f, close = true); line(3f, 7f, 3f, 5f, 8f, 5f) }
        // The infill rhombus lattice.
        NzIcon.PREPARE -> { line(12f, 3f, 21f, 12f, 12f, 21f, 3f, 12f, close = true); line(7.5f, 7.5f, 16.5f, 16.5f); line(16.5f, 7.5f, 7.5f, 16.5f) }
        NzIcon.MONITOR -> { rect(3f, 5f, 18f, 12f); line(8f, 21f, 16f, 21f); line(6f, 13f, 9f, 10f, 12f, 12f, 17f, 8f) }
        NzIcon.MATERIALS -> { circle(12f, 12f, 8.5f); circle(12f, 12f, 3f); line(12f, 3.5f, 12f, 9f) }
        NzIcon.SPECTRUM -> { circle(9f, 10f, 5f); circle(15f, 10f, 5f); circle(12f, 15f, 5f) }
        NzIcon.WORKSPACE -> { rect(3f, 3f, 18f, 18f); line(3f, 9f, 21f, 9f); line(9f, 9f, 9f, 21f); line(13f, 14f, 17f, 14f); line(13f, 17f, 17f, 17f) }
        NzIcon.SETTINGS -> { circle(12f, 12f, 3f); for (a in 0 until 8) { val r = Math.toRadians(a * 45.0); line(12f + 6f * Math.cos(r).toFloat(), 12f + 6f * Math.sin(r).toFloat(), 12f + 8.5f * Math.cos(r).toFloat(), 12f + 8.5f * Math.sin(r).toFloat()) } }
        NzIcon.CAMERA -> { rect(3f, 7f, 18f, 12f); circle(12f, 13f, 3.5f); line(8f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 16f, 7f) }
        NzIcon.ADD -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        NzIcon.PLAY -> line(7f, 5f, 19f, 12f, 7f, 19f, close = true)
        NzIcon.PAUSE -> { line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f) }
        NzIcon.STOP -> rect(6f, 6f, 12f, 12f)
        NzIcon.HEAT -> { line(12f, 3f, 16f, 9f, 15f, 14f, 12f, 21f, 9f, 14f, 8f, 9f, close = true); line(12f, 13f, 12f, 17f) }
        NzIcon.HOME -> { line(4f, 11f, 12f, 4f, 20f, 11f); line(6f, 10f, 6f, 20f, 18f, 20f, 18f, 10f) }
        NzIcon.MOVE -> { line(12f, 3f, 12f, 21f); line(3f, 12f, 21f, 12f); line(9f, 6f, 12f, 3f, 15f, 6f); line(9f, 18f, 12f, 21f, 15f, 18f); line(6f, 9f, 3f, 12f, 6f, 15f); line(18f, 9f, 21f, 12f, 18f, 15f) }
        NzIcon.CHECK -> line(5f, 12.5f, 10f, 17.5f, 19f, 7f)
        NzIcon.ALERT -> { line(12f, 3f, 21.5f, 20f, 2.5f, 20f, close = true); line(12f, 9f, 12f, 14f); circle(12f, 17f, 0.9f, fill = true) }
        NzIcon.QUESTION -> { circle(12f, 12f, 9f); line(9.5f, 9.5f, 10.5f, 7.5f, 13.5f, 7.5f, 14.5f, 9.5f, 12f, 12f, 12f, 13.5f); circle(12f, 16.8f, 0.9f, fill = true) }
        NzIcon.OFFLINE -> { circle(12f, 12f, 9f); line(5.5f, 5.5f, 18.5f, 18.5f) }
        NzIcon.NETWORK -> { rect(9f, 3f, 6f, 5f, 1f); rect(3f, 16f, 6f, 5f, 1f); rect(15f, 16f, 6f, 5f, 1f); line(12f, 8f, 12f, 12f); line(6f, 16f, 6f, 12f, 18f, 12f, 18f, 16f) }
        NzIcon.PRIVATE_NETWORK -> { rect(5f, 10f, 14f, 11f); line(8f, 10f, 8f, 7f, 10f, 4f, 14f, 4f, 16f, 7f, 16f, 10f); circle(12f, 15.5f, 1.2f, fill = true) }
        NzIcon.CLOUD -> line(7f, 18f, 4.5f, 16f, 4.5f, 12.5f, 7.5f, 10.5f, 9f, 7f, 13f, 5.5f, 16.5f, 7.5f, 18f, 10.5f, 20f, 12f, 20f, 16f, 17.5f, 18f, close = true)
        NzIcon.FOLDER -> line(3f, 6f, 9f, 6f, 11f, 8f, 21f, 8f, 21f, 19f, 3f, 19f, close = true)
        NzIcon.IMPORT -> { line(12f, 3f, 12f, 15f); line(7f, 10f, 12f, 15f, 17f, 10f); line(4f, 17f, 4f, 21f, 20f, 21f, 20f, 17f) }
        NzIcon.EXPORT -> { line(12f, 15f, 12f, 3f); line(7f, 8f, 12f, 3f, 17f, 8f); line(4f, 17f, 4f, 21f, 20f, 21f, 20f, 17f) }
        NzIcon.SLICE -> { line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f); line(16f, 3f, 8f, 21f) }
        NzIcon.SEND -> { line(3f, 11f, 21f, 3f, 13f, 21f, 11f, 13f, close = true); line(11f, 13f, 21f, 3f) }
        NzIcon.ARROW_RIGHT -> { line(4f, 12f, 20f, 12f); line(14f, 6f, 20f, 12f, 14f, 18f) }
        NzIcon.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        // The nozzle from the Infill mark: a block narrowing to a tip.
        NzIcon.TOOLHEAD -> { line(6f, 3f, 18f, 3f, 18f, 11f, 14f, 15f, 14f, 18f, 12f, 21f, 10f, 18f, 10f, 15f, 6f, 11f, close = true) }
        NzIcon.LAYERS -> { line(12f, 4f, 21f, 9f, 12f, 14f, 3f, 9f, close = true); line(3f, 13f, 12f, 18f, 21f, 13f); line(3f, 17f, 12f, 22f, 21f, 17f) }
    }
}
