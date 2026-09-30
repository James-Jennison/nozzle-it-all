package com.nozzleitall.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.em
import com.nozzleitall.design.NozzlePalette
import com.nozzleitall.design.NozzleStatusColors
import com.nozzleitall.design.NozzleTokens

object Fonts {
    val display = FontFamily(Font("fonts/SpaceGrotesk.ttf", FontWeight.Normal), Font("fonts/SpaceGrotesk.ttf", FontWeight.SemiBold), Font("fonts/SpaceGrotesk.ttf", FontWeight.Bold))
    val body = FontFamily(Font("fonts/IBMPlexSans.ttf", FontWeight.Normal), Font("fonts/IBMPlexSans.ttf", FontWeight.Medium), Font("fonts/IBMPlexSans.ttf", FontWeight.SemiBold))
    val mono = FontFamily(Font("fonts/IBMPlexMono-Regular.ttf", FontWeight.Normal), Font("fonts/IBMPlexMono-Medium.ttf", FontWeight.Medium))
    fun of(key: String) = when (key) { "display" -> display; "mono" -> mono; else -> body }
}

/** Text styles generated from the shared type scale, scaled by the user's text-size preference. */
class NozzleType(private val scale: Float) {
    private fun style(t: NozzleTokens.TypeStyle) = TextStyle(fontFamily = Fonts.of(t.family), fontSize = t.size * scale, lineHeight = t.lineHeight * scale,
        fontWeight = t.weight, letterSpacing = t.trackingEm.em)
    val display = style(NozzleTokens.Type.display)
    val headline = style(NozzleTokens.Type.headline)
    val title = style(NozzleTokens.Type.title)
    val body = style(NozzleTokens.Type.body)
    val bodySmall = style(NozzleTokens.Type.bodySmall)
    val label = style(NozzleTokens.Type.label)
    val metric = style(NozzleTokens.Type.metric)
    val metricSmall = style(NozzleTokens.Type.metricSmall)
}

class NozzleThemeValues(val dark: Boolean, val palette: NozzlePalette, val status: NozzleStatusColors, val type: NozzleType)

val LocalNozzle = staticCompositionLocalOf { NozzleThemeValues(true, NozzleTokens.darkPalette, NozzleTokens.darkStatus, NozzleType(1f)) }

object Nz {
    val colors: NozzlePalette @Composable get() = LocalNozzle.current.palette
    val status: NozzleStatusColors @Composable get() = LocalNozzle.current.status
    val type: NozzleType @Composable get() = LocalNozzle.current.type
    val dark: Boolean @Composable get() = LocalNozzle.current.dark
}

@Composable
fun NozzleTheme(themeSetting: String = "system", textScale: Float = 1f, content: @Composable () -> Unit) {
    val dark = when (themeSetting) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val values = remember(dark, textScale) {
        NozzleThemeValues(dark, if (dark) NozzleTokens.darkPalette else NozzleTokens.lightPalette, if (dark) NozzleTokens.darkStatus else NozzleTokens.lightStatus, NozzleType(textScale))
    }
    CompositionLocalProvider(LocalNozzle provides values,
        LocalTextSelectionColors provides TextSelectionColors(values.palette.accent, values.palette.accent.copy(alpha = 0.35f))) { content() }
}
