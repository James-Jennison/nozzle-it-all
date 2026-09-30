// Adapted from Helix (github.com/FatBoy721/Helix), AGPL-3.0-or-later.
// Original: services/bambuReport.ts (bambuTrays, isBambuTrayLoaded, activeBambuTray).
// Extended for several AMS units, AMS 2 Pro, AMS HT, the A2L's mixed AMS lite, both external spools and the two-nozzle
// printers from Bambu Studio's own report parser (BambuStudio src/slic3r/GUI/DeviceCore/DevFilaSystem.cpp,
// DevExtruderSystem.cpp, DevDefs.h and src/slic3r/GUI/DeviceManager.cpp; AGPL-3.0); line references below.
package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * A Bambu printer's AMS trays and external spool holders, read from its MQTT status report (`print`) the way Bambu
 * Studio reads them. Pure; no I/O.
 *
 * Every tray is a [BambuTray] carrying both numbers Bambu uses for it: the unit id and slot (`ams_id`/`slot_id`, what
 * the print command's `ams_mapping2` names) and the global tray index (what `ams_mapping`, `tray_now` and `tray_tar`
 * name): `4*id + slot` for AMS, AMS lite and AMS 2 Pro units (ids 0-3), the unit id itself for an AMS HT (ids 128-135,
 * one slot), and `24 + slot` for the A2L's mixed AMS lite. A tray only holds filament when its bit in `tray_exist_bits`
 * is set (Bambu leaves stale type and colour in an unloaded tray); an AMS HT's bit is `16 + (id-128)`, not its index.
 * The external holders come last: [EXT_MAIN] (255, the right nozzle, or the only one) and, on two-nozzle printers,
 * [EXT_DEPUTY] (254, the left nozzle).
 *
 * [parse] keeps the FilamentSlot view the slot panel reads; [read] is the full picture the print command's mapping needs.
 */
object BambuAmsTrays {
    const val TRAYS_PER_AMS = 4
    /** Nothing loaded (`tray_now` / `tray_tar` on a one-nozzle printer). Its own namespace: not [EXT_MAIN]. */
    const val NO_TRAY = 255
    /** `tray_now` on a one-nozzle printer when the external spool is loaded (DevExtruderSystem.cpp:300-304). */
    const val TRAY_NOW_EXTERNAL = 254
    /** The main (right, or only) nozzle's external spool: VIRTUAL_TRAY_MAIN_ID (DevDefs.h:86). */
    const val EXT_MAIN = 255
    /** The deputy (left) nozzle's external spool on H2D / H2D Pro / H2C / X2D: VIRTUAL_TRAY_DEPUTY_ID (DevDefs.h:87). */
    const val EXT_DEPUTY = 254
    /** Extruder ids as the printer numbers them: MAIN is the right nozzle (or the only one), DEPUTY the left. */
    const val MAIN_EXTRUDER = 0
    const val DEPUTY_EXTRUDER = 1
    /** AMS HT unit ids are 0x80..0x87 (DevExtruderSystem.cpp:276-285). */
    const val HT_FIRST_ID = 128
    const val HT_LAST_ID = 135
    /** AMS_LITE_MIXED_TRAY_INDEX_OFFSET (DevDefs.h:93). */
    const val MIXED_LITE_OFFSET = 24

    /** The unit a tray sits in: `info` bits 0-3 (DevAmsType, DevDefs.h:54-62), or an external holder. */
    enum class AmsKind(val label: String) {
        AMS("AMS"), AMS_LITE("AMS Lite"), AMS_2_PRO("AMS 2 Pro"), AMS_HT("AMS HT"),
        /** The A2L's (N9) "AMS lite for N9": tray index 24 + slot whatever its id. */
        AMS_LITE_MIXED("AMS Lite"),
        EXTERNAL("External spool"),
    }

