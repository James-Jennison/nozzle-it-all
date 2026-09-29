// Parsing rules ported from OrcaSlicer's QidiPrinterAgent (src/slic3r/Utils/QidiPrinterAgent.cpp at 5298e49d,
// fetch_slot_info / fetch_filament_dict / parse_ini_section / parse_filament_sections; AGPL-3.0), with the flat
// save_variables shape QIDIStudio reads (src/slic3r/GUI/QDSDeviceManager.cpp 798-870).
package net.jamesjennison.klippercompanion

import org.json.JSONObject

/**
 * A Qidi printer's filament dictionary, Moonraker's `config/officiall_filas_list.cfg` (the file name is Qidi's): the Box
 * stores each slot's filament and colour as numbers into it. `[colordict]` maps colour numbers to colours, `[filaN]`
 * sections give filament N's name (`filament = PLA`).
 */
data class QidiFilamentDictionary(val filaments: Map<Int, String>, val colors: Map<Int, String>) {
    companion object {
        const val CONFIG_FILE = "officiall_filas_list.cfg"

        fun parse(text: String): QidiFilamentDictionary {
            val colors = HashMap<Int, String>(); val filaments = HashMap<Int, String>()
            var section = ""
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("[")) { section = line; continue }
                if (line.isEmpty() || line[0] == '#' || line[0] == ';') continue
                val eq = line.indexOf('='); if (eq < 0) continue
                val key = line.substring(0, eq).trim(); val value = line.substring(eq + 1).trim()
                if (section == "[colordict]") key.toIntOrNull()?.let { colors[it] = value }
                else if (section.startsWith("[fila") && section.endsWith("]") && key == "filament")
                    section.substring(5, section.length - 1).toIntOrNull()?.takeIf { it > 0 }?.let { filaments[it] = value }
            }
            return QidiFilamentDictionary(filaments, colors)
        }
    }
}

/**
 * The Qidi Box (Q2, Q2C, X-Plus 4, X-Max 4, X-Plus 5) read over Moonraker. Pure rules, no I/O. Up to four Boxes of four
 * slots (0-15, box b = slot / 4 + 1); `save_variables` holds `box_count` and each slot's `filament_slot<i>`,
 * `color_slot<i>` and `vendor_slot<i>` numbers into the [QidiFilamentDictionary], and `last_load_slot` ("slotN", the one
 * feeding the nozzle); `box_stepper slot<i>.runout_button` is 0 when the slot holds filament (null: unknown).
 *
 * A slot's [FilamentSlot.tool] is its physical slot number, not a T number: which slot each T uses is chosen per print
 * (`value_t<n>`), as with the Bambu AMS.
 */
object QidiBox {
    const val SLOTS_PER_BOX = 4
    const val MAX_BOXES = 4

    /** `printer/objects/query` arguments for the Box: the saved variables and each slot's runout sensor. */
    val QUERY_OBJECTS: Map<String, String> = mapOf("save_variables" to "variables") +
        (0 until SLOTS_PER_BOX * MAX_BOXES).associate { "box_stepper slot$it" to "runout_button" }

    /** Whether a query's `status` came from a printer with a Box (its slot sensors exist). */
    fun present(status: JSONObject?): Boolean = status?.has("box_stepper slot0") == true

    /**
     * The Box slots from a query's [status] and the printer's [dictionary] (null when it couldn't be read: slots then show
     * as loaded without a name or colour). Null when the printer has no Box.
     */
    fun slots(status: JSONObject?, dictionary: QidiFilamentDictionary?): List<FilamentSlot>? {
        if (!present(status)) return null
        val saved = status!!.optJSONObject("save_variables")
        // Klipper nests the values under "variables" (Orca reads that); QIDIStudio reads them flat.
        val vars = saved?.optJSONObject("variables") ?: saved ?: JSONObject()
        fun int(key: String): Int? = when (val v = vars.opt(key)) { is Number -> v.toInt(); is String -> v.trim().toIntOrNull(); else -> null }
        val boxes = (int("box_count") ?: 1).coerceIn(0, MAX_BOXES)
        val active = (vars.opt("last_load_slot") as? String)?.trim()?.removePrefix("slot")?.toIntOrNull()
        return (0 until boxes * SLOTS_PER_BOX).map { i ->
            val sensor = status.optJSONObject("box_stepper slot$i")?.opt("runout_button")
            val loaded = (sensor as? Number)?.toInt() == 0
            val material = if (!loaded) null else int("filament_slot$i")?.let { dictionary?.filaments?.get(it) }?.trim()?.uppercase()?.ifEmpty { null }
                ?: "LOADED (TYPE NOT REPORTED)"
            val color = if (!loaded) null else int("color_slot$i")?.let { dictionary?.colors?.get(it) }?.let(FilamentLanes::normalizeColor)
            FilamentSlot(i, if (loaded) material else null, color, vendor = if (loaded && int("vendor_slot$i") == 1) "QIDI" else null,
                active = loaded && active == i, name = "Box ${i / SLOTS_PER_BOX + 1} · slot ${i % SLOTS_PER_BOX + 1}")
        }
    }
}
