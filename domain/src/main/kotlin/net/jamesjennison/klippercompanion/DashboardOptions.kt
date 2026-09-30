package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.json.JSONArray

/** Local preferences contain only known choices. Missing cards from older layouts are appended. */
data class DashboardOptions(val mode:String="Dark", val accent:String="Violet", val order:List<String> = cards, val hidden:Set<String> = emptySet()) {
    fun encode():String = JSONObject().put("mode",mode).put("accent",accent).put("order",JSONArray(order)).put("hidden",JSONArray(hidden.toList())).toString()
    fun move(card:String, delta:Int):DashboardOptions {
        val items=order.toMutableList(); val from=items.indexOf(card); val to=from+delta
        if(from !in items.indices || to !in items.indices) return this
        items.add(to,items.removeAt(from)); return copy(order=items)
    }
    companion object {
        val cards=listOf("Camera","Print","Temperatures","Quick tools")
        val modes=listOf("Dark","Light","System")
        val accents=listOf("Violet","Mint","Blue","Lavender")
        fun decode(raw:String?):DashboardOptions = runCatching {
            require(raw!=null && raw.length<=4096)
            val obj=JSONObject(raw)
            fun list(key:String):List<String> { val array=obj.optJSONArray(key)?:return emptyList(); return (0 until array.length()).map {array.optString(it)}.filter {it in cards}.distinct() }
            DashboardOptions(obj.optString("mode").takeIf {it in modes}?:"Dark", obj.optString("accent").takeIf {it in accents}?:"Violet", (list("order")+cards).distinct(),list("hidden").toSet())
        }.getOrDefault(DashboardOptions())
    }
}
