package net.jamesjennison.klippercompanion

import java.io.File

/**
 * Real stats the engine's own export_gcode() already writes into the sliced file's footer -
 * verified against an actual sliced output tonight before writing this, not guessed:
 *   ; filament used [mm] = 1184.66
 *   ; total filament used [g] = 3.56
 *   ; estimated printing time (normal mode) = 14m 35s
 * Any field can be legitimately absent (a profile/engine version that doesn't emit it) - this
 * shows whatever's actually present rather than failing the whole preview over one missing line.
 */
data class GcodeStats(val printTime: String?, val filamentUsedGrams: Double?, val filamentUsedMm: Double?)

object GcodeStatsParser {
    private val timeRegex = Regex("""estimated printing time \(normal mode\)\s*=\s*(.+)""")
    private val weightRegex = Regex("""total filament used \[g]\s*=\s*([\d.]+)""")
    private val lengthRegex = Regex("""filament used \[mm]\s*=\s*([\d.]+)""")

    fun parse(file: File): GcodeStats {
        var time: String? = null
        var weight: Double? = null
        var length: Double? = null
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (!line.startsWith(";")) continue
                if (time == null) timeRegex.find(line)?.let { time = it.groupValues[1].trim() }
                if (weight == null) weightRegex.find(line)?.let { weight = it.groupValues[1].toDoubleOrNull() }
                if (length == null) lengthRegex.find(line)?.let { length = it.groupValues[1].toDoubleOrNull() }
                if (time != null && weight != null && length != null) break
            }
        }
        return GcodeStats(time, weight, length)
    }
}
