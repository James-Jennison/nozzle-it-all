package net.jamesjennison.klippercompanion

// Phase 8 close-out (Consumer Slicer Plan §11): how each kind of multi-tool machine changes material, and the plain
// warnings owners need before mixing materials on one plate. Pure logic, unit-tested.

/** The three real mechanisms behind "more than one material". The bundled machine.json only says how many tools exist. */
enum class MultiToolFamily(val label: String, val explanation: String) {
    SINGLE("Single material", "One extruder: every object prints in the same material."),
    TOOLCHANGER("Independent tools", "Each tool has its own nozzle and filament (Snapmaker U1, Prusa XL). A change swaps the toolhead, so little is purged; a prime tower only limits oozing from the idle nozzles."),
    FILAMENT_SWAP("Filament swap, one nozzle", "One nozzle is fed from several spools (AMS-style). Every change flushes the old colour out, so purge waste is high and a prime tower is normally required."),
}

fun multiToolFamily(model: SlicingPrinterModel?, toolCount: Int): MultiToolFamily = when {
    toolCount <= 1 -> MultiToolFamily.SINGLE
    model != null && SlicingModelCatalog.info(model).vendor == SlicingVendor.BAMBU -> MultiToolFamily.FILAMENT_SWAP // every Bambu model: AMS-style swap (H2 dual-nozzle models are unverified)
    else -> MultiToolFamily.TOOLCHANGER // Snapmaker U1, Prusa XL: the only multi-tool machines this app targets besides Bambu
}

object MaterialCompatibility {
    private val FLEXIBLE = setOf("TPU", "TPE", "FLEX")
    private val HIGH_TEMP = setOf("ABS", "ASA", "PC", "PA", "NYLON", "PA6", "PA12", "PPS", "PEEK", "PEI")
    private val LOW_TEMP = setOf("PLA", "PVA", "BVOH", "HIPS")

    private fun kind(m: MaterialProfile) = m.type.trim().uppercase().substringBefore('-').substringBefore(' ')

    /** Plain-language warnings for the materials assigned on one plate; empty when nothing looks risky. */
    fun warnings(materials: List<MaterialProfile>, family: MultiToolFamily): List<String> {
        val distinct = materials.distinctBy { it.id }
        if (distinct.size < 2) return emptyList()
        val out = ArrayList<String>()
        val kinds = distinct.map(::kind).toSet()
        if (kinds.any { it in HIGH_TEMP } && kinds.any { it in LOW_TEMP })
            out += "${kinds.filter { it in LOW_TEMP }.joinToString("/")} with ${kinds.filter { it in HIGH_TEMP }.joinToString("/")} in one print: they shrink and bond very differently, so layers between them may not stick."
        if (kinds.any { it in FLEXIBLE } && kinds.any { it !in FLEXIBLE })
            out += "Flexible filament (${kinds.filter { it in FLEXIBLE }.joinToString("/")}) with rigid filament" + if (family == MultiToolFamily.FILAMENT_SWAP) ": pushing flexible filament through a shared nozzle path often jams." else ": interfaces between them bond poorly."
        val nozzle = distinct.mapNotNull { it.tempNozzleC }
        if (nozzle.size >= 2 && nozzle.max() - nozzle.min() > 25)
            out += "Nozzle temperatures differ by ${nozzle.max() - nozzle.min()}°C (${nozzle.min()}–${nozzle.max()}°C)" +
                if (family == MultiToolFamily.FILAMENT_SWAP) ": the single nozzle must swing between them at every change, which is slow and can burn the cooler material." else ": the hotter tool may ooze or cook while it waits."
        val bed = distinct.mapNotNull { it.tempBedC }
        if (bed.size >= 2 && bed.max() - bed.min() > 15)
            out += "Bed temperatures differ by ${bed.max() - bed.min()}°C (${bed.min()}–${bed.max()}°C): the bed holds one temperature, so the colder-bed material may not stick."
        return out
    }
}

/** A distinct, readable colour for tool [index] when its material has no colour of its own. */
val DEFAULT_TOOL_COLORS: List<String> = listOf("#F2754E", "#4FC3F7", "#81C784", "#FFD54F", "#BA68C8", "#F06292", "#A1887F", "#90A4AE")
fun toolColorHex(index: Int, material: MaterialProfile?): String = material?.colorHex?.takeIf { it.isNotBlank() } ?: DEFAULT_TOOL_COLORS[index.coerceAtLeast(0) % DEFAULT_TOOL_COLORS.size]
