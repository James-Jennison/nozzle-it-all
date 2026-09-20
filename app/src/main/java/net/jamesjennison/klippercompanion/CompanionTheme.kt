@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
package net.jamesjennison.klippercompanion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// "Kiln": a duotone (ember for heat, teal for the app's own accent) dark theme distinct from
// Helix's flat single-cyan "Cockpit" palette - see the design concept artifact for the reasoning.
// Space Grotesk (display/headline) + IBM Plex Sans (body) + IBM Plex Mono (numeric readouts,
// already used for temperatures/percentages elsewhere) replace the system default faces; all
// three are OFL-licensed, see THIRD_PARTY_NOTICES.md.
private val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)
private val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.width(100f), FontVariation.weight(400))),
    Font(R.font.ibm_plex_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.width(100f), FontVariation.weight(500))),
    Font(R.font.ibm_plex_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.width(100f), FontVariation.weight(600))),
)
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

@Composable fun CompanionTheme(dark: Boolean = true, accent: String = "Mint", content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = (if (dark) darkColorScheme(
            primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF04201B),
            primaryContainer = Color(0xFF193632), onPrimaryContainer = Color(0xFFB7F5E6),
            secondary = Color(0xFF9AA6AC), onSecondary = Color(0xFF161B1E),
            secondaryContainer = Color(0xFF23272C), onSecondaryContainer = Color(0xFF5EEAD4),
            tertiary = Color(0xFFFB923C), onTertiary = Color(0xFF1A0F06), // ember: heat/warning accent
            tertiaryContainer = Color(0xFF2B1F17), onTertiaryContainer = Color(0xFFFDBA8C),
            background = Color(0xFF0E1113), onBackground = Color(0xFFEDF2F4),
            surface = Color(0xFF14161A), onSurface = Color(0xFFEDF2F4),
            surfaceVariant = Color(0xFF1E2327), onSurfaceVariant = Color(0xFF9AA5AA),
            surfaceContainerLowest = Color(0xFF0A0C0D), surfaceContainerLow = Color(0xFF121517),
            surfaceContainer = Color(0xFF14161A), surfaceContainerHigh = Color(0xFF1C2024),
            surfaceContainerHighest = Color(0xFF262B2F),
            outline = Color(0xFF5C666B), outlineVariant = Color(0xFF23272C),
            error = Color(0xFFFB7185), errorContainer = Color(0xFF3A1A1F),
        ) else lightColorScheme(
            background=Color(0xFFEAF0F4), surface=Color(0xFFFFFFFF),
            onBackground=Color(0xFF19242D), onSurface=Color(0xFF19242D),
            surfaceVariant=Color(0xFFDDE5EA), onSurfaceVariant=Color(0xFF3E4D57)
        )).let { scheme ->
            val primary = when(accent) {
                "Blue" -> if(dark) Color(0xFFA8C8FF) else Color(0xFF245B9D)
                "Lavender" -> if(dark) Color(0xFFD3BFFF) else Color(0xFF69429A)
                else -> if(dark) Color(0xFF5EEAD4) else Color(0xFF006B58)
            }
            scheme.copy(primary=primary, onPrimary=if(dark) Color(0xFF0E1113) else Color.White,
                primaryContainer=primary.copy(alpha=1f), onPrimaryContainer=if(dark) Color(0xFF0E1113) else Color.White,
                secondaryContainer=if(dark) Color(0xFF23272C) else Color(0xFFDDE5EA), onSecondaryContainer=primary)
        },
        typography = remember { kilnTypography() },
        shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp)),
        content = content,
    )
}

/**
 * The gradient/bordered-card treatment MainActivity's dashboard hero card and PrinterTiles'
 * printer tile each already use for "something live/active is here" - extracted here so panels
 * with their own visual content (a camera/screen mirror, a 3D view, a video player, an imminent
 * print confirmation) get the same look instead of floating bare inside a plain AlertDialog.
 * [accent] is the hero card's own `printing` flag generalized: on for content that represents an
 * active/imminent state, off for a plain framed surface.
 */
@Composable
fun KilnFrame(accent: Boolean = false, shape: Shape = RoundedCornerShape(20.dp), content: @Composable BoxScope.() -> Unit) {
    val background = if (accent) Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), MaterialTheme.colorScheme.surface))
        else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))
    val border = if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    Box(Modifier.clip(shape).background(background).border(1.dp, border, shape), content = content)
}

