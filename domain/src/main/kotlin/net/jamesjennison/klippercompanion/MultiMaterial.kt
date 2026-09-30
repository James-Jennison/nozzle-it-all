package net.jamesjennison.klippercompanion

// Phase 8 close-out (Consumer Slicer Plan §11): how each kind of multi-tool machine changes material, and the plain
// warnings owners need before mixing materials on one plate. Pure logic, unit-tested.

/**
 * The real mechanisms behind "more than one material". [sharesNozzle]: at least one nozzle is fed from several spools,
 * so changes flush the old colour out through it (purge waste, a prime tower, flexible filament jamming).
 */
enum class MultiToolFamily(val label: String, val explanation: String, val sharesNozzle: Boolean) {
    SINGLE("Single material", "One extruder: every object prints in the same material.", false),
    TOOLCHANGER("Independent tools", "Each tool has its own nozzle and filament (toolchangers such as the Snapmaker U1 and Prusa XL, and IDEX printers). A change swaps the toolhead, so little is purged; a prime tower only limits oozing from the idle nozzles.", false),
    FILAMENT_SWAP("Filament swap, one nozzle", "One nozzle is fed from several spools (Bambu AMS, Creality CFS, Anycubic ACE, Prusa MMU3, ...). Every change flushes the old colour out, so purge waste is high and a prime tower is normally required.", true),
    MIXED("Several nozzles, each fed by several spools", "More spools than nozzles (for example a dual-nozzle printer with an AMS). Changes between spools on the same nozzle flush the old colour out, so purge waste and a prime tower apply as with a single shared nozzle.", true),
}

/**
 * How a printer changes material, from its slicing pack: [nozzleCount] physical nozzles (machine.json's nozzle_diameter
 * entries) and [slotCount] filament slots (the pack's slot count, which also counts spools fed through one nozzle).
 * One nozzle per slot is a toolchanger or IDEX machine; more slots than nozzles means spools share a nozzle.
 */
fun multiToolFamily(nozzleCount: Int, slotCount: Int): MultiToolFamily = when {
    slotCount <= 1 -> MultiToolFamily.SINGLE
    nozzleCount <= 1 -> MultiToolFamily.FILAMENT_SWAP
    slotCount <= nozzleCount -> MultiToolFamily.TOOLCHANGER
    else -> MultiToolFamily.MIXED
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
            out += "Flexible filament (${kinds.filter { it in FLEXIBLE }.joinToString("/")}) with rigid filament" + if (family.sharesNozzle) ": pushing flexible filament through a shared nozzle path often jams." else ": interfaces between them bond poorly."
        val nozzle = distinct.mapNotNull { it.tempNozzleC }
        if (nozzle.size >= 2 && nozzle.max() - nozzle.min() > 25)
            out += "Nozzle temperatures differ by ${nozzle.max() - nozzle.min()}°C (${nozzle.min()}–${nozzle.max()}°C)" +
                if (family.sharesNozzle) ": the shared nozzle must swing between them at every change, which is slow and can burn the cooler material." else ": the hotter tool may ooze or cook while it waits."
        val bed = distinct.mapNotNull { it.tempBedC }
        if (bed.size >= 2 && bed.max() - bed.min() > 15)
            out += "Bed temperatures differ by ${bed.max() - bed.min()}°C (${bed.min()}–${bed.max()}°C): the bed holds one temperature, so the colder-bed material may not stick."
        return out
    }
}

/** A distinct, readable colour for tool [index] when its material has no colour of its own. */
val DEFAULT_TOOL_COLORS: List<String> = listOf("#F2754E", "#4FC3F7", "#81C784", "#FFD54F", "#BA68C8", "#F06292", "#A1887F", "#90A4AE")
fun toolColorHex(index: Int, material: MaterialProfile?): String = material?.colorHex?.takeIf { it.isNotBlank() } ?: DEFAULT_TOOL_COLORS[index.coerceAtLeast(0) % DEFAULT_TOOL_COLORS.size]
