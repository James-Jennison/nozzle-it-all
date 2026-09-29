package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * One filament slot a printer reports: a filament-changer lane on a Klipper printer (FilamentLanes) or a CANVAS tray on an
 * Elegoo printer (ElegooPrinterService). [tool] is the 0-based T number the slot feeds, so slot `tool + 1` lines up with
 * the project's "Tool N" and the sliced file's T<n>.
 *
 * A Bambu AMS tray (BambuAmsTrays) is the exception: its [tool] is Bambu's global tray index (128+ for an AMS HT, 255/254
 * for the external holders), not a T number, and it also says which unit it sits in ([unitKind], e.g. "AMS 2 Pro"),
 * which nozzle that unit feeds ([extruder]: 0 the right or only nozzle, 1 the left, null none) and Bambu's short tray
 * name ([shortName], "A1".."D4", "A" for an HT, "Ext"). Every other printer leaves those null.
 */
data class FilamentSlot(val tool: Int, val material: String?, val colorHex: String?, val vendor: String? = null, val nozzleTempC: Int? = null,
                        val active: Boolean = false, val name: String? = null, val unitKind: String? = null, val extruder: Int? = null,
                        val shortName: String? = null) {
    val loaded: Boolean get() = !material.isNullOrBlank()
    val label: String get() = listOfNotNull(vendor, material).joinToString(" ").ifBlank { "Empty" }
}

/** What a printer said about its filament slots; [source] names where they came from, in plain words. */
data class FilamentSlotStatus(val slots: List<FilamentSlot>, val source: String)

interface FilamentSlotReader : AutoCloseable {
    /** Read-only. Empty slots when the printer has no filament changer Nozzle can read. */
    fun filamentSlots(): FilamentSlotStatus
}

/**
 * The filament lanes of a Klipper printer's filament changer (AFC units such as the Elegoo CANVAS on COSMOS, Box Turtle;
 * Happy Hare MMUs), as slots. Pure rules, no I/O. The same rules as the desktop's adapter-paxx FilamentLanes and the Web
 * App's paxx.ts, ported from OrcaSlicer's MoonrakerPrinterAgent (src/slic3r/Utils/MoonrakerPrinterAgent.cpp at 824b216f,
 * fetch_filament_info, fetch_moonraker_filament_data, fetch_hh_filament_info; AGPL-3.0): Moonraker's `lane_data` database
 * namespace first (written by AFC, AFC_lane.py send_lane_data, and recent Happy Hare), then Happy Hare's `mmu` object. A
 * lane's slot is the tool it is mapped to (`lane`, "0" for T0); lanes with no tool are skipped, as upstream does.
 */
object FilamentLanes {
    /** Happy Hare's fields, queried with AFC's current lane in one call. */
    const val MMU_FIELDS = "num_gates,gate_status,gate_material,gate_color,gate_temperature,tool"

