package com.nozzleitall.environment

import java.util.Locale

/** Presentation only: never changes names, bindings, placement or stored observations. */
data class EnvironmentalSensorGroup(val title: String?, val bindings: List<Binding>)

private val sensorPosition = Regex("^(.+?)\\s+(front\\s+left|front\\s+right|back\\s+left|back\\s+right|front|back|left|right|top|bottom)$", RegexOption.IGNORE_CASE)
private val positionOrder = listOf("front left", "front right", "back left", "back right", "front", "back", "left", "right", "top", "bottom")

fun environmentalSensorGroups(bindings: List<Binding>, labels: Map<String, String>): List<EnvironmentalSensorGroup> {
    data class Entry(val binding: Binding, val name: String, val prefix: String?, val position: Int)
    val entries = bindings.filterNot { it.archived }.map { binding ->
        val name = labels[binding.sensorId] ?: binding.model
        // Only an assigned name can establish a group, never a shared hardware model.
        val match = labels[binding.sensorId]?.trim()?.let(sensorPosition::matchEntire)
        val prefix = match?.groupValues?.get(1)?.trim()
        val position = match?.groupValues?.get(2)?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ")
        Entry(binding, name, prefix, positionOrder.indexOf(position))
    }
    val comparator = compareBy<Entry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name }.thenBy { it.binding.id }
    val candidates = entries.filter { it.prefix != null }.groupBy { it.prefix!!.lowercase(Locale.ROOT) }
    val grouped = candidates.filterValues { rows -> rows.map { it.position }.distinct().size >= 2 }
    val result = grouped.toSortedMap().values.map { rows ->
        EnvironmentalSensorGroup(rows.sortedWith(comparator).first().prefix,
            rows.sortedWith(compareBy<Entry> { it.position }.then(comparator)).map { it.binding })
    }
    val remaining = entries.filter { it.prefix?.lowercase(Locale.ROOT) !in grouped }.sortedWith(comparator)
    return result + if (remaining.isEmpty()) emptyList() else listOf(
        EnvironmentalSensorGroup(if (result.isEmpty()) null else "Other sensors", remaining.map { it.binding }))
}