    /**
     * One tray. [amsId]/[slotId] are what `ams_mapping2` sends (255/0 or 254/0 for the external holders); [trayIndex]
     * is what `ams_mapping` sends for an AMS tray. [extruder] is the nozzle the unit feeds ([MAIN_EXTRUDER] right or
     * only, [DEPUTY_EXTRUDER] left), null when the unit is bound to none (or only through a Filament Track Switch,
     * which this app does not read yet). [remain] is the percentage left, -1 or null when unknown.
     */
    data class BambuTray(
        val amsId: Int, val slotId: Int, val trayIndex: Int, val kind: AmsKind, val extruder: Int?,
        /** The tray's `tray_exist_bits` bit (external holders always exist, DeviceManager.cpp:4150). */
        val exists: Boolean = true,
        /** [exists] and a material reported. */
        val loaded: Boolean, val material: String?, val colorHex: String?, val vendor: String? = null,
        val trayInfoIdx: String? = null, val remain: Int? = null, val nozzleTempC: Int? = null,
        val active: Boolean = false, val name: String = "", val shortName: String = "",
    ) {
        val external: Boolean get() = kind == AmsKind.EXTERNAL

        /** The slot panel's view. [FilamentSlot.tool] is [trayIndex] here (255/254 for the external holders), not a T number. */
        fun toFilamentSlot() = FilamentSlot(
            tool = trayIndex, material = material, colorHex = colorHex, vendor = vendor, nozzleTempC = nozzleTempC,
            active = active, name = name, unitKind = kind.label, extruder = extruder, shortName = shortName,
        )
    }

    /** Everything [read] takes from one report: the trays, and how many nozzles the printer reported (1 or 2). */
    data class Report(val trays: List<BambuTray>, val extruderCount: Int) {
        val dualNozzle: Boolean get() = extruderCount >= 2
    }

    /** The report's `print` object (or the whole report) to its AMS trays as slots; empty when the printer has no AMS. */
    fun parse(report: JSONObject): List<FilamentSlot> = read(report).trays.map { it.toFilamentSlot() }

    /**
     * The report's trays. [unitKindWithoutInfo] is the kind of a unit whose report has no `info` (old X1/P1/A1 firmware):
     * Bambu Studio takes AMS, or AMS lite on the A1 and A1 mini (`use_ams_type` "f1") (DevFilaSystem.cpp:662-667). A
     * status report doesn't name the model, so a caller who knows it says so.
     */
    fun read(report: JSONObject, unitKindWithoutInfo: AmsKind = AmsKind.AMS): Report {
        val print = report.optJSONObject("print") ?: report
        val ams = print.optJSONObject("ams")
        // Both bit sets are hex strings (DevFilaSystem.cpp:538-546).
        val trayBits = hexBits(ams?.opt("tray_exist_bits")) ?: 0L
        val unitBits = hexBits(ams?.opt("ams_exist_bits"))
        val extruder = print.optJSONObject("device")?.optJSONObject("extruder")
        val extruderCount = extruderCount(extruder)
        val dual = extruderCount >= 2
        // One nozzle: the active tray is a global tray index; two nozzles: an (ams_id, slot_id) pair.
        val activeIndex = if (dual) null else activeTrayIndex(ams)
        val activePair = if (dual) activeSlot(extruder) else null
        fun isActive(amsId: Int, slotId: Int, index: Int) = if (dual) activePair == (amsId to slotId) else activeIndex == index
        val trays = ArrayList<BambuTray>()
        val units = ams?.optJSONArray("ams") ?: JSONArray()
        for (position in 0 until minOf(units.length(), 16)) {
            val unit = units.optJSONObject(position) ?: continue
            // Trust the unit's own `id` over its position: a second AMS can report out of order. (Its `ams_id` field is
            // not the unit id: real P1S reports carry "000000000000000" there.)
            val unitId = number(unit.opt("id")) ?: position
            val info = hexBits(unit.opt("info"))
            val kind = if (info == null) unitKindWithoutInfo else kindOf((info and 0xF).toInt(), unitId)
            // info bits 8-11: 0 MAIN, 1 DEPUTY, 0xE bound to none or to a Filament Track Switch (DevFilaSystem.cpp:640-661).
            // No info: MAIN.
            val boundTo = if (info == null) MAIN_EXTRUDER else ((info shr 8) and 0xF).toInt().takeIf { it == MAIN_EXTRUDER || it == DEPUTY_EXTRUDER }
            // A unit whose ams_exist_bits bit is clear is stale (DevFilaSystem.cpp:696-705); old reports without the field keep it.
            val unitPresent = unitBits == null || bit(unitBits, unitExistBit(kind, unitId))
            val unitTrays = unit.optJSONArray("tray") ?: continue
            val slotCount = if (kind == AmsKind.AMS_HT) 1 else TRAYS_PER_AMS
            for (trayPosition in 0 until minOf(unitTrays.length(), slotCount)) {
                val tray = unitTrays.optJSONObject(trayPosition) ?: continue
                val slotId = number(tray.opt("id")) ?: trayPosition
                val index = trayIndex(kind, unitId, slotId)
                val loaded = unitPresent && bit(trayBits, trayExistBit(kind, unitId, slotId))
                trays += tray(tray, unitId, slotId, index, kind, boundTo, loaded, isActive(unitId, slotId, index),
                    unitName(kind, unitId) + if (kind == AmsKind.AMS_HT) "" else " · slot ${slotId + 1}", shortName(kind, unitId, slotId))
            }
        }
        trays += externalTrays(print, dual) { id -> isActive(id, 0, id) }
        return Report(trays, extruderCount)
    }

