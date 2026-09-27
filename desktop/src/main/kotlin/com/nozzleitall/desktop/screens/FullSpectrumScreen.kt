package com.nozzleitall.desktop.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.Glossary
import com.nozzleitall.printer.ext.FullSpectrumState
import com.nozzleitall.printer.ext.fullSpectrum

/**
 * Full Spectrum: shows which colours the loaded toolheads can make together, from the printer's own report. Mixing
 * settings for a project will be applied in Prepare; this screen is where you see what's possible first.
 */
@Composable
fun FullSpectrumScreen(state: AppState) {
    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeader("Full Spectrum", Glossary.term("concept.full-spectrum").description)
        val entry = PrinterPicker(state) ?: run { NoPrinterYet(state, "which colours your toolheads can mix"); return@Column }
        val c = Nz.colors
        if (entry.capabilities.value?.fullSpectrum != true) {
            Banner("Full Spectrum is a Snapmaker U1 feature. ${entry.config.identity.displayName} doesn't offer it.", BannerKind.INFO)
            return@Column
        }
        val fs = FullSpectrumState.from(entry.status.value) ?: FullSpectrumState(false, unavailableReason = "The printer hasn't reported its loaded colours yet.")
        if (!fs.available) Banner(fs.unavailableReason ?: "Full Spectrum isn't available right now.", BannerKind.INFO)
        val palette = fs.palette.mapNotNull { parseHex(it)?.let { col -> it to col } }
        if (palette.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
            Txt("Loaded colours", Nz.type.title)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                palette.forEachIndexed { i, (hex, col) -> Swatch(col, "Toolhead ${i + 1}", hex) }
            }
        }
        if (palette.size >= 2) Card(Modifier.fillMaxWidth()) {
            Txt("Mixes you can print", Nz.type.title)
            Txt("Two loaded colours alternated in thin layers read as a blend. These previews are approximate: real results depend on the material, layer height and lighting.",
                Nz.type.bodySmall, c.textMuted)
            val pairs = palette.indices.flatMap { a -> (a + 1 until palette.size).map { b -> a to b } }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pairs.forEach { (a, b) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Txt("${a + 1} + ${b + 1}", Nz.type.label, c.textMuted, Modifier.width(56.dp))
                        listOf(0.25f, 0.5f, 0.75f).forEach { t ->
                            val mix = lerp(palette[a].second, palette[b].second, t)
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(mix).border(1.dp, c.line, RoundedCornerShape(10.dp))
                                .semantics { contentDescription = "Approximately ${((1 - t) * 100).toInt()} percent toolhead ${a + 1}, ${(t * 100).toInt()} percent toolhead ${b + 1}" })
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Txt("Use it in a project", Nz.type.title)
            Txt("Assign colours to your models in Prepare. Setting mixing ratios and gradients in Prepare is coming next.",
                Nz.type.body, c.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NzButton("Go to Prepare", { state.destination = Destination.PREPARE }, kind = ButtonKind.SECONDARY, icon = NzIcon.PREPARE)
            }
        }
    }
}

@Composable
private fun Swatch(col: Color, label: String, hex: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label, $hex" }) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(col).border(1.dp, Nz.colors.lineStrong, CircleShape))
        Txt(label, Nz.type.bodySmall)
        Txt(hex, Nz.type.metricSmall, Nz.colors.textMuted)
    }
}

/** Mixes in linear light, which is closer to how thin alternating layers read than mixing sRGB values directly. */
fun lerp(a: Color, b: Color, t: Float): Color {
    fun lin(v: Float) = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    fun srgb(v: Float) = if (v <= 0.0031308f) v * 12.92f else (1.055f * Math.pow(v.toDouble(), 1 / 2.4) - 0.055f).toFloat()
    fun m(x: Float, y: Float) = srgb(lin(x) * (1 - t) + lin(y) * t).coerceIn(0f, 1f)
    return Color(m(a.red, b.red), m(a.green, b.green), m(a.blue, b.blue))
}
