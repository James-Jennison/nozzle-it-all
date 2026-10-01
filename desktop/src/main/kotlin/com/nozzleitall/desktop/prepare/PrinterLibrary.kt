package com.nozzleitall.desktop.prepare

import org.json.JSONObject
import java.io.File

/**
 * A printer model's whole profile family from its own slicer (scripts/bundle_printer_library.py; the Snapmaker U1's from
 * Snapmaker Orca at the engine pin): one machine per nozzle size, the process presets and filaments compatible with
 * each, and each machine's defaults. Prepare picks the machine by nozzle diameter and the process from the list, as
 * Snapmaker Orca's printer card and process dropdown do. Printers without a library use their single bundled pack.
 */
class PrinterLibrary private constructor(val profileId: String, val machines: List<Machine>, val processes: Map<String, Process>,
                                         val filaments: Map<String, FilamentLibrary.Entry>) {
    data class Machine(val id: String, val name: String, val nozzle: String, val defaultProcess: String?, val defaultFilament: String?,
                       val processes: List<String>, val filaments: List<String>)
    /** [madeBy] is set for a preset Nozzle It All made where the vendor ships none (engine/profiles/derived). */
    data class Process(val id: String, val name: String, val label: String, val layerHeight: String, val madeBy: String? = null) {
        val displayLabel: String get() = madeBy?.let { "$label ($it)" } ?: label
    }

    fun machineFor(nozzle: String?): Machine =
        machines.firstOrNull { it.nozzle == nozzle } ?: machines.firstOrNull { it.nozzle == "0.4" } ?: machines.first()

    fun processesFor(m: Machine): List<Process> = m.processes.mapNotNull { processes[it] }
    fun filamentsFor(m: Machine): List<FilamentLibrary.Entry> = m.filaments.mapNotNull { filaments[it] }

    private fun resource(kind: String, id: String): String? =
        PrinterLibrary::class.java.getResourceAsStream("/library/$profileId/$kind/$id.json")?.readBytes()?.decodeToString()

    fun json(kind: String, id: String): JSONObject? = resource(kind, id)?.let { JSONObject(it) }

    /** A profile folder for the engine: this machine, this process, and the machine's default filament. */
    fun materialize(cache: File, m: Machine, processId: String?): File {
        val p = processId?.takeIf { it in m.processes } ?: m.defaultProcess ?: m.processes.first()
        val f = m.defaultFilament ?: m.filaments.first()
        val dir = File(cache, "profiles/$profileId@${m.id}@$p").apply { mkdirs() }
        listOf("machine.json" to resource("machine", m.id), "process.json" to resource("process", p), "filament.json" to resource("filament", f))
            .forEach { (name, text) -> text?.let { t -> File(dir, name).takeIf { !it.isFile || it.readText() != t }?.writeText(t) } }
        return dir
    }

    companion object {
        private val cache = HashMap<String, PrinterLibrary?>()

        /** The library for printer profile [profileId], or null when it has none. */
        fun of(profileId: String): PrinterLibrary? = synchronized(cache) {
            cache.getOrPut(profileId) {
                val text = PrinterLibrary::class.java.getResourceAsStream("/library/$profileId/index.json")?.readBytes()?.decodeToString() ?: return@getOrPut null
                val o = JSONObject(text)
                fun ids(j: JSONObject, k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                val machines = o.getJSONArray("machines").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
                    Machine(it.getString("id"), it.getString("name"), it.optString("nozzle"), it.optString("default_process").ifBlank { null },
                        it.optString("default_filament").ifBlank { null }, ids(it, "processes"), ids(it, "filaments")) } }
                val processes = o.getJSONArray("processes").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associate {
                    it.getString("id") to Process(it.getString("id"), it.getString("name"), it.getString("label"), it.optString("layer_height"),
                        it.optString("made_by").ifBlank { null }) } }
                val filaments = o.getJSONArray("filaments").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associate {
                    it.getString("id") to FilamentLibrary.Entry(it.getString("id"), it.getString("name"), it.optString("vendor"), it.optString("type"), it.optString("family")) } }
                PrinterLibrary(profileId, machines, processes, filaments).takeIf { machines.isNotEmpty() }
            }
        }
    }
}
