package net.jamesjennison.klippercompanion

import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONObject

data class LedRequest(val led: String, val percent: String)
data class LedStatus(val led: String, val ready: Boolean, val brightness: Double?)
interface LedReader : AutoCloseable {
    fun leds(): List<String>
    fun ledStatus(led: String): LedStatus
}
object LedControls {
    fun validLed(name: String) = name.length in 1..64 && Regex("[A-Za-z_][A-Za-z0-9_]{0,63}").matches(name)
    fun catalog(result: JSONObject): List<String> {
        val objects = result.getJSONArray("objects")
        require(objects.length() <= 10000) { "Object catalog is too large." }
        return (0 until objects.length()).mapNotNull { objects.opt(it) as? String }
            .filter { it.startsWith("led ") }.map { it.removePrefix("led ") }.filter(::validLed).distinct().sorted()
            .also { require(it.size <= 32) { "Too many lights." } }
    }
    fun parse(led: String, result: JSONObject): LedStatus {
        require(validLed(led)) { "Unsupported light." }
        val status = result.getJSONObject("status")
        val ready = status.optJSONObject("webhooks")?.optString("state") == "ready"
        val white = status.optJSONObject("led $led")?.optJSONArray("color_data")?.optJSONArray(0)
            ?.optDouble(3)?.takeIf { it.isFinite() && it in 0.0..1.0 }
        return LedStatus(led, ready, white)
    }
    // Lights are cosmetic and safe to change in any print state (unlike heaters/fans/macros),
    // so this only requires a ready printer, not an idle one.
    fun prepare(request: LedRequest, status: LedStatus): PrinterCommand {
        require(validLed(request.led) && request.led == status.led) { "Light identity changed." }
        require(status.ready) { "Light controls require a ready printer." }
        require(status.brightness?.isFinite() == true) { "Light reading unavailable." }
        require(request.percent.length in 1..24 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(request.percent)) { "Enter a plain percentage from 0 to 100." }
        val value = BigDecimal(request.percent)
        require(value >= BigDecimal.ZERO && value <= BigDecimal("100")) { "Use a percentage from 0 to 100." }
        val fraction = value.divide(BigDecimal("100")).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
        // SYNC=0 per Klipper's led.py docs: avoids resetting the idle timeout when the
        // printer isn't already moving, which SYNC=1 (the gcode default) would do.
        val script = "SET_LED LED=${request.led} WHITE=${fraction.toPlainString()} SYNC=0"
        return PrinterCommand("Set ${request.led} light to ${value.stripTrailingZeros().toPlainString()}%", "printer/gcode/script", mapOf("script" to script), emptySet(), ledRequest = request)
    }
}
