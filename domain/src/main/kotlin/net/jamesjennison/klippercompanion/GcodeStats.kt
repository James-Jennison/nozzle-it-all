package net.jamesjennison.klippercompanion

import java.io.File

/**
 * Real stats the engine's own export_gcode() writes into the sliced file's footer, verified against an actual
 * two-tool Snapmaker U1 slice (not guessed):
 *   ; filament used [mm] = 1241.54, 1226.54, 0.00, 0.00        (one value per tool)
 *   ; filament used [g] = 3.70, 3.66, 0.00, 0.00
 *   ; total filament used [g] = 7.36
 *   ; total filament change = 100
 *   ; enable_prime_tower = 0 ... ; flush_volumes_matrix = 0,84,84,84,...   (mm3 between tools)
 * Any field can be legitimately absent; whatever is present is shown rather than failing the preview.
 */
data class GcodeStats(
    val printTime: String?, val filamentUsedGrams: Double?, val filamentUsedMm: Double?,
    val toolchanges: Int? = null, val perToolGrams: List<Double> = emptyList(), val perToolMm: List<Double> = emptyList(),
    val primeTower: Boolean? = null, val flushMatrixMm3: List<Double> = emptyList(), val densityGPerCm3: Double? = null,
) {
    /** Tools that actually extrude (multi-tool prints only). */
    val toolsUsed: List<Int> get() = perToolGrams.mapIndexedNotNull { i, g -> if (g > 0.0) i else null }

    /**
     * Estimated filament flushed at toolchanges: changes x the average off-diagonal flush volume, converted with the
     * filament density. Zero when the print has no prime tower (nothing is purged, which is its own risk: see
     * [purgeNote]); null when the file lacks the numbers to estimate from. An estimate - the real waste also depends on
     * where the slicer flushes (infill, support, tower).
     */
    fun estimatedPurgeGrams(): Double? {
        val changes = toolchanges ?: return null
        if (primeTower == false) return 0.0
        val n = kotlin.math.sqrt(flushMatrixMm3.size.toDouble()).toInt()
        if (n < 2 || n * n != flushMatrixMm3.size) return null
        val offDiagonal = flushMatrixMm3.filterIndexed { i, _ -> i / n != i % n }
        val averageMm3 = offDiagonal.average().takeIf { it.isFinite() } ?: return null
        return changes * averageMm3 / 1000.0 * (densityGPerCm3 ?: 1.24)
    }

    /** What the owner should know about purging for this print, depending on how the machine changes material. */
    fun purgeNote(family: MultiToolFamily): String? = when {
        (toolchanges ?: 0) <= 0 -> null
        family == MultiToolFamily.TOOLCHANGER -> "Independent tools: nothing is purged at toolchanges (each tool keeps its own nozzle), so there is no purge waste to estimate."
        primeTower == false -> "No prime tower: nothing is purged at toolchanges, so colours can bleed at each change (turn on Prime tower in Settings to purge)."
        else -> null
    }
}

object GcodeStatsParser {
    private val timeRegex = Regex("""estimated printing time \(normal mode\)\s*=\s*(.+)""")
    private val weightRegex = Regex("""total filament used \[g]\s*=\s*([\d.]+)""")
    // Bambu's G-code layout (Bambu profiles on the Snapmaker Orca engine): the time is in the header block and the
    // weights are per filament with no total.
    private val headerTimeRegex = Regex("""^;\s*model printing time:.*total estimated time:\s*(.+)$""")
    private val perToolMmRegex = Regex("""^;\s*filament used \[mm]\s*=\s*(.+)$""")
    private val perToolGramsRegex = Regex("""^;\s*filament used \[g]\s*=\s*(.+)$""")
    private val changeRegex = Regex("""total filament change\s*=\s*(\d+)""")
    private val primeRegex = Regex("""^;\s*enable_prime_tower\s*=\s*([01])""")
    private val flushRegex = Regex("""^;\s*flush_volumes_matrix\s*=\s*(.+)$""")
    private val densityRegex = Regex("""^;\s*filament_density\s*=\s*([\d.]+)""")

    private fun numbers(text: String) = text.split(',').map { it.trim().toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0 }

    fun parse(file: File): GcodeStats {
        var time: String? = null; var weight: Double? = null; var headerTime: String? = null
        var perMm: List<Double> = emptyList(); var perG: List<Double> = emptyList()
        var changes: Int? = null; var prime: Boolean? = null; var flush: List<Double> = emptyList(); var density: Double? = null
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (!line.startsWith(";")) continue
                if (time == null) timeRegex.find(line)?.let { time = it.groupValues[1].trim() }
                if (headerTime == null) headerTimeRegex.find(line)?.let { headerTime = it.groupValues[1].trim() }
                if (weight == null) weightRegex.find(line)?.let { weight = it.groupValues[1].toDoubleOrNull() }
                if (perMm.isEmpty()) perToolMmRegex.find(line)?.let { perMm = numbers(it.groupValues[1]) }
                if (perG.isEmpty()) perToolGramsRegex.find(line)?.let { perG = numbers(it.groupValues[1]) }
                if (changes == null) changeRegex.find(line)?.let { changes = it.groupValues[1].toIntOrNull() }
                if (prime == null) primeRegex.find(line)?.let { prime = it.groupValues[1] == "1" }
                if (flush.isEmpty()) flushRegex.find(line)?.let { flush = numbers(it.groupValues[1]) }
                if (density == null) densityRegex.find(line)?.let { density = it.groupValues[1].toDoubleOrNull() }
            }
        }
        if (time == null) time = headerTime
        if (weight == null && perG.isNotEmpty()) weight = perG.sum()
        return GcodeStats(time, weight, perMm.takeIf { it.isNotEmpty() }?.sum(), changes, perG, perMm, prime, flush, density)
    }
}
