package net.jamesjennison.klippercompanion.environment
import androidx.compose.material3.*

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nozzleitall.environment.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val positionColors = listOf(Color(0xFFB59AFF), Color(0xFF35B9C5), Color(0xFFFFC66D), Color(0xFFFF91B5))
internal const val s4ArtworkAspect = 1402f / 1122f
// Matches ContentScale.Fit inside the same padded rectangle, including letterboxing.
internal fun s4ArtworkRect(width: Float, height: Float, horizontalPadding: Float, verticalPadding: Float): Rect {
    val availableWidth = (width - 2 * horizontalPadding).coerceAtLeast(0f)
    val availableHeight = (height - 2 * verticalPadding).coerceAtLeast(0f)
    val fittedWidth = minOf(availableWidth, availableHeight * s4ArtworkAspect)
    val fittedHeight = fittedWidth / s4ArtworkAspect
    return Rect((width - fittedWidth) / 2, (height - fittedHeight) / 2,
        (width + fittedWidth) / 2, (height + fittedHeight) / 2)
}
// Named corner locations on the elevated-front reference, not measured probe coordinates.
internal val s4ArtworkAnchors = listOf(Offset(.15f, .14f), Offset(.85f, .14f), Offset(.08f, .83f), Offset(.92f, .83f))
private fun fmt(v: Double?) = v?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"
private fun stamp(t: Long) = DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(t))

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun S4EnvironmentOverview(group: EnvironmentalSensorGroup,
    positions: Map<String, Binding>, data: EnvironmentalDashboardData, historyMode: Boolean,
    collecting: Boolean, selected: String?, showDiagram: Boolean = true, placementOnly: Boolean = false, onSelect: (Binding) -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(historyMode) {
        while (!historyMode) { now = System.currentTimeMillis(); delay(15_000) }
    }
    val savedReference = data.samples.filter { s -> group.bindings.any { it.id == s.binding } }.maxOfOrNull { it.time }
    // Refresh on receptions as well as the timer: a new reception must not look future-dated for 15s.
    val liveReference = remember(now, data.readings) { System.currentTimeMillis() }
    val reference = if (historyMode) savedReference ?: 0L else liveReference
    if (placementOnly) {
        DryerDiagram(positions, data, reference, historyMode, selected, onSelect)
        return
    }
    var showMethod by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("s4-environment-overview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(if (historyMode) savedReference?.let { "Saved history · summary at ${stamp(it)}" } ?: "Saved history · no saved group readings"
            else if (collecting) "Live · viewed from the front" else "Stopped · last received readings")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val diagram: @Composable () -> Unit = {
                DryerDiagram(positions, data, reference, historyMode, selected, onSelect)
            }
            val summary: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (channel in listOf(Channel.TEMPERATURE, Channel.HUMIDITY)) {
                        val stats = EnvironmentalGroupOverview.stats(group.bindings, data.readings, channel, reference)
                        Column(Modifier.widthIn(min = 230.dp).background(Color(0xFF192229), RoundedCornerShape(8.dp)).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Label(if (channel == Channel.TEMPERATURE) "Average temperature" else "Average humidity", color = Color(0xFFAEBCC5))
                            Metric("${fmt(stats?.average)} ${channel.unit}", if (channel == Channel.TEMPERATURE) positionColors[0] else positionColors[1])
                            Label("Range  ${fmt(stats?.minimum)} – ${fmt(stats?.maximum)} ${channel.unit}", color = Color.White)
                            Label("Spread  ${fmt(stats?.let { it.maximum - it.minimum })} ${channel.unit}   ·   ${stats?.count ?: 0}/4 positions", color = Color(0xFFAEBCC5))
                        }
                    }
                  }
                    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { showMethod = !showMethod }.padding(vertical = 4.dp)
                        .testTag("s4-calculation-details")
                        .semantics(mergeDescendants = true) { stateDescription = if (showMethod) "Expanded" else "Collapsed" }) {
                        Label(if (showMethod) "Hide calculation details" else "Calculation details", color = Color(0xFFAEBCC5))
                    }
                    if (showMethod) Label("Only readings received in the last 2 minutes contribute. Missing, older and future-dated readings are excluded. Positions follow sensor names. This is sampled air, not filament moisture; the illustration is not a heat map.")
                }
            }
            if (showDiagram && maxWidth >= 900.dp && LocalDensity.current.fontScale <= 1.2f) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { diagram() }
                    Box(Modifier.width(250.dp)) { summary() }
                }
            } else if (showDiagram) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { diagram(); summary() }
            else summary()
        }
        Label("Recorded trends", title = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            EnvironmentalGroupOverview.corners.forEachIndexed { i, corner ->
                positions[corner]?.let { Label("● $corner", color = positionColors[i]) }
            }
        }
        val chart: @Composable (Channel) -> Unit = { channel ->
          Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val series = EnvironmentalGroupOverview.trends(data.samples, group.bindings, channel)
            Label(if (channel == Channel.TEMPERATURE) "Temperature (°C)" else "Humidity (%RH)", title = true)
            val axis = series.values.firstOrNull()
            if (axis == null) Label("No recorded readings yet")
            else {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.height(110.dp).width(50.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Label(fmt(axis.high)); Label(fmt((axis.high + axis.low) / 2)); Label(fmt(axis.low))
                    }
                    Canvas(Modifier.weight(1f).height(110.dp).testTag("s4-group-trend-" + channel.name)
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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 800.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(1f)) { chart(Channel.TEMPERATURE) }
                Box(Modifier.weight(1f)) { chart(Channel.HUMIDITY) }
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chart(Channel.TEMPERATURE); chart(Channel.HUMIDITY)
            }
        }
    }
}

