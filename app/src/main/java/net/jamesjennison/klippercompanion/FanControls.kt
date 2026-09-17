package net.jamesjennison.klippercompanion

import java.math.BigDecimal
import org.json.JSONObject

data class FanRequest(val fan:String,val percent:String,val activeTool:String)
data class FanStatus(val fan:String,val activeTool:String,val ready:Boolean,val printState:String,val speed:Double?,val configured:Boolean,val noMacroOverride:Boolean)
interface FanReader:AutoCloseable {
    fun fans():List<String>
    fun fanStatus(fan:String):FanStatus
}
object FanControls {
    val idleStates=setOf("standby","complete","cancelled")
    fun validFan(name:String)=name=="fan" || Regex("fan_generic [A-Za-z_][A-Za-z0-9_]{0,63}").matches(name)
    fun catalog(result:JSONObject):List<String> {
        val objects=result.getJSONArray("objects")
        require(objects.length()<=10000){"Fan catalog is too large."}
        return (0 until objects.length()).mapNotNull{objects.opt(it) as? String}.filter(::validFan).distinct().sorted().also {
            require(it.size<=64){"Too many manual fans."}
        }
    }
    fun parse(fan:String,result:JSONObject):FanStatus {
        require(validFan(fan)){"Unsupported manual fan."}
        val status=result.getJSONObject("status")
        val settings=status.optJSONObject("configfile")?.optJSONObject("settings")
        val commands=if(fan=="fan")setOf("M106","M107") else setOf("SET_FAN_SPEED")
        val unmodified=settings!=null && settings.keys().asSequence().none { key -> commands.any {key.equals("gcode_macro $it",true)} }
        return FanStatus(fan,Moonraker.activeExtruder(status),status.optJSONObject("webhooks")?.optString("state")=="ready",
            status.optJSONObject("print_stats")?.optString("state","unknown")?:"unknown",
            status.optJSONObject(fan)?.optDouble("speed")?.takeIf{it.isFinite() && it in 0.0..1.0},settings?.optJSONObject(fan)!=null,unmodified)
    }
    fun prepare(request:FanRequest,status:FanStatus):PrinterCommand {
        require(validFan(request.fan) && request.fan==status.fan){"Fan identity changed."}
        require(status.ready && status.printState in idleStates){"Fan controls require an idle, ready printer."}
        require(request.activeTool.isNotEmpty() && request.activeTool==status.activeTool){"Active tool changed. Review the fan selection again."}
        require(status.configured && status.noMacroOverride){"The configured fan command cannot be verified."}
        require(status.speed?.isFinite()==true && status.speed in 0.0..1.0){"Fan reading unavailable."}
        require(request.percent.length in 1..24 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(request.percent)){"Enter a plain percentage from 0 to 100."}
        val value=BigDecimal(request.percent)
        require(value>=BigDecimal.ZERO && value<=BigDecimal("100")){"Use a percentage from 0 to 100."}
        fun plain(n:BigDecimal)=n.stripTrailingZeros().toPlainString()
        val script=if(request.fan=="fan")"M106 S${plain(value*BigDecimal("2.55"))}" else "SET_FAN_SPEED FAN=${request.fan.removePrefix("fan_generic ")} SPEED=${plain(value.movePointLeft(2))}"
        return PrinterCommand("Set ${request.fan} to ${plain(value)}%","printer/gcode/script",mapOf("script" to script),idleStates,fanRequest=request)
    }
}
