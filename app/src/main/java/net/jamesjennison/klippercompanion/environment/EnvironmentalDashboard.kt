package net.jamesjennison.klippercompanion.environment

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.*
import androidx.compose.ui.text.TextStyle
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
        EnvironmentalDashboardData().update(EnvironmentalView(view.state, bindings = view.bindings), history)
    }
    val data = if (view.state == EnvironmentalState.DISABLED || view.state == EnvironmentalState.DISABLED_RECOVERY) EnvironmentalDashboardData()
        else if (historyMode) historical else memory
    val samples = data.samples
    val bindings = view.bindings.filterNot { it.archived }
    var selected by remember { mutableStateOf<String?>(null) }
    val target = bindings.firstOrNull { it.id == selected } ?: bindings.firstOrNull()
    val temperature = MaterialTheme.colorScheme.primary
    val humidity = Color(0xFF35B9C5)
    Column(Modifier.fillMaxWidth().testTag("environment-dashboard"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (bindings.isEmpty()) DashboardCard(Modifier.fillMaxWidth()) {
            DashboardText("Your environment, at a glance", MaterialTheme.typography.titleMedium)
            DashboardText("Discover sensors to see their temperature and humidity here. Import Govee names once to add matching sensors automatically.")
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = (maxWidth.value / 270f).toInt().coerceIn(1, 4)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bindings.chunked(columns).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { binding -> key(binding.id) {
                        val name = view.sensorLabels[binding.sensorId] ?: binding.model
                        val reading = data.readings[binding.id]
                        DashboardCard(Modifier.weight(1f).testTag("environment-sensor-card")
                            .border(if (target?.id == binding.id) 2.dp else 0.dp, if (target?.id == binding.id) temperature else Color.Transparent, RoundedCornerShape(16.dp))
                            .selectable(target?.id == binding.id, role = Role.Tab) { selected = binding.id }
                            .semantics { stateDescription = if (target?.id == binding.id) "Charts shown" else "Show charts" }) {
                            DashboardText(name, MaterialTheme.typography.titleMedium, maxLines = 2)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    DashboardText("${number(reading?.values?.get(Channel.TEMPERATURE))}°C", MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp), temperature)
                                    DashboardText("Temperature", MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                                    val rh = reading?.values?.get(Channel.HUMIDITY)
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawArc(humidity.copy(alpha = .16f), 0f, 360f, false, style = Stroke(5.dp.toPx()))
                                        if (rh != null) drawArc(humidity, -90f, rh.coerceIn(0.0, 100.0).toFloat() * 3.6f, false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        DashboardText(number(rh), MaterialTheme.typography.titleLarge, humidity)
                                        DashboardText("%RH", MaterialTheme.typography.bodySmall, humidity)
                                    }
                                }
                            }
                            val series = EnvironmentalCharts.series(samples, binding.id, Channel.TEMPERATURE)
                            if (series != null) TrendCanvas(series, temperature, Modifier.fillMaxWidth().height(32.dp))
                            DashboardText(reading?.let { "Received ${time(it.time)}" } ?: "Waiting for a reading", MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                } }
            }
        }
        target?.let { binding -> DashboardCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    DashboardText("${view.sensorLabels[binding.sensorId] ?: binding.model} · trends", MaterialTheme.typography.titleMedium)
                    DashboardText(if (historyMode) "Saved history" else if (view.state == EnvironmentalState.COLLECTING) "Recording while this app is visible" else "Start monitoring to record new chart data", MaterialTheme.typography.bodySmall)
                }
                DashboardButton("Sensor details", { onDetails(binding) })
            }
            BoxWithConstraints {
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

@Composable private fun EnvironmentalTrend(samples: List<ChartSample>, binding: String, channel: Channel, color: Color) {
    val series = remember(samples, binding, channel) { EnvironmentalCharts.series(samples, binding, channel) }
    val title = if (channel == Channel.TEMPERATURE) "Temperature" else "Humidity"
    DashboardText("$title (${channel.unit})", MaterialTheme.typography.labelLarge, color)
    if (series == null) {
        Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) { DashboardText("Chart begins with the first recorded reading", MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val summary = "$title: ${series.points.size} samples, ${number(series.low)} to ${number(series.high)} ${channel.unit}; ${time(series.start)} to ${time(series.end)}"
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.height(140.dp).width(48.dp), verticalArrangement = Arrangement.SpaceBetween) {
            DashboardText(number(series.high), MaterialTheme.typography.bodySmall); DashboardText(number((series.high + series.low) / 2), MaterialTheme.typography.bodySmall); DashboardText(number(series.low), MaterialTheme.typography.bodySmall)
        }
        TrendCanvas(series, color, Modifier.weight(1f).height(140.dp).testTag("environment-trend-" + channel.name).semantics { contentDescription = summary }.focusable(), grid = true)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DashboardText(time(series.start), MaterialTheme.typography.bodySmall); DashboardText(time(series.end), MaterialTheme.typography.bodySmall)
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

@Composable private fun DashboardText(text: String, style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = LocalContentColor.current, maxLines: Int = Int.MAX_VALUE) {
    Text(text, style = style, color = color, maxLines = maxLines)
}
@Composable private fun DashboardCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}
@Composable private fun DashboardButton(text: String, onClick: () -> Unit) {
    TextButton(onClick) { Text(text) }
}
