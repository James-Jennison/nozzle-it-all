package com.nozzleitall.desktop.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nozzleitall.environment.*
import com.nozzleitall.desktop.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun number(value: Double?) = value?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"
private fun time(value: Long) = DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))

/** Rendering and bounded last-reception memory only. No collection or storage side effects. */
@Composable internal fun EnvironmentalDashboard(view: EnvironmentalView, history: List<ChartSample>, historyMode: Boolean = false,
    onDetails: (Binding) -> Unit = {}) {
    var memory by remember { mutableStateOf(EnvironmentalDashboardData()) }
    LaunchedEffect(view, history) { memory = memory.update(view, history) }
    val historical = remember(view.state, view.bindings, history) {
        EnvironmentalDashboardData().update(EnvironmentalView(EnvironmentalState.ENABLED_IDLE, bindings = view.bindings), history)
    }
    val data = if (historyMode) historical
        else if (view.state == EnvironmentalState.DISABLED || view.state == EnvironmentalState.DISABLED_RECOVERY) EnvironmentalDashboardData() else memory
    val samples = data.samples
    val groups = remember(view.bindings, view.sensorLabels) { environmentalSensorGroups(view.bindings, view.sensorLabels) }
    val bindings = groups.flatMap { it.bindings }
    var selected by remember { mutableStateOf<String?>(null) }
    var expandedChoice by remember { mutableStateOf<Boolean?>(null) }
    var showDryer by remember { mutableStateOf(true) }
    val expandedTrends = expandedChoice ?: groups.none { EnvironmentalGroupOverview.s4Positions(it, view.sensorLabels) != null }
    val hasOverview = groups.any { EnvironmentalGroupOverview.s4Positions(it, view.sensorLabels) != null }
    val target = bindings.firstOrNull { it.id == selected } ?: if (hasOverview) null else bindings.firstOrNull()
    val temperature = Nz.colors.accent
    val humidity = Color(0xFF35B9C5)
    Column(Modifier.fillMaxWidth().testTag("environment-dashboard"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (bindings.isEmpty()) Card(Modifier.fillMaxWidth()) {
            Icon(NzIcon.HEAT, temperature, 40.dp)
            Txt("Your environment, at a glance", Nz.type.title)
            Txt("Discover sensors to see their temperature and humidity here. Import Govee names once to add matching sensors automatically.")
        }
        val renderGroup: @Composable (EnvironmentalSensorGroup, Boolean) -> Unit = { group, inlinePlacement ->
            val positions = EnvironmentalGroupOverview.s4Positions(group, view.sensorLabels)
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Txt(if (positions != null) "SUNLU S4" else group.title ?: "Sensors", Nz.type.title,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    if (positions != null) NzButton(if (showDryer) "Hide placement" else "Sensor placement", { showDryer = !showDryer })
                }
                EnvironmentalSensorTable(group.bindings, view.sensorLabels, data, target?.id, positions) {
                    selected = it.id; expandedChoice = true
                }
                if (positions != null) S4EnvironmentOverview(group, positions, data, historyMode,
                    view.state == EnvironmentalState.COLLECTING, target?.id, showDiagram = showDryer && inlinePlacement) {
                    selected = it.id; expandedChoice = true
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val primary = groups.firstOrNull { EnvironmentalGroupOverview.s4Positions(it, view.sensorLabels) != null }
            if (maxWidth >= 1200.dp && primary != null && groups.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1.6f)) { renderGroup(primary, false) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (showDryer) Card(Modifier.fillMaxWidth()) {
                            Txt("S4 sensor placement", Nz.type.title)
                            Txt("Viewed from the front", Nz.type.bodySmall, Nz.colors.textMuted)
                            S4EnvironmentOverview(primary, EnvironmentalGroupOverview.s4Positions(primary, view.sensorLabels)!!,
                                data, historyMode, view.state == EnvironmentalState.COLLECTING, target?.id, placementOnly = true) {
                                selected = it.id; expandedChoice = true
                            }
                        }
                        groups.filter { it != primary }.forEach { renderGroup(it, true) }
                    }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                groups.forEach { renderGroup(it, true) }
            }
        }
        target?.let { binding -> Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("${view.sensorLabels[binding.sensorId] ?: binding.model} · trends", Nz.type.title)
                    Txt(if (historyMode) "Saved history" else if (view.state == EnvironmentalState.COLLECTING) "Recording · continues when switching apps" else "Start monitoring to record new chart data", Nz.type.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NzButton(if (expandedTrends) "Hide sensor charts" else "Show sensor charts", { expandedChoice = !expandedTrends })
                    NzButton("Sensor details", { onDetails(binding) })
                }
            }
            if (expandedTrends) BoxWithConstraints {
                if (maxWidth >= 650.dp) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) { EnvironmentalTrend(samples, binding.id, Channel.TEMPERATURE, temperature) }
                    Column(Modifier.weight(1f)) { EnvironmentalTrend(samples, binding.id, Channel.HUMIDITY, humidity) }
                } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    EnvironmentalTrend(samples, binding.id, Channel.TEMPERATURE, temperature)
                    EnvironmentalTrend(samples, binding.id, Channel.HUMIDITY, humidity)
                }
            }
        } }
    }
}