    /**
     * The external holders. New firmware sends `print.vir_slot[]` ("255" main/right, "254" deputy/left); older and
     * one-nozzle firmware sends a single `print.vt_tray`, which Bambu Studio always treats as 255 (MAIN) whatever id it
     * carries (a P1S sends "254") (DeviceManager.cpp:3504-3541). They have no occupancy bit: a holder holds filament when
     * it reports a type.
     */
    private fun externalTrays(print: JSONObject, dualNozzle: Boolean, isActive: (Int) -> Boolean): List<BambuTray> {
        val slots = print.optJSONArray("vir_slot")
        val found = LinkedHashMap<Int, JSONObject>()
        if (slots != null) {
            for (i in 0 until minOf(slots.length(), 8)) {
                val tray = slots.optJSONObject(i) ?: continue
                val id = virtualSlotId(number(tray.opt("id")) ?: continue)
                if ((id == EXT_MAIN || id == EXT_DEPUTY) && id !in found) found[id] = tray
            }
        } else {
            print.optJSONObject("vt_tray")?.let { found[EXT_MAIN] = it }
        }
        // [GK] Whether a one-nozzle H2S/P2S sends vir_slot with a single "255" entry isn't visible in Bambu Studio's code;
        // both forms land here. Only a printer with a deputy holder (or two nozzles) gets the left/right names.
        val sided = dualNozzle || EXT_DEPUTY in found
        return listOf(EXT_MAIN, EXT_DEPUTY).mapNotNull { id ->
            val tray = found[id] ?: return@mapNotNull null
            val name = when { !sided -> "External spool"; id == EXT_MAIN -> "External (right)"; else -> "External (left)" }
            // Bambu Studio's mapping builds the external tray with slot 0 (DevMapping.cpp:235).
            tray(tray, id, 0, id, AmsKind.EXTERNAL, if (id == EXT_MAIN) MAIN_EXTRUDER else DEPUTY_EXTRUDER,
                tray.optString("tray_type").isNotBlank(), isActive(id), name, "Ext", exists = true)
        }
    }

    /** parse_vt_tray (DeviceManager.cpp:4152-4168): bits 8-15 ams id, bits 0-7 slot; 65024 (0xFE00) is 254 + 0 = 254. */
    fun virtualSlotId(raw: Int): Int = if ((raw shr 8) > 0) (raw shr 8) + (raw and 0xFF) else raw

