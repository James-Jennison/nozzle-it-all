package net.jamesjennison.klippercompanion

import org.json.JSONObject

data class MacroOptions(val favorite: Boolean=false, val group: String="", val parameters: String="")
data class MacroParameter(val name: String, val minimum: Double, val maximum: Double, val default: Double)
object MacroTools {
    fun definitions(text: String): List<MacroParameter> {
        require(text.length<=4000) { "Parameter definitions are too long." }
        val rows=text.lines().filter { it.isNotBlank() }
        require(rows.size<=12) { "Use at most 12 numeric parameters." }
        return rows.map { row ->
            val pair=row.trim().split('=');require(pair.size==2 && Regex("[A-Z][A-Z0-9_]{0,31}").matches(pair[0])) { "Use NAME=min,max,default, one per line." }
            val numbers=pair[1].split(',').map { it.trim().toDoubleOrNull() }
            require(numbers.size==3 && numbers.all { it!=null && it.isFinite() && kotlin.math.abs(it)<=1_000_000 }) { "Use three finite numbers within ±1000000." }
            val (low,high,default)=numbers.map { it!! };require(low<=default && default<=high) { "Default must be between minimum and maximum." }
            MacroParameter(pair[0],low,high,default)
        }.also { require(it.map { p->p.name }.distinct().size==it.size) { "Parameter names must be unique." } }
    }
    fun command(name: String, definitions: List<MacroParameter>, values: Map<String,String>): PrinterCommand {
        val base=Moonraker.macro(name)
        require(values.keys==definitions.map { it.name }.toSet()) { "Parameter fields changed. Reopen the form." }
        val arguments=definitions.map { p ->
            val raw=values.getValue(p.name)
            require(raw.length<=32 && Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)").matches(raw)) { "${p.name}: enter a plain number." }
            val n=raw.toDoubleOrNull();require(n!=null && n.isFinite() && n>=p.minimum && n<=p.maximum) { "${p.name}: use ${p.minimum} to ${p.maximum}." }
            "${p.name}=${java.math.BigDecimal(raw).stripTrailingZeros().toPlainString()}"
        }
        return base.copy(arguments=mapOf("script" to (listOf(name)+arguments).joinToString(" ")),allowedStates=setOf("standby","complete","cancelled","error"))
    }
    fun decode(raw: String): Map<String,MacroOptions> = runCatching {
        val obj=JSONObject(raw);obj.keys().asSequence().take(500).associateWith { key -> val p=obj.getJSONObject(key);MacroOptions(p.optBoolean("favorite"),p.optString("group").take(40),p.optString("parameters").take(4000)) }
    }.getOrDefault(emptyMap())
    fun encode(values: Map<String,MacroOptions>)=JSONObject().apply { values.forEach { (k,v)->put(k,JSONObject().put("favorite",v.favorite).put("group",v.group).put("parameters",v.parameters)) } }.toString()
}
