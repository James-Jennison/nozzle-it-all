package com.nozzleitall.desktop.prepare

/** One thing worth knowing before you slice, with the one-click fix when there is one. */
data class Advice(val id: String, val text: String, val action: String? = null, val actionLabel: String? = null)

/**
 * The problems the app can see on the plate before a slice, said plainly. Pure rules so they are easy to test; the screen only draws them.
 * Nothing here blocks slicing: these are notices, and each can be dismissed.
 */
object PrepareAdvice {
    const val ARRANGE = "arrange"
    const val CHOOSE_PRINTER = "choose-printer"

    class Input(
        /** Names of objects that hang off the plate. */
        val offPlate: List<String>,
        /** True when a printer (or an export-only target) has been chosen. */
        val hasPrinter: Boolean,
        /** Object name to its count of open edges (a mesh that isn't closed may print with holes). */
        val openEdges: Map<String, Int>,
        val objectCount: Int,
    )

    fun compute(i: Input): List<Advice> {
        if (i.objectCount == 0) return emptyList()
        val out = ArrayList<Advice>()
        if (i.offPlate.isNotEmpty())
            out += Advice("off-plate", (if (i.offPlate.size == 1) "${i.offPlate[0]} hangs off the plate." else "${i.offPlate.size} objects hang off the plate.") + " Anything outside the bed can't print.", ARRANGE, "Arrange")
        val open = i.openEdges.filterValues { it > 0 }
        if (open.isNotEmpty()) {
            val worst = open.maxByOrNull { it.value }!!
            out += Advice("open-edges", (if (open.size == 1) "${worst.key} isn't a closed shape (${worst.value} open edges)" else "${open.size} objects aren't closed shapes (worst: ${worst.key}, ${worst.value} open edges)") +
                ". It may print with holes: repair the mesh before printing.")
        }
        if (!i.hasPrinter) out += Advice("no-printer", "No printer is chosen. You can slice, but sending needs one.", CHOOSE_PRINTER, "Choose printer")
        return out
    }
}
