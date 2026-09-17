package net.jamesjennison.klippercompanion

import org.json.JSONObject

data class ToolheadTemperature(val name: String, val temperature: Double?, val target: Double?)
interface ToolheadReader : AutoCloseable {
    fun toolheadTemperatures(): List<ToolheadTemperature>
}
object Toolheads {
    private const val MAX_TOOLHEADS = 8
    fun validExtruder(name: String) = name.length <= 32 && Regex("extruder[0-9]*").matches(name)
    fun discover(listResult: JSONObject): List<String> {
        val objects = listResult.getJSONArray("objects")
        require(objects.length() <= 10000) { "Object list is too large." }
        val found = (0 until objects.length()).mapNotNull { objects.opt(it) as? String }.filter(::validExtruder).distinct()
        require(found.size <= MAX_TOOLHEADS) { "Too many toolheads." }
        return found.sortedBy { it.removePrefix("extruder").toIntOrNull() ?: 0 }
    }
    fun parse(names: List<String>, result: JSONObject): List<ToolheadTemperature> {
        val status = result.getJSONObject("status")
        return names.map { name ->
            val reading = status.optJSONObject(name)
            fun finite(field: String) = reading?.optDouble(field)?.takeIf { it.isFinite() }
            ToolheadTemperature(name, finite("temperature"), finite("target"))
        }
    }
}
