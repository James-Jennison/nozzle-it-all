package net.jamesjennison.klippercompanion

import org.json.JSONObject

// Phase 9S: the transport-independent Moonraker rules that the control models need, split out of Moonraker's companion.
object MoonrakerRules {
    fun activeExtruder(status: JSONObject): String = status.optJSONObject("toolhead")?.optString("extruder", "")
        ?.takeIf { it.length <= 32 && Regex("extruder[0-9]*").matches(it) } ?: ""
    fun macro(name: String): PrinterCommand {
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) { "Unsupported macro name" }
        return PrinterCommand("Run $name", "printer/gcode/script", mapOf("script" to name))
    }
}
