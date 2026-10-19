package com.nozzleitall.desktop.screens
import com.nozzleitall.desktop.ui.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.nozzleitall.environment.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val positionColors = listOf(Color(0xFFB59AFF), Color(0xFF35B9C5), Color(0xFFFFC66D), Color(0xFFFF91B5))
private fun fmt(v: Double?) = v?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"
private fun stamp(t: Long) = DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(t))

@Composable internal fun S4EnvironmentOverview(group: EnvironmentalSensorGroup,
    positions: Map<String, Binding>, data: EnvironmentalDashboardData, historyMode: Boolean,
    collecting: Boolean, selected: String?, onSelect: (Binding) -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(historyMode) {
        while (!historyMode) { now = System.currentTimeMillis(); delay(15_000) }
    }
    val savedReference = data.samples.filter { s -> group.bindings.any { it.id == s.binding } }.maxOfOrNull { it.time }
    // Refresh on receptions as well as the timer: a new reception must not look future-dated for 15s.
    val liveReference = remember(now, data.readings) { System.currentTimeMillis() }
    val reference = if (historyMode) savedReference ?: 0L else liveReference
    Column(Modifier.fillMaxWidth().testTag("s4-environment-overview"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Label("SUNLU S4 · environment", title = true)
        Label(if (historyMode) savedReference?.let { "Saved history · summary at ${stamp(it)}" } ?: "Saved history · no saved group readings"
            else if (collecting) "Live receptions · sensor positions viewed from the front" else "Monitoring stopped · last received readings")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val diagram: @Composable () -> Unit = {
                DryerDiagram(positions, data, reference, historyMode, selected, onSelect)
            }
            val summary: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Label("Group conditions", title = true)
                    for (channel in listOf(Channel.TEMPERATURE, Channel.HUMIDITY)) {
                        val stats = EnvironmentalGroupOverview.stats(group.bindings, data.readings, channel, reference)
                        Label(if (channel == Channel.TEMPERATURE) "Temperature (°C)" else "Humidity (%RH)", title = true)
                        Label("Average  ${fmt(stats?.average)} ${channel.unit}", title = true)
                        Label("Range  ${fmt(stats?.minimum)} – ${fmt(stats?.maximum)} ${channel.unit}")
                        Label("Spread  ${fmt(stats?.let { it.maximum - it.minimum })} ${channel.unit} · ${stats?.count ?: 0}/4 positions")
                    }
                    Label("Only receptions within 2 minutes of ${if (historyMode) "the page's last group reading" else "now"} contribute. Missing, older and future-dated readings are excluded.")
                    Label("Positions follow assigned sensor names; schematic, not a heat map. Readings describe sampled air, not filament moisture.")
                }
            }
            if (maxWidth >= 800.dp) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(1.4f)) { diagram() }
                Box(Modifier.weight(1f)) { summary() }
            } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { diagram(); summary() }
        }
        Label("Group trends · recorded receptions", title = true)
        EnvironmentalGroupOverview.corners.forEachIndexed { i, corner ->
            positions[corner]?.let { Label("● $corner", color = positionColors[i]) }
        }
        for (channel in listOf(Channel.TEMPERATURE, Channel.HUMIDITY)) {
            val series = EnvironmentalGroupOverview.trends(data.samples, group.bindings, channel)
            Label(if (channel == Channel.TEMPERATURE) "Temperature (°C)" else "Humidity (%RH)", title = true)
            val axis = series.values.firstOrNull()
            if (axis == null) Label("No recorded readings yet")
            else {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.height(150.dp).width(50.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Label(fmt(axis.high)); Label(fmt((axis.high + axis.low) / 2)); Label(fmt(axis.low))
                    }
                    Canvas(Modifier.weight(1f).height(150.dp).testTag("s4-group-trend-" + channel.name)
                        .semantics { contentDescription = "S4 ${channel.name} trends: ${series.size} sensors; shared range ${fmt(axis.low)} to ${fmt(axis.high)} ${channel.unit}; ${stamp(axis.start)} to ${stamp(axis.end)}. " +
                            EnvironmentalGroupOverview.corners.mapIndexedNotNull { i, corner -> positions[corner]?.id?.let(series::get)?.let { s ->
                                "$corner (${listOf("purple", "teal", "amber", "pink")[i]}): ${s.points.size} samples"
                            } }.joinToString("; ") }) {
                        val pad = 5.dp.toPx(); val w = (size.width - 2 * pad).coerceAtLeast(1f); val h = size.height - 2 * pad
                        for (i in 0..4) drawLine(Color.Gray.copy(alpha = .25f), Offset(pad, pad + h * i / 4), Offset(pad + w, pad + h * i / 4))
                        EnvironmentalGroupOverview.corners.forEachIndexed { index, corner ->
                            val s = positions[corner]?.id?.let(series::get)
                            var previous: Offset? = null
                            s?.points?.forEach { point ->
                                val p = Offset(pad + point.x * w, pad + point.y * h)
                                previous?.let { if (point.connect) drawLine(positionColors[index], it, p, 2.dp.toPx()) }
                                drawCircle(positionColors[index], 2.5.dp.toPx(), p); previous = p
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label(stamp(axis.start)); if (axis.end != axis.start) Label(stamp(axis.end))
                }
            }
        }
    }
}

