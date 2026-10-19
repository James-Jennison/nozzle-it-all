package com.nozzleitall.environment

/** Display-only spatial mapping from explicit owner-assigned corner names, not radio proximity. */
object EnvironmentalGroupOverview {
    const val RECENT_MS = 120_000L
    val corners = listOf("Back Left", "Back Right", "Front Left", "Front Right")
    fun s4Positions(group: EnvironmentalSensorGroup, labels: Map<String, String>): Map<String, Binding>? {
        if (!group.title.equals("S4", true)) return null
        val pairs = group.bindings.map { b ->
            val name = labels[b.sensorId]?.trim()?.replace(Regex("\\s+"), " ") ?: return null
            val corner = corners.firstOrNull { name.equals("S4 $it", true) } ?: return null
            corner to b
        }
        if (pairs.map { it.first }.distinct().size != pairs.size) return null
        return pairs.toMap()
    }
    fun recent(reading: EnvironmentalDashboardData.Received?, reference: Long): Boolean =
        reading != null && reading.time <= reference && reference.toDouble() - reading.time.toDouble() <= RECENT_MS

    data class Stats(val count: Int, val average: Double, val minimum: Double, val maximum: Double)
    fun stats(bindings: List<Binding>, readings: Map<String, EnvironmentalDashboardData.Received>,
        channel: Channel, reference: Long): Stats? {
        val values = bindings.mapNotNull { b -> readings[b.id]?.takeIf { recent(it, reference) }
            ?.values?.get(channel)?.takeIf { it.isFinite() } }
        return if (values.isEmpty()) null else Stats(values.size, values.average(), values.min(), values.max())
    }

    /** Each sensor retains its own gaps and sessions, but all use identical axes. No interpolation. */
    fun trends(samples: List<ChartSample>, bindings: List<Binding>, channel: Channel): Map<String, EnvironmentalCharts.Series> {
        val series = bindings.mapNotNull { b -> EnvironmentalCharts.series(samples, b.id, channel)?.let { b.id to it } }.toMap()
        if (series.isEmpty()) return emptyMap()
        val low = series.values.minOf { it.low }; val high = series.values.maxOf { it.high }
        val start = series.values.minOf { it.start }; val end = series.values.maxOf { it.end }
        return series.mapValues { (_, s) -> EnvironmentalCharts.Series(s.points.map { p ->
            val time = s.start.toDouble() + p.x * (s.end.toDouble() - s.start.toDouble())
            val value = s.high - p.y * (s.high - s.low)
            EnvironmentalCharts.Point(if (start == end) .5f else ((time - start) / (end.toDouble() - start)).toFloat(),
                ((high - value) / (high - low)).toFloat(), p.connect)
        }, low, high, start, end) }
    }
}