private fun kilnTypography(): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = SpaceGrotesk),
        displayMedium = base.displayMedium.copy(fontFamily = SpaceGrotesk),
        displaySmall = base.displaySmall.copy(fontFamily = SpaceGrotesk),
        headlineLarge = base.headlineLarge.copy(fontFamily = SpaceGrotesk),
        headlineMedium = base.headlineMedium.copy(fontFamily = SpaceGrotesk),
        headlineSmall = base.headlineSmall.copy(fontFamily = SpaceGrotesk),
        titleLarge = base.titleLarge.copy(fontFamily = SpaceGrotesk),
        titleMedium = base.titleMedium.copy(fontFamily = SpaceGrotesk),
        titleSmall = base.titleSmall.copy(fontFamily = PlexSans),
        bodyLarge = base.bodyLarge.copy(fontFamily = PlexSans),
        bodyMedium = base.bodyMedium.copy(fontFamily = PlexSans),
        bodySmall = base.bodySmall.copy(fontFamily = PlexSans),
        labelLarge = base.labelLarge.copy(fontFamily = PlexSans),
        labelMedium = base.labelMedium.copy(fontFamily = PlexSans),
        labelSmall = base.labelSmall.copy(fontFamily = PlexSans),
    )
}

// HOME/CONTROL/FILES redrawn and SLICE/SETTINGS added for the 5-tab nav (see MainActivity's
// bottom nav); PRINTER/CAMERA/EXPAND/CLOSE/NOZZLE/BED are unchanged, used outside the nav bar.
enum class CompanionSymbol { DASHBOARD, CONTROL, FILES, PRINTER, CAMERA, EXPAND, CLOSE, NOZZLE, BED, SLICE, SETTINGS }

/** Original outlined symbols; parent controls provide accessible text labels. */
@Composable fun CompanionIcon(symbol: CompanionSymbol, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier.size(24.dp)) {
        val u = size.minDimension / 24f
        val stroke = Stroke(1.7f * u, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x*u,y*u), Offset(x2*u,y2*u), strokeWidth=1.7f*u, cap=StrokeCap.Round)
        fun box(x: Float,y: Float,w: Float,h: Float) = drawRect(color,Offset(x*u,y*u),Size(w*u,h*u),style=stroke)
        when(symbol) {
            // A build-up layer stack: three bars narrowing toward the top, not the original
            // mockup's generic 2x2 grid.
            CompanionSymbol.DASHBOARD -> { line(4f,19f,20f,19f);line(7f,13f,17f,13f);line(10f,7f,14f,7f) }
            // A nozzle taper feeding a bead, not the original mockup's slider-fader icon.
            CompanionSymbol.CONTROL -> {
                val p=Path().apply { moveTo(7*u,4*u);lineTo(17*u,4*u);lineTo(14.5f*u,11*u);lineTo(9.5f*u,11*u);close() }
                drawPath(p,color,style=stroke);line(12f,11f,12f,16f);line(10f,20f,14f,20f)
            }
            CompanionSymbol.FILES -> {
                val p=Path().apply { moveTo(6*u,3*u);lineTo(14*u,3*u);lineTo(18*u,7*u);lineTo(18*u,17*u);lineTo(6*u,17*u);close() }
                drawPath(p,color,style=stroke)
                line(14f,3f,14f,7f);line(14f,7f,18f,7f)
                // toolpath squiggle
                val zigzag=Path().apply { moveTo(8.5f*u,12*u);lineTo(10.5f*u,14*u);lineTo(12.5f*u,12*u);lineTo(14.5f*u,14*u) }
                drawPath(zigzag,color,style=stroke)
            }
            CompanionSymbol.PRINTER -> { box(4f,4f,16f,17f);line(8f,4f,8f,12f);line(16f,4f,16f,12f);line(8f,12f,16f,12f);line(12f,12f,12f,15f);line(7f,18f,17f,18f) }
            CompanionSymbol.CAMERA -> { box(3f,6f,18f,14f);drawCircle(color,4f*u,Offset(12*u,13*u),style=stroke);line(8f,3f,16f,3f) }
            CompanionSymbol.EXPAND -> { line(4f,9f,4f,4f);line(4f,4f,9f,4f);line(15f,4f,20f,4f);line(20f,4f,20f,9f);line(20f,15f,20f,20f);line(20f,20f,15f,20f);line(9f,20f,4f,20f);line(4f,20f,4f,15f) }
            CompanionSymbol.CLOSE -> { line(5f,5f,19f,19f);line(19f,5f,5f,19f) }
            CompanionSymbol.NOZZLE -> { box(6f,4f,12f,7f);line(6f,11f,10f,17f);line(18f,11f,14f,17f);line(10f,17f,14f,17f);line(12f,20f,12f,21f) }
            CompanionSymbol.BED -> { line(3f,17f,21f,17f);line(5f,17f,5f,21f);line(19f,17f,19f,21f);for(x in listOf(7f,12f,17f)) { line(x,4f,x-1,8f);line(x-1,8f,x,12f) } }
            // A block with cut lines through it: slicing a model into printable layers.
            CompanionSymbol.SLICE -> { box(6f,4f,12f,16f);line(6f,9f,18f,9f);line(6f,14f,18f,14f) }
            // A dial: circle with four cardinal ticks.
            CompanionSymbol.SETTINGS -> {
                drawCircle(color,8f*u,Offset(12*u,12*u),style=stroke)
                line(12f,1f,12f,3f);line(12f,21f,12f,23f);line(1f,12f,3f,12f);line(21f,12f,23f,12f)
            }
        }
    }
}