/** Read-only aligned values; selecting a row opens its recorded charts below. */
@Composable private fun EnvironmentalSensorTable(bindings: List<Binding>, labels: Map<String, String>,
    data: EnvironmentalDashboardData, selected: String?, positions: Map<String, Binding>?, onSelect: (Binding) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 650.dp
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Txt("Sensor", Nz.type.bodySmall, Nz.colors.textMuted, Modifier.weight(1f))
                Txt("°C", Nz.type.bodySmall, Nz.colors.textMuted, Modifier.width(90.dp))
                Txt("%RH", Nz.type.bodySmall, Nz.colors.textMuted, Modifier.width(90.dp))
                if (wide) Txt("Last received", Nz.type.bodySmall, Nz.colors.textMuted, Modifier.width(150.dp))
            }
            bindings.forEach { binding -> key(binding.id) {
                val reading = data.readings[binding.id]
                val corner = positions?.entries?.firstOrNull { it.value.id == binding.id }?.key
                val color = corner?.let { positionColors.getOrNull(EnvironmentalGroupOverview.corners.indexOf(it)) } ?: Nz.colors.text
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .background(if (selected == binding.id) Nz.colors.accent.copy(alpha = .12f) else Color.Transparent)
                    .selectable(selected == binding.id, role = Role.Tab) { onSelect(binding) }
                    .semantics(mergeDescendants = true) { stateDescription = if (selected == binding.id) "Charts shown" else "Show charts" }
                    .testTag("environment-sensor-card").padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt((if (corner != null) "● " else "") + (labels[binding.sensorId] ?: binding.model), Nz.type.label, color)
                        if (!wide) Txt(reading?.let { time(it.time) } ?: "No reception", Nz.type.bodySmall, Nz.colors.textMuted)
                    }
                    Txt(number(reading?.values?.get(Channel.TEMPERATURE)), Nz.type.metricSmall, Nz.colors.accent, Modifier.width(90.dp))
                    Txt(number(reading?.values?.get(Channel.HUMIDITY)), Nz.type.metricSmall, Color(0xFF35B9C5), Modifier.width(90.dp))
                    if (wide) Txt(reading?.let { time(it.time) } ?: "No reception", Nz.type.bodySmall, Nz.colors.textMuted, Modifier.width(150.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Nz.colors.line))
            } }
        }
    }
}

@Composable private fun EnvironmentalTrend(samples: List<ChartSample>, binding: String, channel: Channel, color: Color) {
    val series = remember(samples, binding, channel) { EnvironmentalCharts.series(samples, binding, channel) }
    val title = if (channel == Channel.TEMPERATURE) "Temperature" else "Humidity"
    Txt("$title (${channel.unit})", Nz.type.label, color)
    if (series == null) {
        Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) { Txt("Chart begins with the first recorded reading", Nz.type.bodySmall, Nz.colors.textMuted) }
        return
    }
    val summary = "$title: ${series.points.size} samples, ${number(series.low)} to ${number(series.high)} ${channel.unit}; ${time(series.start)} to ${time(series.end)}"
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.height(140.dp).width(48.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Txt(number(series.high), Nz.type.bodySmall); Txt(number((series.high + series.low) / 2), Nz.type.bodySmall); Txt(number(series.low), Nz.type.bodySmall)
        }
        TrendCanvas(series, color, Modifier.weight(1f).height(140.dp).testTag("environment-trend-" + channel.name).semantics { contentDescription = summary }.focusable(), grid = true)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (series.start == series.end) Arrangement.Center else Arrangement.SpaceBetween) {
        Txt(time(series.start), Nz.type.bodySmall)
        if (series.end != series.start) Txt(time(series.end), Nz.type.bodySmall)
    }
}

@Composable private fun TrendCanvas(series: EnvironmentalCharts.Series, color: Color, modifier: Modifier, grid: Boolean = false) {
    Canvas(modifier) {
        val inset = 6.dp.toPx(); val w = (size.width - inset * 2).coerceAtLeast(1f); val h = (size.height - inset * 2).coerceAtLeast(1f)
        if (grid) for (i in 0..4) { val y = inset + h * i / 4; drawLine(Color.Gray.copy(alpha = .25f), Offset(inset, y), Offset(inset + w, y), 1.dp.toPx()) }
        var previous: Offset? = null
        series.points.forEach { point ->
            val p = Offset(inset + point.x * w, inset + point.y * h)
            previous?.let { if (point.connect) drawLine(color, it, p, 2.dp.toPx()) }
            drawCircle(color, 2.dp.toPx(), p); previous = p
        }
    }
}
