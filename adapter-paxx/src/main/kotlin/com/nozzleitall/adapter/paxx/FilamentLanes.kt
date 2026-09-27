package com.nozzleitall.adapter.paxx

import com.nozzleitall.printer.Material
import com.nozzleitall.printer.PrinterStatus
import com.nozzleitall.printer.Toolhead
import org.json.JSONArray
import org.json.JSONObject

/**
 * The filament lanes of a Klipper printer's filament changer (AFC units such as the Elegoo CANVAS on COSMOS, Box Turtle;
 * Happy Hare MMUs), as material slots. Pure rules, no I/O.
 *
 * Ported from OrcaSlicer's MoonrakerPrinterAgent (src/slic3r/Utils/MoonrakerPrinterAgent.cpp at 824b216f,
 * fetch_filament_info, fetch_moonraker_filament_data, fetch_hh_filament_info; AGPL-3.0): Moonraker's `lane_data`
 * database namespace first (written by AFC, AFC_lane.py send_lane_data, and recent Happy Hare), then Happy Hare's `mmu`
 * object. A lane's slot is the tool it is mapped to (`lane`, "0" for T0), so the slots line up with the T numbers the
 * sliced file uses; lanes with no tool are skipped, as upstream does.
 */
object FilamentLanes {
    /** Happy Hare's fields, queried with the rest of the status in one call. */
    const val MMU_FIELDS = "num_gates,gate_status,gate_material,gate_color,gate_temperature,tool"

    data class Lane(val tool: Int, val material: String, val colorHex: String?, val nozzleTemp: Int?, val name: String? = null) {
        val loaded get() = material.isNotEmpty()
    }

    /** `server/database/item?namespace=lane_data`'s result (its "value" object). Null when there are no lanes. */
    fun fromLaneData(result: JSONObject?): List<Lane>? {
        val value = result?.optJSONObject("value") ?: return null
        val lanes = value.keySet().sorted().mapNotNull { key ->
            val lane = value.optJSONObject(key) ?: return@mapNotNull null
            val tool = (lane.opt("lane") as? String)?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            Lane(tool, lane.string("material"), U1Protocol.normalizeColor(lane.string("color")), (lane.opt("nozzle_temp") as? Number)?.toInt()?.takeIf { it > 0 }, key)
        }.distinctBy { it.tool }
        return lanes.takeIf { it.isNotEmpty() }
    }

    /** Happy Hare's `mmu` status object. Only available gates (status 1 or 2) with a material, as upstream. */
    fun fromHappyHare(mmu: JSONObject?): List<Lane>? {
        val gates = (mmu?.opt("num_gates") as? Number)?.toInt()?.takeIf { it > 0 } ?: return null
        val status = mmu.optJSONArray("gate_status"); val material = mmu.optJSONArray("gate_material")
        val color = mmu.optJSONArray("gate_color"); val temp = mmu.optJSONArray("gate_temperature")
        if (status == null || material == null || color == null || temp == null) return null
        val lanes = (0 until minOf(gates, 64)).mapNotNull { g ->
            if (((status.opt(g) as? Number)?.toInt() ?: 0) <= 0) return@mapNotNull null
            val type = material.string(g).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Lane(g, type, U1Protocol.normalizeColor(color.string(g)), (temp.opt(g) as? Number)?.toInt()?.takeIf { it > 0 })
        }
        return lanes.takeIf { it.isNotEmpty() }
    }

    /**
     * [status] with its toolheads replaced by the lanes, one slot per tool (T0 is slot 1). The printer has one nozzle, so
     * its temperature and diameter go on the lane feeding it: AFC's current lane or Happy Hare's current tool, else the
     * first lane.
     */
    fun apply(status: PrinterStatus, lanes: List<Lane>, currentLane: String? = null, currentTool: Int? = null): PrinterStatus {
        val nozzle = status.toolheads.firstOrNull { it.active } ?: status.toolheads.firstOrNull()
        val sorted = lanes.sortedBy { it.tool }
        val feeding = sorted.firstOrNull { currentLane != null && it.name == currentLane } ?: sorted.firstOrNull { it.tool == currentTool }
        val withTemps = feeding ?: sorted.first()
        return status.copy(toolheads = sorted.map { l ->
            val here = l === withTemps
            Toolhead(l.tool, if (here) nozzle?.nozzleTemperature else null, if (here) nozzle?.nozzleTarget else null, nozzle?.nozzleDiameterMm, l.loaded,
                if (l.loaded) Material(type = l.material.uppercase(), colorHex = l.colorHex) else null, active = l === feeding)
        })
    }

    private fun JSONObject.string(k: String): String = (opt(k) as? String)?.trim().orEmpty()
    private fun JSONArray.string(i: Int): String = (opt(i) as? String)?.trim().orEmpty()
}