    private fun tray(tray: JSONObject, amsId: Int, slotId: Int, index: Int, kind: AmsKind, extruder: Int?, loaded: Boolean,
                     active: Boolean, name: String, shortName: String, exists: Boolean = loaded): BambuTray {
        val type = trayType(tray)
        return BambuTray(
            amsId = amsId, slotId = slotId, trayIndex = index, kind = kind, extruder = extruder, exists = exists,
            loaded = loaded && type != null,
            material = type.takeIf { loaded },
            colorHex = if (loaded) FilamentLanes.normalizeColor(tray.optString("tray_color")) else null,
            vendor = tray.optString("tray_sub_brands").trim().takeIf { loaded && it.isNotEmpty() },
            trayInfoIdx = tray.optString("tray_info_idx").trim().takeIf { loaded && it.isNotEmpty() },
            remain = number(tray.opt("remain")).takeIf { loaded },
            nozzleTempC = number(tray.opt("nozzle_temp_max"))?.takeIf { loaded && it > 0 },
            active = active, name = name, shortName = shortName,
        )
    }

    /**
     * A tray's material the way Bambu Studio reads it: only when the tray reports both `tray_info_idx` and `tray_type`
     * (otherwise the type is cleared), with the support filaments GFS00 / GFS01 named PLA-S / PA-S
     * (DevFilaSystem.cpp:832-846).
     */
    private fun trayType(tray: JSONObject): String? {
        if (!tray.has("tray_info_idx") || !tray.has("tray_type")) return null
        val type = when (tray.optString("tray_info_idx").trim()) {
            "GFS00" -> "PLA-S"
            "GFS01" -> "PA-S"
            else -> tray.optString("tray_type").trim()
        }
        return type.takeIf { it.isNotEmpty() }
    }

    /**
     * A material name for comparing a tray with a sliced filament, with Bambu Studio's support aliases
     * (DevAmsTray::get_filament_type, DevFilaSystem.cpp:144-154): Sup.PLA, Support W and a bare Support are PLA-S,
     * Sup.PA and Support G PA-S, Sup.ABS ABS-S. Upper case.
     */
    fun materialKey(type: String?): String {
        val t = type.orEmpty().trim()
        return when {
            t.equals("Sup.PLA", true) || t.equals("Support W", true) || t.equals("Support", true) -> "PLA-S"
            t.equals("Sup.PA", true) || t.equals("Support G", true) -> "PA-S"
            t.equals("Sup.ABS", true) -> "ABS-S"
            else -> t.uppercase()
        }
    }

    private fun kindOf(type: Int, unitId: Int): AmsKind = when (type) {
        1 -> AmsKind.AMS
        2 -> AmsKind.AMS_LITE
        3 -> AmsKind.AMS_2_PRO
        4 -> AmsKind.AMS_HT
        5 -> AmsKind.AMS_LITE_MIXED
        // 0 (EXT_SPOOL) or a type newer than this code: go by the id range rather than guess a new layout.
        else -> if (unitId in HT_FIRST_ID..HT_LAST_ID) AmsKind.AMS_HT else AmsKind.AMS
    }

    /** The global tray index `ams_mapping` / `tray_now` use (GetTrayIndexMap, DevFilaSystem.cpp:398-427). */
    fun trayIndex(kind: AmsKind, unitId: Int, slotId: Int): Int = when (kind) {
        AmsKind.AMS_HT -> unitId + slotId
        AmsKind.AMS_LITE_MIXED -> MIXED_LITE_OFFSET + slotId
        AmsKind.EXTERNAL -> unitId
        AmsKind.AMS, AmsKind.AMS_LITE, AmsKind.AMS_2_PRO -> unitId * TRAYS_PER_AMS + slotId
    }

