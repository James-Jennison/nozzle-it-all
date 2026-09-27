package com.nozzleitall.desktop.prepare

import org.json.JSONObject

/**
 * The printer card's bed type and per-nozzle flow, decided exactly as Snapmaker Orca's sidebar decides them
 * (src/slic3r/GUI/Plater.cpp Sidebar::on_printer_change, bed-type combo; Preset::get_default_bed_type;
 * FlowTypeHelper.cpp). Values are the engine's own keys (curr_bed_type, nozzle_volume_type).
 */
object PrinterSetup {
    data class BedChoice(val value: String, val label: String)

    /** The bed types the printer offers, whether the choice is open, and what it starts on. */
    data class Beds(val choices: List<BedChoice>, val enabled: Boolean, val default: String)

    private val standard = listOf(BedChoice("Cool Plate", "Smooth Cool Plate"), BedChoice("Engineering Plate", "Engineering Plate"),
        BedChoice("High Temp Plate", "Smooth High Temp Plate"), BedChoice("Textured PEI Plate", "Textured PEI Plate"),
        BedChoice("Textured Cool Plate", "Textured Cool Plate"), BedChoice("Supertack Plate", "Cool Plate (SuperTack)"))
    // The U1's own lists (PrintConfig.cpp curr_bed_type: enum_values_u1 / enum_values_ex).
    private val u1 = listOf(BedChoice("Textured PEI Plate", "Textured PEI Plate"), BedChoice("High Temp Plate", "Smooth PEI Plate"),
        BedChoice("Graphic Effect Plate", "Graphic Effect Plate"))
    private val u1Multi = listOf(BedChoice("Cool Plate", "Smooth Cool Plate"), BedChoice("Engineering Plate", "Engineering Plate"),
        BedChoice("High Temp Plate", "Smooth PEI Plate"), BedChoice("Textured PEI Plate", "Textured PEI Plate"),
        BedChoice("Textured Cool Plate", "Textured Cool Plate"), BedChoice("Supertack Plate", "Cool Steel Plate"),
        BedChoice("Graphic Effect Plate", "Graphic Effect Plate"))

    /** Vendor model ids Preset::get_default_bed_type keys on (from the vendors' machine model files). */
    private val modelIds = mapOf("Bambu Lab X1 Carbon" to "BL-P001", "Bambu Lab X1" to "BL-P002", "Bambu Lab X1E" to "C13",
        "Bambu Lab P1P" to "C11", "Bambu Lab P1S" to "C12", "Bambu Lab A1" to "N2S", "Bambu Lab A1 mini" to "N1", "Snapmaker U1" to "SM_U1")

    private val enumOrder = listOf("Default Plate", "Cool Plate", "Engineering Plate", "High Temp Plate", "Textured PEI Plate",
        "Textured Cool Plate", "Graphic Effect Plate", "Supertack Plate") // BedType enum order (btDefault = 0 ...)

    fun beds(machine: JSONObject): Beds {
        val model = machine.optString("printer_model")
        val multi = machine.optString("support_multi_bed_types") == "1" || machine.optBoolean("support_multi_bed_types", false)
        val isU1 = model.contains("Snapmaker", true) && model.contains("U1", true)
        val isBambu = model.startsWith("Bambu Lab")
        val choices = when { isU1 && !multi -> u1; isU1 -> u1Multi; else -> standard }
        // get_default_bed_type: a numeric default_bed_type, else by vendor model id.
        val default = machine.optString("default_bed_type").trim().toIntOrNull()?.takeIf { it != 0 }?.let { enumOrder.getOrNull(it) }
            ?: when (modelIds[model]) { "BL-P001", "BL-P002", "C13" -> "Cool Plate"; "SM_U1" -> "Textured PEI Plate"; else -> "High Temp Plate" }
        val start = if (isU1 && !multi && default !in u1.map { it.value }) "Textured PEI Plate" else default
        return Beds(choices, isBambu || multi || isU1, start)
    }

    /** Whether the printer offers high-flow nozzles (printer_flow_support lists "high_flow"). */
    fun supportsHighFlow(machine: JSONObject): Boolean =
        machine.optJSONArray("printer_flow_support")?.let { a -> (0 until a.length()).any { a.optString(it) == "high_flow" } }
            ?: (machine.optString("printer_flow_support").split(';', ',').any { it.trim() == "high_flow" })

    /** Nozzle count: one entry per nozzle_diameter value. */
    fun nozzles(machine: JSONObject): List<String> =
        machine.optJSONArray("nozzle_diameter")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf(machine.optString("nozzle_diameter", "0.4"))
}
