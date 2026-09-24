package net.jamesjennison.klippercompanion

import org.json.JSONObject

data class ConsoleEntry(val time:Double,val type:String,val message:String) {
    val error:Boolean get()=type=="response" && (message.trimStart().startsWith("!!") || message.contains("error",ignoreCase=true))
}
data class ConsoleBatch(val entries:List<ConsoleEntry>,val truncated:Boolean=false)
/** Read-only capability: intentionally no script/command method. Command dispatch
 * (see [ConsoleCommand]) goes through the shared [PrinterService.command], not this reader. */
interface ConsoleReader:AutoCloseable { fun console():ConsoleBatch }
/** Builds a validated raw-gcode command from free-typed console text. Local validation
 * only - there is no live server state to re-check, unlike heater/fan/macro/speed-flow. */
object ConsoleCommand {
    const val MAX_LENGTH=256
    fun prepare(text:String):PrinterCommand {
        val trimmed=text.trim()
        require(trimmed.isNotEmpty()){"Enter a command."}
        require(trimmed.length<=MAX_LENGTH){"Use at most $MAX_LENGTH characters."}
        // A newline would let one line of visible text silently run as several gcode
        // commands once sent, so control characters (incl. \n/\r) are rejected outright.
        require(trimmed.none{it.isISOControl()}){"Remove newlines and control characters; enter one command."}
        return PrinterCommand("Send: $trimmed","printer/gcode/script",mapOf("script" to trimmed))
    }
}
object ConsoleLog {
    const val MAX_ENTRIES=200
    const val MAX_MESSAGE=2048
    const val MAX_COPY=65536
    fun parse(result:JSONObject):ConsoleBatch {
        val rows=result.getJSONArray("gcode_store")
        var truncated=rows.length()>MAX_ENTRIES
        val entries=(maxOf(0,rows.length()-MAX_ENTRIES) until rows.length()).map {i->
            val row=rows.getJSONObject(i)
            val time=row.getDouble("time");require(time.isFinite() && time>=0 && time<=253402300799.0){"Invalid console timestamp."}
            val type=row.getString("type");require(type in setOf("command","response")){"Invalid console message type."}
            val message=row.getString("message");if(message.length>MAX_MESSAGE)truncated=true
            ConsoleEntry(time,type,message.take(MAX_MESSAGE).filter {it=='\n'||it=='\t'||!it.isISOControl()})
        }
        return ConsoleBatch(entries,truncated)
    }
    fun filter(entries:List<ConsoleEntry>,query:String,errorsOnly:Boolean)=entries.filter{(!errorsOnly||it.error)&&it.message.contains(query.take(128),ignoreCase=true)}.asReversed()
    fun line(entry:ConsoleEntry):String {
        val stamp=java.time.Instant.ofEpochMilli((entry.time*1000).toLong()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        return "$stamp · ${entry.type}\n${entry.message}"
    }
    fun copy(entries:List<ConsoleEntry>)=entries.joinToString("\n\n",transform=::line).take(MAX_COPY)
}
