package net.jamesjennison.klippercompanion

import java.math.BigDecimal
import org.json.JSONObject

// Panda Breath: a chamber-heater/filament-dryer accessory. Klipper surfaces it as a
// `panda_breath` state object plus a `heater_generic` whose name usually matches
// panda|breath|chamber. The Auto/Dry gcodes are extras-registered, so they are
// missing from printer/gcode/help on firmware that doesn't support them - feature
// detected rather than gated to a specific printer kind, so any generic Klipper
// printer that happens to have the hardware gets the panel; one that doesn't shows
// "not detected" instead of nothing (owner doesn't own this hardware, so this is
// built from Helix's reference logic and Klipper/Moonraker's own object model, not
// physically verified - see FEATURE_PARITY_ROADMAP.md).
// Adapted from Helix (github.com/FatBoy721/Helix), AGPL-3.0-or-later.
// Original: hooks/useDashboardModel.ts (findPandaBreathHeater/pandaModeLabel/panda actions).
data class PandaBreathStatus(
    val ready: Boolean, val printState: String, val detected: Boolean,
    val temperature: Double?, val target: Double?, val mode: String,
    val supportsAuto: Boolean, val autoOn: Boolean, val dryCommand: String,
    val dryActive: Boolean, val supportsDryStop: Boolean,
)
sealed class PandaBreathRequest {
    data class SetTarget(val value: String) : PandaBreathRequest()
    data class SetAuto(val enabled: Boolean, val value: String) : PandaBreathRequest()
    data class Dry(val value: String, val hours: String) : PandaBreathRequest()
    object Stop : PandaBreathRequest()
}
interface PandaBreathReader : AutoCloseable {
    fun pandaBreathStatus(): PandaBreathStatus
}
object PandaBreathControls {
    val idleStates = setOf("standby", "complete", "cancelled")
    private val nameRe = Regex("(panda|breath|chamber)", RegexOption.IGNORE_CASE)
    private val maxTemp = BigDecimal("60")
    // Matches Helix's own PANDA_AUTO_FILTER_TEMP/PANDA_AUTO_HOTBED_TEMP constants exactly -
    // these are fixed accessory presets, not user-configurable in either app.
    private const val AUTO_FILTER_TEMP = 30
    private const val AUTO_HOTBED_TEMP = 80

