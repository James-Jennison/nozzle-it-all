package net.jamesjennison.klippercompanion

import java.math.BigDecimal
import org.json.JSONObject

data class HeaterRequest(val heater: String, val value: String)
data class HeaterStatus(
    val heater: String, val activeTool: String, val ready: Boolean, val printState: String,
    val temperature: Double?, val target: Double?, val minimum: Double?, val maximum: Double?,
    val standardCommand: Boolean
)
interface HeaterReader: AutoCloseable {
    fun heaterStatus(heater: String): HeaterStatus
}
object HeaterControls {
    val idleStates=setOf("standby","complete","cancelled")
    fun validHeater(name: String) = name=="heater_bed" || (name.length<=32 && Regex("extruder[0-9]*").matches(name))
    fun parse(heater: String, result: JSONObject): HeaterStatus {
        require(validHeater(heater)) {"Unsupported heater."}
        val status=result.getJSONObject("status")
        val settings=status.optJSONObject("configfile")?.optJSONObject("settings")
        val config=settings?.optJSONObject(heater)
        val reading=status.optJSONObject(heater)
        fun finite(obj:JSONObject?,field:String)=obj?.optDouble(field)?.takeIf{it.isFinite()}
        return HeaterStatus(heater,Moonraker.activeExtruder(status),status.optJSONObject("webhooks")?.optString("state")=="ready",
            status.optJSONObject("print_stats")?.optString("state","unknown")?:"unknown",
            finite(reading,"temperature"),finite(reading,"target"),finite(config,"min_temp"),finite(config,"max_temp"),
            settings!=null && settings.keys().asSequence().none{it.equals("gcode_macro SET_HEATER_TEMPERATURE",true)})
    }
    fun prepare(request:HeaterRequest,status:HeaterStatus):PrinterCommand {
        require(validHeater(request.heater) && request.heater==status.heater) {"Heater identity changed."}
        require(status.ready && status.printState in idleStates) {"Heating controls require an idle, ready printer."}
        require(request.heater=="heater_bed" || request.heater==status.activeTool) {"The selected nozzle is no longer the active tool."}
        require(status.standardCommand) {"The standard heater command cannot be verified."}
        require(status.temperature?.isFinite()==true && status.target?.isFinite()==true) {"Heater readings unavailable."}
        val min=status.minimum;val max=status.maximum
        require(min!=null && max!=null && min.isFinite() && max.isFinite() && min<max && max in 1.0..500.0) {"Configured heater limits unavailable."}
        require(request.value.length in 1..24 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(request.value)) {"Enter a plain nonnegative temperature."}
        val value=BigDecimal(request.value)
        require(value.signum()==0 || (value>=BigDecimal.valueOf(min) && value<=BigDecimal.valueOf(max))) {"Temperature is outside the configured heater limits."}
        val normalized=value.stripTrailingZeros().toPlainString()
        return PrinterCommand("Set ${request.heater} to $normalized°C","printer/gcode/script",
            mapOf("script" to "SET_HEATER_TEMPERATURE HEATER=${request.heater} TARGET=$normalized"),idleStates,request)
    }
}
