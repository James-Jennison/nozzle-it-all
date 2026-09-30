package com.nozzleitall.adapter.elegoo

import com.nozzleitall.printer.Material
import com.nozzleitall.printer.Toolhead
import org.json.JSONArray
import org.json.JSONObject

/**
 * The CANVAS filament switcher as material slots. Pure rules, no I/O; the same shape on both printers.
 *
 * Ported from elegoo-link (Apache-2.0) `handleCanvasStatus` in src/lan/adapters/elegoo_fdm_cc/elegoo_fdm_cc_message_adapter.cpp
 * and src/lan/adapters/elegoo_fdm_cc2/elegoo_fdm_cc2_message_adapter.cpp: an object with `active_canvas_id`,
 * `active_tray_id`, `auto_refill` and `canvas_list[] { canvas_id, connected, tray_list[] { tray_id, brand, filament_type,
 * filament_name, filament_code, filament_color, min_nozzle_temp, max_nozzle_temp, status } }`. Tray `status` follows
 * ElegooSlicer's reading of it (src/slic3r/Utils/Elegoo/ElegooLink.cpp getPrinterMmsInfo): 0 empty/disconnected,
 * 1 pre-loaded, 2 loaded, anything else an error; pre-loaded and loaded trays are usable (PrinterMmsManager.cpp
 * checkTrayIsReady), and a blank brand means "Generic".
 *
 * Slots are numbered in the order Nozzle shows them: canvas by canvas (canvas_id ascending), tray by tray (tray_id
 * ascending), from 0. The printer's own ids are kept on each slot and are what goes back in `slot_map`, so Nozzle never
 * has to guess whether the firmware counts from 0 or 1.
 */
object Canvas {
    data class Slot(val index: Int, val canvasId: Int, val trayId: Int, val status: Int, val brand: String, val type: String, val name: String,
                    val colorHex: String?, val minNozzleTemp: Int?, val maxNozzleTemp: Int?) {
        val loaded: Boolean get() = status == 1 || status == 2
        val material: Material? get() = if (!loaded || type.isEmpty() && colorHex == null) null else Material(
            vendor = brand.takeIf { it.isNotEmpty() },
            type = type.takeIf { it.isNotEmpty() }?.uppercase(),
            // "PLA Matte" on a PLA tray becomes the sub-type "Matte"; a name that just repeats the type adds nothing.
            subType = name.takeIf { it.isNotEmpty() && !it.equals(type, ignoreCase = true) }?.let { n ->
                if (type.isNotEmpty() && n.startsWith(type, ignoreCase = true)) n.substring(type.length).trim().takeIf { it.isNotEmpty() } else n },
            colorHex = colorHex)
    }

    data class State(val slots: List<Slot>, val activeCanvasId: Int?, val activeTrayId: Int?, val autoRefill: Boolean?) {
        val active: Slot? get() = slots.firstOrNull { it.canvasId == activeCanvasId && it.trayId == activeTrayId }
    }

    private const val MAX_CANVASES = 8
    private const val MAX_TRAYS = 16

    /** Parses a canvas object. Null when it has no canvas list; an empty state when no CANVAS is connected. */
    fun parse(obj: JSONObject?): State? {
        val list = obj?.optJSONArray("canvas_list") ?: return null
        val canvases = (0 until minOf(list.length(), MAX_CANVASES)).mapNotNull { list.optJSONObject(it) }
            .filter { bool(it, "connected") == true }
            .sortedBy { int(it, "canvas_id") ?: 0 }
        val slots = ArrayList<Slot>()
        for (canvas in canvases) {
            val canvasId = int(canvas, "canvas_id") ?: 0
            val trays = canvas.optJSONArray("tray_list") ?: JSONArray()
            (0 until minOf(trays.length(), MAX_TRAYS)).mapNotNull { trays.optJSONObject(it) }.sortedBy { int(it, "tray_id") ?: 0 }.forEach { t ->
                slots += Slot(slots.size, canvasId, int(t, "tray_id") ?: 0, int(t, "status") ?: 0, str(t, "brand"), str(t, "filament_type"),
                    str(t, "filament_name"), ElegooNet.normalizeColor(str(t, "filament_color")), int(t, "min_nozzle_temp")?.takeIf { it > 0 },
                    int(t, "max_nozzle_temp")?.takeIf { it > 0 })
            }
        }
        return State(slots, int(obj, "active_canvas_id"), int(obj, "active_tray_id"), bool(obj, "auto_refill"))
    }

    /**
     * One [Toolhead] per slot, the way other single-nozzle printers with a material unit report theirs (see adapter-paxx
     * FilamentLanes.apply): the one nozzle's temperatures go on the slot feeding it (else the first slot). With no
     * CANVAS slots, the printer's single nozzle is the only toolhead.
     */
    fun toolheads(state: State?, nozzle: Double?, nozzleTarget: Double?): List<Toolhead> {
        val slots = state?.slots.orEmpty()
        if (slots.isEmpty()) return if (nozzle != null || nozzleTarget != null) listOf(Toolhead(0, nozzle, nozzleTarget, active = true)) else emptyList()
        val feeding = state!!.active
        val withTemps = feeding ?: slots.first()
        return slots.map { s ->
            val here = s === withTemps
            Toolhead(s.index, if (here) nozzle else null, if (here) nozzleTarget else null, loaded = s.loaded, material = s.material, active = s === feeding)
        }
    }

    /**
     * elegoo-link's `slot_map` (StartPrintParams.slotMap, serialised in both message adapters' START_PRINT case): one
     * `{"t": <T number in the file>, "canvas_id": ..., "tray_id": ...}` per mapped filament. [toolheadMap] is
     * PrinterAction.StartJob's: entry i is the slot (Toolhead.index) feeding filament i, -1 for "not mapped" (skipped, as
     * ElegooSlicer skips unmapped filaments). An empty map gives an empty array, as elegoo-link sends.
     * Throws IllegalArgumentException for a slot the printer doesn't have or one with nothing loaded.
     */
    fun slotMap(toolheadMap: List<Int>, state: State?): JSONArray {
        val out = JSONArray()
        if (toolheadMap.isEmpty()) return out
        require(toolheadMap.size <= 32) { "Too many materials for one print." }
        val slots = state?.slots.orEmpty()
        require(slots.isNotEmpty()) { "Nozzle It All hasn't read this printer's CANVAS slots yet. Check the printer's status, then start again." }
        toolheadMap.forEachIndexed { t, index ->
            if (index < 0) return@forEachIndexed
            val slot = slots.getOrNull(index) ?: throw IllegalArgumentException("Material ${t + 1} is mapped to slot ${index + 1}, which this printer doesn't have.")
            require(slot.loaded) { "Material ${t + 1} is mapped to slot ${index + 1}, which has nothing loaded." }
            out.put(JSONObject().put("t", t).put("canvas_id", slot.canvasId).put("tray_id", slot.trayId))
        }
        return out
    }

    private fun int(o: JSONObject, k: String): Int? = when (val v = o.opt(k)) { is Number -> v.toInt(); is String -> v.trim().toIntOrNull(); else -> null }
    private fun bool(o: JSONObject, k: String): Boolean? = when (val v = o.opt(k)) { is Boolean -> v; is Number -> v.toInt() != 0; else -> null }
    private fun str(o: JSONObject, k: String): String = (o.opt(k) as? String)?.trim().orEmpty()
}
