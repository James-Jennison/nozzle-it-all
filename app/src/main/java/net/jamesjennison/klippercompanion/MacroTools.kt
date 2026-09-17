package net.jamesjennison.klippercompanion

import org.json.JSONObject
import java.math.BigDecimal

data class MacroOptions(val favorite: Boolean=false, val group: String="", val parameters: String="")
data class MacroParameter(val name: String, val minimum: BigDecimal, val maximum: BigDecimal, val default: BigDecimal) {
    init {
        require(Regex("[A-Z][A-Z0-9_]{0,31}").matches(name)) { "Invalid parameter name." }
        listOf(minimum,maximum,default).forEach { MacroTools.validateDecimal(it) }
        require(minimum<=default && default<=maximum) { "Default must be between minimum and maximum." }
    }
}
object MacroTools {
    internal fun validateDecimal(value: BigDecimal) {
        // Bound expansion before rendering exponent-form definitions as plain input.
        require(value.scale() in -6..30 && value.precision()<=32 && value.abs()<=BigDecimal("1000000")) { "Use representable numbers within ±1000000." }
        require(value.stripTrailingZeros().toPlainString().length<=32) { "Use at most 32 characters per plain number." }
    }
    fun definitions(text: String): List<MacroParameter> {
        require(text.length<=4000) { "Parameter definitions are too long." }
        val rows=text.lines().filter { it.isNotBlank() }
        require(rows.size<=12) { "Use at most 12 numeric parameters." }
        return rows.map { row ->
            val pair=row.trim().split('=');require(pair.size==2 && Regex("[A-Z][A-Z0-9_]{0,31}").matches(pair[0])) { "Use NAME=min,max,default, one per line." }
            val numbers=pair[1].split(',').map { it.trim().toBigDecimalOrNull() }
            require(numbers.size==3 && numbers.all { it!=null }) { "Use three finite numbers within ±1000000." }
            val (low,high,default)=numbers.map { it!! };require(low<=default && default<=high) { "Default must be between minimum and maximum." }
            MacroParameter(pair[0],low,high,default)
        }.also { require(it.map { p->p.name }.distinct().size==it.size) { "Parameter names must be unique." } }
    }
    fun command(name: String, definitions: List<MacroParameter>, values: Map<String,String>): PrinterCommand {
        val base=Moonraker.macro(name)
        require(definitions.size<=12 && definitions.map {it.name}.distinct().size==definitions.size) { "Use at most 12 unique parameters." }
        require(values.keys==definitions.map { it.name }.toSet()) { "Parameter fields changed. Reopen the form." }
        val arguments=definitions.map { p ->
            val raw=values.getValue(p.name)
            require(raw.length<=32 && Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)").matches(raw)) { "${p.name}: enter a plain number." }
            val n=BigDecimal(raw);require(n>=p.minimum && n<=p.maximum) { "${p.name}: use ${p.minimum.toPlainString()} to ${p.maximum.toPlainString()}." }
            "${p.name}=${n.stripTrailingZeros().toPlainString()}"
        }
        return base.copy(arguments=mapOf("script" to (listOf(name)+arguments).joinToString(" ")),allowedStates=setOf("standby","complete","cancelled","error"))
    }
    fun decode(raw: String): Map<String,MacroOptions> = runCatching {
        val obj=JSONObject(raw);obj.keys().asSequence().take(500).associateWith { key -> val p=obj.getJSONObject(key);MacroOptions(p.optBoolean("favorite"),p.optString("group").take(40),p.optString("parameters").take(4000)) }
    }.getOrDefault(emptyMap())
    fun encode(values: Map<String,MacroOptions>)=JSONObject().apply { values.forEach { (k,v)->put(k,JSONObject().put("favorite",v.favorite).put("group",v.group).put("parameters",v.parameters)) } }.toString()
}
