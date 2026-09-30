package net.jamesjennison.klippercompanion

/**
 * The filaments a sliced G-code file declares in the settings block OrcaSlicer (and this app's engine) writes at its end:
 * `; filament_type = PLA;PETG` and `; filament_colour = #FF0000;#00FF00`, one entry per project filament in T order.
 * A filament changer's print start names the material each file tool expects (Creality's `colorMatch.list[].type`,
 * Flashforge's `materialMappings[].toolMaterialColor`), so the services read them here. Pure; no I/O.
 */
object SlicedFileFilaments {
    /** Project filament [tool] (T<tool> in the file) as the file declares it; null fields were not declared. */
    data class Filament(val tool: Int, val type: String?, val colorHex: String?)

    private const val MAX_FILAMENTS = 64
    private val TYPE = Regex("""^\s*;\s*filament_type\s*=\s*(.*)$""")
    private val COLOUR = Regex("""^\s*;\s*filament_colou?r\s*=\s*(.*)$""")

    /** Empty when the file declares neither (a file sliced elsewhere, or with the settings block stripped). */
    fun read(lines: Sequence<String>): List<Filament> {
        var types: List<String>? = null
        var colours: List<String>? = null
        for (line in lines) {
            if (types == null) { val m = TYPE.find(line); if (m != null) types = split(m.groupValues[1]) }
            if (colours == null) { val m = COLOUR.find(line); if (m != null) colours = split(m.groupValues[1]) }
            if (types != null && colours != null) break
        }
        val t = types.orEmpty(); val c = colours.orEmpty()
        return (0 until minOf(MAX_FILAMENTS, maxOf(t.size, c.size))).map { i ->
            Filament(i, t.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }, FilamentLanes.normalizeColor(c.getOrNull(i)))
        }
    }

    private fun split(value: String): List<String> = value.trim().trim('"').split(';').map { it.trim().trim('"') }
}