    /** "#RRGGBB" upper case, dropping an alpha byte; null for anything that isn't a colour (as U1Protocol.normalizeColor). */
    fun normalizeColor(raw: String?): String? {
        val s = raw?.trim()?.removePrefix("#") ?: return null
        if ((s.length != 6 && s.length != 8) || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return "#" + s.substring(0, 6).uppercase()
    }

    /** `server/database/item?namespace=lane_data`'s result (its "value" object). Null when there are no lanes. */
    fun fromLaneData(result: JSONObject?): List<FilamentSlot>? {
        val value = result?.optJSONObject("value") ?: return null
        val lanes = value.keySet().sorted().take(64).mapNotNull { key ->
            val lane = value.optJSONObject(key) ?: return@mapNotNull null
            val tool = (lane.opt("lane") as? String)?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            FilamentSlot(tool, lane.string("material").ifEmpty { null }, normalizeColor(lane.string("color")),
                nozzleTempC = (lane.opt("nozzle_temp") as? Number)?.toInt()?.takeIf { it > 0 }, name = key)
        }.distinctBy { it.tool }
        return lanes.takeIf { it.isNotEmpty() }
    }

    /** Happy Hare's `mmu` status object. Only available gates (status 1 or 2) with a material, as upstream. */
    fun fromHappyHare(mmu: JSONObject?): List<FilamentSlot>? {
        val gates = (mmu?.opt("num_gates") as? Number)?.toInt()?.takeIf { it > 0 } ?: return null
        val status = mmu.optJSONArray("gate_status"); val material = mmu.optJSONArray("gate_material")
        val color = mmu.optJSONArray("gate_color"); val temp = mmu.optJSONArray("gate_temperature")
        if (status == null || material == null || color == null || temp == null) return null
        val lanes = (0 until minOf(gates, 64)).mapNotNull { g ->
            if (((status.opt(g) as? Number)?.toInt() ?: 0) <= 0) return@mapNotNull null
            val type = material.string(g).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            FilamentSlot(g, type, normalizeColor(color.string(g)), nozzleTempC = (temp.opt(g) as? Number)?.toInt()?.takeIf { it > 0 })
        }
        return lanes.takeIf { it.isNotEmpty() }
    }

    /**
     * The lanes sorted by tool, material types upper case (as the desktop shows them), with the lane feeding the one
     * nozzle marked active: AFC's current lane, else Happy Hare's current tool.
     */
    fun slots(lanes: List<FilamentSlot>, currentLane: String? = null, currentTool: Int? = null): List<FilamentSlot> {
        val sorted = lanes.sortedBy { it.tool }
        val feeding = sorted.firstOrNull { currentLane != null && it.name == currentLane } ?: sorted.firstOrNull { it.tool == currentTool }
        return sorted.map { it.copy(material = it.material?.uppercase(), active = it === feeding) }
    }

    /**
     * The whole read as Moonraker answers it: [laneData] from `server/database/item?namespace=lane_data` (null when that
     * namespace doesn't exist), [objects] the `status` of `printer/objects/query` with `AFC=current_load` and
     * `mmu=MMU_FIELDS`.
     */
    /** Fields the Snapmaker U1 reports per toolhead in its `print_task_config` object (U1Protocol.statusQuery's). */
    const val U1_TASK_CONFIG_FIELDS = "filament_exist,filament_vendor,filament_type,filament_sub_type,filament_color_rgba,filament_official"
    private const val U1_TOOLHEADS = 4

    /**
     * The Snapmaker U1's four toolheads from `print_task_config`, with the desktop PAXX adapter's rules (adapter-paxx
     * U1Protocol.parseStatus; field semantics from the Snapmaker Orca fork, docs/upstream/PROVENANCE.md P-0001):
     * `filament_exist` says whether a toolhead is loaded; type, vendor and colour are read only for loaded ones.
     * [activeExtruder] is `toolhead.extruder` ("extruder", "extruder1", ...). Null when the printer has no such object.
     * Found missing on a real PAXX U1 in Test Mode: Android listed four toolheads with no materials.
     */
    fun fromU1TaskConfig(cfg: JSONObject?, activeExtruder: String?): List<FilamentSlot>? {
        val exist = cfg?.optJSONArray("filament_exist") ?: return null
        fun JSONArray?.str(i: Int): String? = this?.opt(i)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() && !it.equals("NONE", true) }
        return (0 until minOf(U1_TOOLHEADS, exist.length())).map { i ->
            val loaded = exist.opt(i) == true
            val type = if (loaded) cfg.optJSONArray("filament_type").str(i)?.uppercase() ?: "LOADED (TYPE NOT REPORTED)" else null
            FilamentSlot(i, type, if (loaded) normalizeColor(cfg.optJSONArray("filament_color_rgba").str(i)) else null,
                vendor = if (loaded) cfg.optJSONArray("filament_vendor").str(i) else null,
                active = activeExtruder == (if (i == 0) "extruder" else "extruder$i"), name = "T$i")
        }.takeIf { it.isNotEmpty() }
    }

    fun read(laneData: JSONObject?, objects: JSONObject?): FilamentSlotStatus {
        val mmu = objects?.optJSONObject("mmu")
        val fromDb = fromLaneData(laneData)
        val lanes = fromDb ?: fromHappyHare(mmu)
            ?: return fromU1TaskConfig(objects?.optJSONObject("print_task_config"), objects?.optJSONObject("toolhead")?.optString("extruder"))
                ?.let { FilamentSlotStatus(it, "Snapmaker U1 toolheads") } ?: FilamentSlotStatus(emptyList(), "")
        val current = objects?.optJSONObject("AFC")?.optString("current_load")?.takeIf { it.isNotBlank() && it != "null" }
        return FilamentSlotStatus(slots(lanes, current, (mmu?.opt("tool") as? Number)?.toInt()?.takeIf { it >= 0 }),
            if (fromDb != null) "Filament changer lanes (AFC)" else "Happy Hare gates")
    }

    private fun JSONObject.string(k: String): String = (opt(k) as? String)?.trim().orEmpty()
    private fun JSONArray.string(i: Int): String = (opt(i) as? String)?.trim().orEmpty()
}
