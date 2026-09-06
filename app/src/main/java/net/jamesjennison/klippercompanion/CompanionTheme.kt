package net.jamesjennison.klippercompanion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable fun CompanionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF81E6CF), onPrimary = Color(0xFF00382E),
            primaryContainer = Color(0xFF244B45), onPrimaryContainer = Color(0xFFB7F5E6),
            secondary = Color(0xFFA8B5BD), onSecondary = Color(0xFF19242D),
            secondaryContainer = Color(0xFF293E43), onSecondaryContainer = Color(0xFF81E6CF),
            background = Color(0xFF10171D), onBackground = Color(0xFFE6EEF2),
            surface = Color(0xFF19242D), onSurface = Color(0xFFE6EEF2),
            surfaceVariant = Color(0xFF293640), onSurfaceVariant = Color(0xFFA8B5BD),
            surfaceContainerLowest = Color(0xFF0C1217), surfaceContainerLow = Color(0xFF152029),
            surfaceContainer = Color(0xFF19242D), surfaceContainerHigh = Color(0xFF22303A),
            surfaceContainerHighest = Color(0xFF293640),
            outline = Color(0xFF71818B), outlineVariant = Color(0xFF364650),
            error = Color(0xFFFFB4AB), errorContainer = Color(0xFF5E2422),
        ),
        shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp)),
        content = content,
    )
}

enum class CompanionSymbol { DASHBOARD, CONTROL, FILES, PRINTER, CAMERA, EXPAND, CLOSE, NOZZLE, BED }

/** Original outlined symbols; parent controls provide accessible text labels. */
@Composable fun CompanionIcon(symbol: CompanionSymbol, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier.size(24.dp)) {
        val u = size.minDimension / 24f
        val stroke = Stroke(1.7f * u, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x*u,y*u), Offset(x2*u,y2*u), strokeWidth=1.7f*u, cap=StrokeCap.Round)
        fun box(x: Float,y: Float,w: Float,h: Float) = drawRect(color,Offset(x*u,y*u),Size(w*u,h*u),style=stroke)
        when(symbol) {
            CompanionSymbol.DASHBOARD -> { box(3f,3f,7f,7f);box(14f,3f,7f,7f);box(3f,14f,7f,7f);box(14f,14f,7f,7f) }
            CompanionSymbol.CONTROL -> { for((x,y) in listOf(5f to 8f,12f to 16f,19f to 8f)) { line(x,3f,x,y-3);line(x,y+3,x,21f);drawCircle(color,3f*u,Offset(x*u,y*u),style=stroke) } }
            CompanionSymbol.FILES -> { val p=Path().apply { moveTo(3*u,6*u);lineTo(10*u,6*u);lineTo(12*u,9*u);lineTo(21*u,9*u);lineTo(21*u,20*u);lineTo(3*u,20*u);close() };drawPath(p,color,style=stroke) }
            CompanionSymbol.PRINTER -> { box(4f,4f,16f,17f);line(8f,4f,8f,12f);line(16f,4f,16f,12f);line(8f,12f,16f,12f);line(12f,12f,12f,15f);line(7f,18f,17f,18f) }
            CompanionSymbol.CAMERA -> { box(3f,6f,18f,14f);drawCircle(color,4f*u,Offset(12*u,13*u),style=stroke);line(8f,3f,16f,3f) }
            CompanionSymbol.EXPAND -> { line(4f,9f,4f,4f);line(4f,4f,9f,4f);line(15f,4f,20f,4f);line(20f,4f,20f,9f);line(20f,15f,20f,20f);line(20f,20f,15f,20f);line(9f,20f,4f,20f);line(4f,20f,4f,15f) }
            CompanionSymbol.CLOSE -> { line(5f,5f,19f,19f);line(19f,5f,5f,19f) }
            CompanionSymbol.NOZZLE -> { box(6f,4f,12f,7f);line(6f,11f,10f,17f);line(18f,11f,14f,17f);line(10f,17f,14f,17f);line(12f,20f,12f,21f) }
            CompanionSymbol.BED -> { line(3f,17f,21f,17f);line(5f,17f,5f,21f);line(19f,17f,19f,21f);for(x in listOf(7f,12f,17f)) { line(x,4f,x-1,8f);line(x-1,8f,x,12f) } }
        }
    }
}