@Composable private fun DryerDiagram(positions: Map<String, Binding>, data: EnvironmentalDashboardData,
    reference: Long, historyMode: Boolean, selected: String?, onSelect: (Binding) -> Unit) {
    // Official-reference illustration; telemetry stays native, selectable and independent.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val diagramHeight = if (maxWidth >= 480.dp && fontScale <= 1.2f) 260.dp else 350.dp
    Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().height(diagramHeight * fontScale).align(Alignment.Center).testTag("s4-dryer-illustration")) {
        DryerArtwork(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 4.dp))
        Canvas(Modifier.fillMaxSize()) {
            val fitted = s4ArtworkRect(size.width, size.height, 48.dp.toPx(), 4.dp.toPx())
            s4ArtworkAnchors.forEachIndexed { i, anchor ->
                val p = Offset(fitted.left + anchor.x * fitted.width, fitted.top + anchor.y * fitted.height)
                val edge = Offset((if (i % 2 == 0) .19f else .81f) * size.width, (if (i < 2) .18f else .70f) * size.height)
                drawLine(positionColors[i].copy(alpha = .65f), edge, p, 1.dp.toPx())
                drawCircle(positionColors[i], 3.dp.toPx(), p)
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 26.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PositionLabel("Back Left", positions, data, reference, historyMode, 0, selected, onSelect, Modifier.weight(1f, fill = false).widthIn(max = 220.dp))
                Spacer(Modifier.width(12.dp))
                PositionLabel("Back Right", positions, data, reference, historyMode, 1, selected, onSelect, Modifier.weight(1f, fill = false).widthIn(max = 220.dp))
            }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PositionLabel("Front Left", positions, data, reference, historyMode, 2, selected, onSelect, Modifier.weight(1f, fill = false).widthIn(max = 220.dp))
                    Spacer(Modifier.width(12.dp))
                    PositionLabel("Front Right", positions, data, reference, historyMode, 3, selected, onSelect, Modifier.weight(1f, fill = false).widthIn(max = 220.dp))
                }
                Spacer(Modifier.height(22.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Label("FRONT", color = Color(0xFFAEBCC5)) }
            }
        }
    }
}

}

@OptIn(ExperimentalLayoutApi::class)
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
    Column(modifier.heightIn(min = 48.dp).background(Color(0xEE111B22), RoundedCornerShape(8.dp))
        .border(if (binding != null && binding.id == selected) 2.dp else 0.dp,
            if (binding != null && binding.id == selected) positionColors[index] else Color.Transparent, RoundedCornerShape(8.dp))
        .clickable(enabled = binding != null, role = Role.Button) { binding?.let(onSelect) }
        .padding(8.dp).testTag("s4-position-" + corner.replace(" ", "-"))
        .semantics(mergeDescendants = true) { stateDescription = if (binding == null) "No assigned sensor" else if (binding.id == selected) "Charts shown" else "Show charts" }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("● $corner", color = positionColors[index], title = true)
            if (status != "Recent reception" && status != "Saved reception") Label(status, color = Color(0xFFB7C5CC))
        }
        Label("${fmt(reading?.values?.get(Channel.TEMPERATURE))}°C  ·  ${fmt(reading?.values?.get(Channel.HUMIDITY))}%RH", color = Color.White)
    }
}

@Composable private fun Label(text: String, title: Boolean = false, color: Color = LocalContentColor.current) {
    Text(text, style = if (title) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall, color = color)
}

@Composable private fun Metric(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 28.sp), color = color)
}

@Composable private fun DryerArtwork(modifier: Modifier) {
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(net.jamesjennison.klippercompanion.R.drawable.s4_dryer),
        contentDescription = "S4 dryer illustration, viewed from the front", modifier = modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit)
}

