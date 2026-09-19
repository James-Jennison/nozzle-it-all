package net.jamesjennison.klippercompanion

import org.json.JSONObject

data class ToolRequest(val tool: String)
data class ToolStatus(val tools: List<String>, val activeTool: String, val ready: Boolean, val printState: String)
interface ToolReader: AutoCloseable {
    fun toolStatus(): ToolStatus
}
object ToolControls {
    val idleStates=setOf("standby","complete","cancelled")
    fun validTool(name:String)=name.length<=32 && Regex("extruder[0-9]*").matches(name)
    fun toolIndex(name:String)=if(name=="extruder")0 else name.removePrefix("extruder").toInt()
    fun parse(result:JSONObject):ToolStatus {
        val status=result.getJSONObject("status")
        val settings=status.optJSONObject("configfile")?.optJSONObject("settings")
        val tools=settings?.keys()?.asSequence()?.filter(::validTool)?.toList()?.sortedBy(::toolIndex)?:emptyList()
        return ToolStatus(tools,Moonraker.activeExtruder(status),status.optJSONObject("webhooks")?.optString("state")=="ready",
            status.optJSONObject("print_stats")?.optString("state","unknown")?:"unknown")
    }
    fun prepare(request:ToolRequest,status:ToolStatus):PrinterCommand {
        require(validTool(request.tool) && request.tool in status.tools){"Unsupported tool."}
        require(status.ready && status.printState in idleStates){"Tool switching requires an idle, ready printer."}
        require(request.tool!=status.activeTool){"This tool is already active."}
        val index=toolIndex(request.tool)
        return PrinterCommand("Switch to ${request.tool} (T$index)","printer/gcode/script",mapOf("script" to "T$index"),idleStates,toolRequest=request)
    }
}