    /** The tray's bit in `tray_exist_bits` (sGetAmsFlagBit, DevFilaSystem.cpp:111-123): for an HT not its index. */
    private fun trayExistBit(kind: AmsKind, unitId: Int, slotId: Int): Int = when (kind) {
        AmsKind.AMS_HT -> 16 + (unitId - HT_FIRST_ID) + slotId
        else -> trayIndex(kind, unitId, slotId)
    }

    /** The unit's bit in `ams_exist_bits` (DevFilaSystem.cpp:696-705). */
    private fun unitExistBit(kind: AmsKind, unitId: Int): Int = when (kind) {
        AmsKind.AMS_HT -> 4 + (unitId - HT_FIRST_ID)
        AmsKind.AMS_LITE_MIXED -> 12
        else -> unitId
    }

    /** "AMS 1", "AMS Lite 2", "AMS 2 Pro 1", "AMS HT 1" (id 128), numbered as Bambu Studio does (DevFilaSystem.cpp:235-274). */
    private fun unitName(kind: AmsKind, unitId: Int): String {
        val n = when { unitId > 127 -> unitId - 127; unitId in 0x10..0x1F -> unitId - 15; else -> unitId + 1 }
        return "${kind.label} $n"
    }

    /** "A1".."D4"; an HT is a bare letter from "A" (GetTrayNameByTrayId, DevFilaSystem.cpp:443-465). */
    private fun shortName(kind: AmsKind, unitId: Int, slotId: Int): String = when {
        kind == AmsKind.AMS_HT -> ('A' + (unitId - HT_FIRST_ID)).toString()
        unitId in 0..25 -> "${'A' + unitId}${slotId + 1}"
        else -> "$unitId-${slotId + 1}"
    }

    /** `print.device.extruder.state` bits 0-3 (DevExtruderSystem.cpp:334-348), else the `info` entries; 1 when absent. */
    private fun extruderCount(extruder: JSONObject?): Int {
        val fromState = number(extruder?.opt("state"))?.let { it and 0xF }?.takeIf { it > 0 }
        return fromState ?: extruder?.optJSONArray("info")?.length()?.takeIf { it > 0 } ?: 1
    }

    /**
     * One nozzle: the tray index `tray_now` names. 255 none, 254 the external spool (the main holder, [EXT_MAIN]),
     * 0x80-0x87 an AMS HT, anything else `4*ams + slot` (DevExtruderSystem.cpp:290-320).
     */
    private fun activeTrayIndex(ams: JSONObject?): Int? = when (val now = number(ams?.opt("tray_now"))) {
        null, NO_TRAY -> null
        TRAY_NOW_EXTERNAL -> EXT_MAIN
        else -> now?.takeIf { it >= 0 }
    }

    /**
     * Two nozzles, where `tray_now` means nothing: the current extruder (`state` bits 4-7) and its `info[].snow`,
     * `(ams_id << 8) | slot_id`, 0xFFFF none (DevExtruderSystem.cpp:334-396).
     */
    private fun activeSlot(extruder: JSONObject?): Pair<Int, Int>? {
        val current = number(extruder?.opt("state"))?.let { (it shr 4) and 0xF } ?: return null
        val infos = extruder?.optJSONArray("info") ?: return null
        val entry = (0 until infos.length()).mapNotNull { infos.optJSONObject(it) }.firstOrNull { number(it.opt("id")) == current } ?: return null
        // [GK] The exact snow value for an external holder (0xFF00 main, 0xFE00 deputy) isn't in Bambu Studio's code.
        val now = number(entry.opt("snow"))?.takeIf { it in 0 until 0xFFFF } ?: return null
        return (now shr 8) to (now and 0xFF)
    }

    private fun bit(bits: Long, index: Int): Boolean = index in 0..62 && (bits and (1L shl index)) != 0L

    private fun hexBits(value: Any?): Long? = (value as? String)?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull(16)

    /** Bambu sends numbers as strings about as often as it sends them as numbers. */
    private fun number(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}
