package com.nozzleitall.desktop.prepare

import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What Orca's settings tabs do while you edit: which settings are greyed out or hidden, which values it forces, what it tells you
 * and what it asks, as the engine reports them (`nozzle-engine --option-states` and `--config-checks`, the engine's
 * nozzle/bridge/android/option_states.cpp, ported from Orca's ConfigManipulation and the printer / filament tabs). The screens apply
 * them, so a setting that does nothing in Orca does nothing here either.
 */
object OptionRules {
    data class State(val enabled: Boolean = true, val visible: Boolean = true)
    /** A row of the filament Setting Overrides page: a checkbox ([checked] = the filament has its own value) and the field it unlocks. */
    data class Override(val checkboxEnabled: Boolean, val checked: Boolean, val fieldEnabled: Boolean, val visible: Boolean, val inherited: String)
    data class Notice(val id: String, val message: String, val changes: Map<String, String>)
    data class Conflict(val id: String, val message: String, val choices: List<Pair<String, Map<String, String>>>)
    data class Result(val states: Map<String, State>, val forced: Map<String, String>, val overrides: Map<String, Override>,
                      val choices: Map<String, List<Pair<String, String>>>, val notices: List<Notice>, val conflicts: List<Conflict>) {
        fun state(key: String) = states[key] ?: State()
    }

    class EngineError(message: String) : Exception(message)

    private fun call(mode: String, profiles: List<File>, overrides: Map<String, String>, extra: JSONObject): Result {
        val engine = SliceEngine.locateEngine() ?: throw EngineError("The slicing engine isn't installed with this copy of Nozzle It All.")
        val req = File.createTempFile("nozzle-rules", ".json").apply { deleteOnExit() }
        try {
            req.writeText(extra.put("profiles", org.json.JSONArray(profiles.map { it.absolutePath })).put("overrides", JSONObject(overrides)).toString())
            val p = ProcessBuilder(engine.absolutePath, mode, req.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); throw EngineError("The settings rules took too long.") }
            val j = runCatching { JSONObject(out) }.getOrElse { throw EngineError("This engine doesn't have the settings rules.") }
            j.optString("error").takeIf { it.isNotBlank() }?.let { throw EngineError(it) }
            return parse(j)
        } finally { req.delete() }
    }

    fun parse(j: JSONObject): Result {
        fun strings(o: JSONObject?) = o?.keys()?.asSequence()?.associateWith { o.optString(it) } ?: emptyMap()
        val states = j.optJSONObject("states")?.let { s -> s.keys().asSequence().associateWith { s.getJSONObject(it).let { st -> State(st.optBoolean("enabled", true), st.optBoolean("visible", true)) } } } ?: emptyMap()
        val overrides = j.optJSONObject("overrides")?.let { s -> s.keys().asSequence().associateWith { s.getJSONObject(it).let { o ->
            Override(o.optBoolean("checkboxEnabled", true), o.optBoolean("checked"), o.optBoolean("fieldEnabled"), o.optBoolean("visible", true), o.optString("inherited")) } } } ?: emptyMap()
        val choices = j.optJSONObject("choices")?.let { s -> s.keys().asSequence().associateWith { k -> s.getJSONArray(k).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getString("value") to it.getString("label") } } } } } ?: emptyMap()
        val notices = j.optJSONArray("notices")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Notice(it.getString("id"), it.getString("message"), strings(it.optJSONObject("changes"))) } } ?: emptyList()
        val conflicts = j.optJSONArray("conflicts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { c ->
            Conflict(c.getString("id"), c.getString("message"), c.getJSONArray("choices").let { ch -> (0 until ch.length()).map { ch.getJSONObject(it) }.map { x -> x.getString("label") to strings(x.optJSONObject("changes")) } }) } } ?: emptyList()
        return Result(states, strings(j.optJSONObject("forced")), overrides, choices, notices, conflicts)
    }

    /**
     * Every printer, filament and print rule for the configuration [profiles] + [overrides] describe. [flowVariant] picks the extruder
     * variant the filament Setting Overrides page shows; [printerName] and [printerModelId] are the profile's, for the rules that name a printer.
     */
    fun states(profiles: List<File>, overrides: Map<String, String>, scope: String = "all", flowVariant: Int = 0, printerName: String = "", printerModelId: String = "",
               filamentCount: Int = 1): Result =
        call("--option-states", profiles, overrides, JSONObject().put("scope", scope).put("flowVariant", flowVariant).put("printerName", printerName)
            .put("printerModelId", printerModelId).put("filamentCount", filamentCount))

    /**
     * Orca's checks when a print setting changes: values it resets (notices) and combinations it asks about (conflicts).
     * [variableLayer] says some object has a variable layer height or height ranges, which is what the mixed sub-layer warning needs.
     */
    fun checks(profiles: List<File>, overrides: Map<String, String>, variableLayer: Boolean = false, filamentCount: Int = 1, printerName: String = ""): Result =
        call("--config-checks", profiles, overrides, JSONObject().put("variableLayer", variableLayer).put("filamentCount", filamentCount).put("printerName", printerName))
}
