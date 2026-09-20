package net.jamesjennison.klippercompanion

import java.math.BigDecimal
import org.json.JSONObject

// PAXX multiACE: lane status/RFID info, dryer, load/unload, cross-ACE switching. PAXX-specific
// (unlike PandaBreathControls/Spoolman, which are generic-Klipper feature-detected), so this
// gates to PrinterKind.SNAPMAKER_U1_PAXX rather than "any printer with the object" - the owner
// doesn't have this hardware attached to their own U1; built anyway at the owner's explicit
// request, since the app serves more than one printer/owner (same reasoning as P31/P13).
// Adapted from Helix (github.com/FatBoy721/Helix), AGPL-3.0-or-later - logic reference only
// (Helix's own implementation is TypeScript, nothing portable to Kotlin). Original:
// hooks/useACE.ts, whose own header cites the exact source verified against:
// "multiACE commands verified against decay71/multiACE v0.99.2b ace.py."
// Simplification versus Helix: Helix additionally treats an all-zero RGB color as "no color
// reported" when nothing else on the slot looks branded (isGenericBlack), to avoid painting an
// empty/unknown slot black. That heuristic isn't reproduced here - colorHex is passed through
// as reported, null when absent.
data class AceLane(val index: Int, val status: String, val brand: String?, val material: String?, val colorHex: String?)
data class AceDryer(val active: Boolean, val targetTemp: Double?, val remainingMinutes: Double?)
data class AceUnit(val aceIndex: Int, val connected: Boolean, val active: Boolean, val temperature: Double?, val humidity: Double?, val dryer: AceDryer, val lanes: List<AceLane>)
data class AceStatus(val ready: Boolean, val printState: String, val hardwareDetected: Boolean, val units: List<AceUnit>)
sealed class AceRequest {
    data class Load(val ace: Int, val lane: Int) : AceRequest()
    data class Unload(val lane: Int) : AceRequest()
    object UnloadAll : AceRequest()
    data class DryStart(val ace: Int, val temp: String, val minutes: String) : AceRequest()
    data class DryStop(val ace: Int) : AceRequest()
    data class Switch(val ace: Int) : AceRequest()
}
interface AceReader : AutoCloseable {
    fun aceStatus(): AceStatus
}
object AceControls {
    val idleStates = setOf("standby", "complete", "cancelled")
    fun parse(result: JSONObject): AceStatus {
        val status = result.getJSONObject("status")
        val ready = status.optJSONObject("webhooks")?.optString("state") == "ready"
        val printState = status.optJSONObject("print_stats")?.optString("state", "unknown") ?: "unknown"
        val controller = status.optJSONObject("ace")
        val deviceCount = controller?.optInt("device_count") ?: 0
        val hardwareDetected = deviceCount > 0
        val activeDevice = controller?.optInt("active_device") ?: 0
        val rawUnits = controller?.optJSONArray("aces")
        val units = if (hardwareDetected && rawUnits != null && rawUnits.length() > 0) {
            require(rawUnits.length() <= 16) { "Too many ACE units reported." }
            (0 until rawUnits.length()).mapNotNull { rawUnits.optJSONObject(it) }.map { raw -> parseUnit(raw, activeDevice) }
        } else emptyList()
        return AceStatus(ready, printState, hardwareDetected, units)
    }
    private fun parseUnit(raw: JSONObject, activeDevice: Int): AceUnit {
        val aceIndex = raw.optInt("idx")
        val dryerRaw = raw.optJSONObject("dryer_status")
        val dryerActive = dryerRaw?.optString("status")?.equals("drying", ignoreCase = true) == true || dryerRaw?.optBoolean("active") == true
        val dryer = AceDryer(dryerActive, dryerRaw?.optDouble("target_temp")?.takeIf { it.isFinite() }, dryerRaw?.optDouble("remain_time")?.takeIf { it.isFinite() })
        val slots = raw.optJSONArray("slots")
        val lanes = (0 until 4).map { lane ->
            val slot = (0 until (slots?.length() ?: 0)).map { slots!!.optJSONObject(it) }
                .firstOrNull { it?.optInt("index", -1) == lane } ?: slots?.optJSONObject(lane)
            val rawStatus = slot?.optString("status")?.lowercase() ?: ""
            val laneStatus = when {
                rawStatus == "ready" || rawStatus == "loaded" -> if (dryerActive) "drying" else "loaded"
                rawStatus == "empty" -> "empty"
                rawStatus == "busy" || rawStatus == "loading" || rawStatus == "unloading" -> "busy"
                else -> "unknown"
            }
            AceLane(lane, laneStatus, slot?.optString("brand")?.takeIf { it.isNotBlank() },
                (slot?.optString("material")?.takeIf { it.isNotBlank() } ?: slot?.optString("type")?.takeIf { it.isNotBlank() }),
                slot?.optString("color")?.takeIf { it.isNotBlank() } ?: slot?.optString("rgb")?.takeIf { it.isNotBlank() })
        }
        return AceUnit(aceIndex, raw.optBoolean("connected", true), aceIndex == activeDevice,
            raw.optDouble("temp").takeIf { it.isFinite() }, raw.optDouble("humidity").takeIf { it.isFinite() }, dryer, lanes)
    }
    private fun requireReady(status: AceStatus) {
        require(status.hardwareDetected) { "No multiACE hardware was detected." }
        require(status.ready && status.printState in idleStates) { "multiACE controls require an idle, ready printer." }
    }
    private fun requireLane(lane: Int) = require(lane in 0..3) { "Lane must be 0-3." }
    private fun requireAce(ace: Int, status: AceStatus) = require(status.units.any { it.aceIndex == ace }) { "That ACE unit is no longer reported by the printer." }
    fun prepareLoad(request: AceRequest.Load, status: AceStatus): PrinterCommand {
        requireReady(status); requireAce(request.ace, status); requireLane(request.lane)
        return PrinterCommand("Load lane ${request.lane} (ACE ${request.ace})", "printer/gcode/script",
            mapOf("script" to "ACE_LOAD_HEAD HEAD=${request.lane} ACE=${request.ace} SLOT=${request.lane}"), idleStates, aceRequest = request)
    }
    fun prepareUnload(request: AceRequest.Unload, status: AceStatus): PrinterCommand {
        requireReady(status); requireLane(request.lane)
        return PrinterCommand("Unload lane ${request.lane}", "printer/gcode/script",
            mapOf("script" to "ACE_UNLOAD_HEAD HEAD=${request.lane}"), idleStates, aceRequest = request)
    }
    fun prepareUnloadAll(status: AceStatus): PrinterCommand {
        requireReady(status)
        return PrinterCommand("Unload all lanes", "printer/gcode/script", mapOf("script" to "ACE_UNLOAD_ALL_HEADS"), idleStates, aceRequest = AceRequest.UnloadAll)
    }
    fun prepareDryStart(request: AceRequest.DryStart, status: AceStatus): PrinterCommand {
        requireReady(status); requireAce(request.ace, status)
        require(request.temp.length in 1..8 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(request.temp)) { "Enter a plain nonnegative temperature." }
        val temp = BigDecimal(request.temp); require(temp >= BigDecimal.ZERO && temp <= BigDecimal("80")) { "Use a dry temperature from 0 to 80°C." }
        require(request.minutes.length in 1..8 && Regex("[0-9]+").matches(request.minutes)) { "Enter a plain, positive number of minutes." }
        val minutes = request.minutes.toInt(); require(minutes in 1..2880) { "Use a dry duration from 1 to 2880 minutes (48h)." }
        return PrinterCommand("Dry ACE ${request.ace} at ${temp.toPlainString()}°C for ${minutes}m", "printer/gcode/script",
            mapOf("script" to "ACE_DRY ACE=${request.ace} TEMP=${temp.stripTrailingZeros().toPlainString()} DURATION=$minutes"), idleStates, aceRequest = request)
    }
    fun prepareDryStop(request: AceRequest.DryStop, status: AceStatus): PrinterCommand {
        requireReady(status); requireAce(request.ace, status)
        return PrinterCommand("Stop drying ACE ${request.ace}", "printer/gcode/script", mapOf("script" to "ACE_STOP_DRYING ACE=${request.ace}"), idleStates, aceRequest = request)
    }
    fun prepareSwitch(request: AceRequest.Switch, status: AceStatus): PrinterCommand {
        requireReady(status); requireAce(request.ace, status)
        return PrinterCommand("Switch to ACE ${request.ace}", "printer/gcode/script", mapOf("script" to "ACE_SWITCH TARGET=${request.ace} AUTOLOAD=1"), idleStates, aceRequest = request)
    }
}
