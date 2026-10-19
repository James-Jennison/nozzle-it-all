package com.nozzleitall.desktop.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.nozzleitall.environment.*
import com.nozzleitall.desktop.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun number(value: Double?) = value?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"
private fun time(value: Long) = DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))

/** Rendering only: no radio, cloud request, storage, or invented samples. */
@Composable internal fun EnvironmentalDashboard(view: EnvironmentalView, history: List<ChartSample>, historyMode: Boolean = false) {
    val samples = if (historyMode) history else view.chartSamples
    val bindings = view.bindings.filterNot { it.archived }
    var selected by remember { mutableStateOf<String?>(null) }
    val target = bindings.firstOrNull { it.id == selected } ?: bindings.firstOrNull()
    Column(Modifier.fillMaxWidth().testTag("environment-dashboard"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt("Sensor dashboard", Nz.type.title)
        Txt(if (historyMode) "Local history page — recorded receptions" else "Live view — explicitly start monitoring for trends", Nz.type.bodySmall)
        if (bindings.isEmpty()) Txt("Discover and add sensors, or open local history to restore saved sensors.")
        bindings.forEach { binding ->
            val name = view.sensorLabels[binding.sensorId] ?: binding.model
            val latest = if (historyMode) samples.lastOrNull { it.binding == binding.id }
                else view.latest[binding.id]?.let(EnvironmentalCharts::sample)
            val reception = if (historyMode) null else view.discovery[binding.address]
            val values = reception?.readings?.associate { it.channel to it.value } ?: latest?.values.orEmpty()
            val received = reception?.receivedAt ?: latest?.time
            Card(Modifier.fillMaxWidth().testTag("environment-sensor-card")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NzButton(name, { selected = binding.id }, modifier = Modifier.semantics {
                        stateDescription = if (target?.id == binding.id) "Charts shown" else "Show charts"
                    })
                    Txt("${number(values[Channel.TEMPERATURE])} °C    ${number(values[Channel.HUMIDITY])} %RH", Nz.type.metricSmall)
                    Txt("Battery ${number(values[Channel.BATTERY_PERCENT])}% · ${if (received == null) "No reception available" else "Received " + time(received)}", Nz.type.bodySmall)
                    Txt("Reception snapshot · physical measurement age unknown", Nz.type.bodySmall)
                    if (target?.id == binding.id) {
                        EnvironmentalTrend(samples, binding.id, Channel.TEMPERATURE)
                        EnvironmentalTrend(samples, binding.id, Channel.HUMIDITY)
                    }
                }
            }
        }
        Txt("Up to 500 received samples in this view. Lines stop at session changes, known drops and gaps over 2 minutes; unobserved gaps remain unknown. Open local history to load/refresh stored readings.", Nz.type.bodySmall)
    }
}

@Composable private fun EnvironmentalTrend(samples: List<ChartSample>, binding: String, channel: Channel) {
    val series = remember(samples, binding, channel) { EnvironmentalCharts.series(samples, binding, channel) }
    val title = if (channel == Channel.TEMPERATURE) "Temperature" else "Humidity"
    val color = Nz.colors.accent
    Txt("$title (${channel.unit})")
    if (series == null) { Txt("No recorded ${title.lowercase()} samples"); return }
    val summary = "$title: ${series.points.size} samples, ${number(series.low)} to ${number(series.high)} ${channel.unit}; ${time(series.start)} to ${time(series.end)}"
    Canvas(Modifier.fillMaxWidth().height(140.dp).testTag("environment-trend-" + channel.name)
        .semantics { contentDescription = summary }.focusable()) {
        val inset = 6.dp.toPx()
        val w = (size.width - inset * 2).coerceAtLeast(1f)
        val h = (size.height - inset * 2).coerceAtLeast(1f)
        for (i in 0..4) {
            val y = inset + h * i / 4
            drawLine(Color.Gray.copy(alpha = .3f), Offset(inset, y), Offset(inset + w, y), 1.dp.toPx())
        }
        var previous: Offset? = null
        series.points.forEach { point ->
            val p = Offset(inset + point.x * w, inset + point.y * h)
            previous?.let { if (point.connect) drawLine(color, it, p, 2.dp.toPx()) }
            drawCircle(color, 2.dp.toPx(), p)
            previous = p
        }
    }
    Txt("${number(series.low)}–${number(series.high)} ${channel.unit} · ${time(series.start)} → ${time(series.end)}", Nz.type.bodySmall)
}