@Composable private fun DryerDiagram(positions: Map<String, Binding>, data: EnvironmentalDashboardData,
    reference: Long, historyMode: Boolean, selected: String?, onSelect: (Binding) -> Unit) {
    // Original scalable cutaway illustration: long S4 shell, clear lid, four reel bays and front control panel.
    Box(Modifier.fillMaxWidth().height(350.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)).testTag("s4-dryer-illustration")) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawRoundRect(Color(0xFF080D11), Offset(w * .02f, h * .03f), Size(w * .96f, h * .91f), CornerRadius(22.dp.toPx()))
            drawRoundRect(Color(0xFF52616B), Offset(w * .03f, h * .04f), Size(w * .94f, h * .79f), CornerRadius(20.dp.toPx()), style = Stroke(3.dp.toPx()))
            drawRoundRect(Color(0xFF24343B), Offset(w * .055f, h * .065f), Size(w * .89f, h * .74f), CornerRadius(16.dp.toPx()))
            for (i in 0..3) {
                val x = w * (.17f + i * .22f)
                drawRoundRect(Color(0xFF1C2429), Offset(x - w * .075f, h * .30f), Size(w * .15f, h * .26f), CornerRadius(10.dp.toPx()))
                for (j in 0..6) drawLine(Color(0xFF66717B), Offset(x - w * .055f + j * w * .018f, h * .32f),
                    Offset(x - w * .055f + j * w * .018f, h * .54f), 2.dp.toPx())
                drawOval(Color(0xFF909AA4), Offset(x - w * .078f, h * .30f), Size(w * .035f, h * .26f), style = Stroke(2.dp.toPx()))
                drawOval(Color(0xFF909AA4), Offset(x + w * .043f, h * .30f), Size(w * .035f, h * .26f), style = Stroke(2.dp.toPx()))
            }
            drawLine(Color(0xFF8298A3).copy(alpha = .4f), Offset(w * .08f, h * .27f), Offset(w * .92f, h * .27f), 2.dp.toPx())
            drawRoundRect(Color(0xFF152B35), Offset(w * .38f, h * .845f), Size(w * .24f, h * .075f), CornerRadius(4.dp.toPx()))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 26.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PositionLabel("Back Left", positions, data, reference, historyMode, 0, selected, onSelect, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                PositionLabel("Back Right", positions, data, reference, historyMode, 1, selected, onSelect, Modifier.weight(1f))
            }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PositionLabel("Front Left", positions, data, reference, historyMode, 2, selected, onSelect, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    PositionLabel("Front Right", positions, data, reference, historyMode, 3, selected, onSelect, Modifier.weight(1f))
                }
                Spacer(Modifier.height(22.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Label("S4  ·  FRONT", color = Color(0xFFDAEEF5)) }
            }
        }
    }
}

@Composable private fun PositionLabel(corner: String, positions: Map<String, Binding>, data: EnvironmentalDashboardData,
    reference: Long, historyMode: Boolean, index: Int, selected: String?, onSelect: (Binding) -> Unit, modifier: Modifier) {
    val binding = positions[corner]
    val reading = binding?.let { data.readings[it.id] }
    val status = when {
        binding == null -> "Not assigned"
        reading == null -> "No reception"
        reading.time > reference -> "Future timestamp"
        !EnvironmentalGroupOverview.recent(reading, reference) -> "Older than 2 min"
        historyMode -> "Saved reception"
        else -> "Recent reception"
    }
    Column(modifier.background(Color(0xEE111B22), RoundedCornerShape(8.dp))
        .border(if (binding != null && binding.id == selected) 2.dp else 0.dp,
            if (binding != null && binding.id == selected) positionColors[index] else Color.Transparent, RoundedCornerShape(8.dp))
        .clickable(enabled = binding != null, role = Role.Button) { binding?.let(onSelect) }
        .padding(8.dp).testTag("s4-position-" + corner.replace(" ", "-"))
        .semantics(mergeDescendants = true) { stateDescription = if (binding == null) "No assigned sensor" else if (binding.id == selected) "Charts shown" else "Show charts" }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label("● $corner", color = positionColors[index], title = true)
        Label("${fmt(reading?.values?.get(Channel.TEMPERATURE))}°C  ·  ${fmt(reading?.values?.get(Channel.HUMIDITY))}%RH", color = Color.White)
        Label(status, color = Color(0xFFB7C5CC))
    }
}

@Composable private fun Label(text: String, title: Boolean = false, color: Color = Nz.colors.text) {
    Txt(text, if (title) Nz.type.label else Nz.type.bodySmall, color)
}