    fun parse(result: JSONObject, help: JSONObject): PandaBreathStatus {
        val status = result.getJSONObject("status")
        val heaterKeys = status.keys().asSequence()
            .filter { it.startsWith("heater_generic ") && status.optJSONObject(it)?.has("temperature") == true }
            .toList()
        require(heaterKeys.size <= 64) { "Too many generic heaters." }
        // If there's exactly one unnamed generic heater, assume it's the Panda Breath - matches
        // Helix's own findPandaBreathHeater. Multiple unnamed heaters is ambiguous; don't guess.
        val named = heaterKeys.firstOrNull { nameRe.containsMatchIn(it.removePrefix("heater_generic ")) }
        val key = named ?: heaterKeys.singleOrNull()
        val heater = key?.let { status.optJSONObject(it) }
        val panda = status.optJSONObject("panda_breath")
        fun hasGcode(command: String) = help.has(command)
        val supportsAuto = hasGcode("PANDA_BREATH_AUTO") || panda?.opt("auto_target") is Number
        val dryCommand = when {
            hasGcode("PANDA_BREATH_DRY_START") -> "start"
            hasGcode("PANDA_BREATH_DRY_RUN") -> "run"
            else -> ""
        }
        val target = heater?.optDouble("target")?.takeIf { it.isFinite() }
        val temperature = heater?.optDouble("temperature")?.takeIf { it.isFinite() }
        val autoOn = panda?.optBoolean("auto_enabled") == true || panda?.optInt("work_mode") == 1
        val remaining = panda?.optDouble("remaining_seconds")?.takeIf { it.isFinite() }
        val dryActive = panda?.optBoolean("filament_drying_active") == true ||
            (remaining != null && remaining > 0) || panda?.optInt("work_mode") == 3
        val ready = status.optJSONObject("webhooks")?.optString("state") == "ready"
        val printState = status.optJSONObject("print_stats")?.optString("state", "unknown") ?: "unknown"
        val mode = when {
            dryActive -> "Dry" + (remaining?.takeIf { it > 0 }?.let { " " + dryTimeLabel(it) } ?: "")
            autoOn -> "Auto"
            (target ?: 0.0) > 0 || panda?.optBoolean("work_on") == true -> "Manual"
            heater != null -> "Idle"
            else -> "not detected"
        }
        return PandaBreathStatus(ready, printState, heater != null, temperature, target, mode,
            supportsAuto, autoOn, dryCommand, dryActive, hasGcode("PANDA_BREATH_DRY_STOP"))
    }
    private fun dryTimeLabel(seconds: Double): String {
        val hours = (seconds / 3600).toInt()
        val minutes = Math.ceil((seconds % 3600) / 60).toInt()
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes.coerceAtLeast(1)}m"
    }
    private fun clampedTemp(raw: String): BigDecimal {
        require(raw.length in 1..24 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(raw)) { "Enter a plain nonnegative temperature." }
        val value = BigDecimal(raw)
        require(value >= BigDecimal.ZERO && value <= maxTemp) { "Use a temperature from 0 to $maxTemp°C." }
        return value
    }
    private fun requireIdle(status: PandaBreathStatus) {
        require(status.detected) { "No Panda Breath (or equivalent) heater was detected." }
        require(status.ready && status.printState in idleStates) { "Panda Breath controls require an idle, ready printer." }
    }
    fun prepareSetTarget(request: PandaBreathRequest.SetTarget, status: PandaBreathStatus): PrinterCommand {
        requireIdle(status)
        val value = clampedTemp(request.value)
        val normalized = value.stripTrailingZeros().toPlainString()
        return PrinterCommand("Set Panda Breath to $normalized°C", "printer/gcode/script",
            mapOf("script" to "M141 S$normalized"), idleStates, pandaBreathRequest = request)
    }
    fun prepareAuto(request: PandaBreathRequest.SetAuto, status: PandaBreathStatus): PrinterCommand {
        requireIdle(status)
        require(status.supportsAuto) { "This firmware does not support Panda Breath Auto mode." }
        val script = if (request.enabled) {
            val normalized = clampedTemp(request.value).stripTrailingZeros().toPlainString()
            "PANDA_BREATH_AUTO ENABLE=1 TARGET=$normalized FILTERTEMP=$AUTO_FILTER_TEMP HOTBEDTEMP=$AUTO_HOTBED_TEMP"
        } else "PANDA_BREATH_AUTO ENABLE=0"
        val title = if (request.enabled) "Enable Panda Breath Auto mode" else "Disable Panda Breath Auto mode"
        return PrinterCommand(title, "printer/gcode/script", mapOf("script" to script), idleStates, pandaBreathRequest = request)
    }
    fun prepareDry(request: PandaBreathRequest.Dry, status: PandaBreathStatus): PrinterCommand {
        requireIdle(status)
        require(status.dryCommand.isNotEmpty()) { "This firmware does not support a Panda Breath dry cycle." }
        require(request.hours.length in 1..8 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(request.hours)) { "Enter a plain, positive number of hours." }
        val hours = BigDecimal(request.hours)
        require(hours > BigDecimal.ZERO && hours <= BigDecimal("48")) { "Use a dry duration from 0 to 48 hours." }
        val normalized = clampedTemp(request.value).stripTrailingZeros().toPlainString()
        val script = if (status.dryCommand == "start") "PANDA_BREATH_DRY_START TEMP=$normalized HOURS=${hours.stripTrailingZeros().toPlainString()}"
            else "PANDA_BREATH_DRY_RUN TARGET=$normalized DURATION=${hours.multiply(BigDecimal(60)).stripTrailingZeros().toPlainString()}"
        return PrinterCommand("Dry filament at $normalized°C for ${hours.stripTrailingZeros().toPlainString()}h", "printer/gcode/script",
            mapOf("script" to script), idleStates, pandaBreathRequest = request)
    }
    fun prepareStop(status: PandaBreathStatus): PrinterCommand {
        requireIdle(status)
        val lines = buildList {
            if (status.dryActive && status.supportsDryStop) add("PANDA_BREATH_DRY_STOP")
            if (status.supportsAuto) add("PANDA_BREATH_AUTO ENABLE=0")
            add("M141 S0")
        }
        return PrinterCommand("Stop Panda Breath", "printer/gcode/script",
            mapOf("script" to lines.joinToString("\n")), idleStates, pandaBreathRequest = PandaBreathRequest.Stop)
    }
}
