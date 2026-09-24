package net.jamesjennison.klippercompanion

import java.math.BigDecimal
import org.json.JSONObject

data class SpeedFlowRequest(val kind: String, val percent: String)
data class SpeedFlowStatus(val ready: Boolean, val printState: String, val speedFactor: Double?, val extrudeFactor: Double?, val noMacroOverride: Boolean)
interface SpeedFlowReader : AutoCloseable {
    fun speedFlowStatus(): SpeedFlowStatus
}
object SpeedFlowControls {
    val idleStates = setOf("standby", "complete", "cancelled")
    val kinds = setOf("speed", "flow")
    fun parse(result: JSONObject): SpeedFlowStatus {
        val status = result.getJSONObject("status")
        val settings = status.optJSONObject("configfile")?.optJSONObject("settings")
        val unmodified = settings != null && settings.keys().asSequence().none { key -> setOf("M220", "M221").any { key.equals("gcode_macro $it", true) } }
        val move = status.optJSONObject("gcode_move")
        fun finite(field: String) = move?.optDouble(field)?.takeIf { it.isFinite() }
        return SpeedFlowStatus(status.optJSONObject("webhooks")?.optString("state") == "ready",
            status.optJSONObject("print_stats")?.optString("state", "unknown") ?: "unknown",
            finite("speed_factor"), finite("extrude_factor"), unmodified)
    }
    fun prepare(request: SpeedFlowRequest, status: SpeedFlowStatus): PrinterCommand {
        require(request.kind in kinds) { "Unsupported factor." }
        require(status.ready && status.printState in idleStates) { "Speed/flow controls require an idle, ready printer." }
        require(status.noMacroOverride) { "The standard M220/M221 command cannot be verified." }
        val current = if (request.kind == "speed") status.speedFactor else status.extrudeFactor
        require(current?.isFinite() == true) { "Current factor unavailable." }
        require(request.percent.length in 1..24 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(request.percent)) { "Enter a plain nonnegative percentage." }
        val value = BigDecimal(request.percent)
        if (request.kind == "speed") require(value >= BigDecimal("10") && value <= BigDecimal("200")) { "Use a percentage from 10 to 200." }
        else require(value >= BigDecimal("50") && value <= BigDecimal("150")) { "Use a percentage from 50 to 150." }
        val normalized = value.stripTrailingZeros().toPlainString()
        val script = "${if (request.kind == "speed") "M220" else "M221"} S$normalized"
        val label = if (request.kind == "speed") "speed" else "flow"
        return PrinterCommand("Set $label factor to $normalized%", "printer/gcode/script", mapOf("script" to script), idleStates, speedFlowRequest = request)
    }
}
