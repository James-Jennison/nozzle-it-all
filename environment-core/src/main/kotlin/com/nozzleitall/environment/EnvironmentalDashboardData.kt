package com.nozzleitall.environment

/** Display-only last receptions. Never writes observations or starts the radio. */
data class EnvironmentalDashboardData(
    val readings: Map<String, Received> = emptyMap(),
    val samples: List<ChartSample> = emptyList()
) {
    data class Received(val time: Long, val values: Map<Channel, Double>)
    fun update(view: EnvironmentalView, history: List<ChartSample>): EnvironmentalDashboardData {
        if (view.state == EnvironmentalState.DISABLED || view.state == EnvironmentalState.DISABLED_RECOVERY) return EnvironmentalDashboardData()
        val active = view.bindings.filterNot { it.archived }.map { it.id }.toSet()
        val merged = EnvironmentalCharts.merge(EnvironmentalCharts.merge(samples, history), view.chartSamples).filter { it.binding in active }
        val next = readings.filterKeys { it in active }.toMutableMap()
        fun receive(id: String, value: Received) {
            if (id in active && value.time >= (next[id]?.time ?: Long.MIN_VALUE)) next[id] = value
        }
        merged.forEach { receive(it.binding, Received(it.time, it.values)) }
        view.latest.values.forEach { observation ->
            val s = EnvironmentalCharts.sample(observation)
            receive(s.binding, Received(s.time, s.values))
        }
        view.bindings.filterNot { it.archived }.forEach { binding ->
            view.discovery.entries.firstOrNull { it.key.equals(binding.address, true) }?.value?.let { r ->
                receive(binding.id, Received(r.receivedAt, r.readings.filter { it.value.isFinite() }.associate { it.channel to it.value }))
            }
        }
        return EnvironmentalDashboardData(next, merged)
    }
}

fun EnvironmentalState.dashboardStatus(): String = when (this) {
    EnvironmentalState.DISABLED -> "Monitoring is off"
    EnvironmentalState.DISABLED_RECOVERY -> "Storage recovery needed"
    EnvironmentalState.ENABLED_IDLE -> "Stopped · showing last received readings"
    EnvironmentalState.DISCOVERING -> "Finding sensors · monitoring has not started"
    EnvironmentalState.COLLECTING -> "Monitoring · receiving and recording readings"
}

/** Surface actionable failures without exposing adapter paths and diagnostic identifiers. */
fun EnvironmentalView.dashboardNotice(): String? {
    return when (state) {
        EnvironmentalState.ENABLED_IDLE, EnvironmentalState.DISABLED_RECOVERY -> status.substringBefore(" · ")
        else -> null
    }
}
