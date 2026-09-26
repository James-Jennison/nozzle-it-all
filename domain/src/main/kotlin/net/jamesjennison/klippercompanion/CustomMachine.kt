package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Per-printer overrides for a bundled slicing profile, for machines whose bed, origin or start/end G-code do not match
 * any bundled model (custom-built Klipper printers above all). Applied to a *copy* of the profile's machine.json right
 * before slicing; the bundled asset is never edited. The nozzle stays whatever the profile says (0.4 mm): the bundled
 * process settings are derived from it, so changing it here would quietly break them.
 *
 * A blank start/end G-code means "keep the profile's own".
 */
data class CustomMachine(
    val bedWidthMm: Double,
    val bedDepthMm: Double,
    val maxHeightMm: Double,
    val originAtCenter: Boolean = false,
    val startGcode: String = "",
    val endGcode: String = "",
) {
    /** A plain-language reason this cannot be used, or null when it is fine. */
    fun problem(): String? = validate(bedWidthMm, bedDepthMm, maxHeightMm, startGcode, endGcode)

    fun toJson(): JSONObject = JSONObject().put("w", bedWidthMm).put("d", bedDepthMm).put("h", maxHeightMm)
        .put("center", originAtCenter).put("start", startGcode).put("end", endGcode)

    companion object {
        const val MIN_BED_MM = 50.0
        const val MAX_BED_MM = 1000.0
        const val MIN_HEIGHT_MM = 20.0
        const val MAX_HEIGHT_MM = 1000.0
        const val MAX_GCODE_CHARS = 4000

        fun validate(width: Double, depth: Double, height: Double, start: String, end: String): String? {
            if (!width.isFinite() || !depth.isFinite() || width !in MIN_BED_MM..MAX_BED_MM || depth !in MIN_BED_MM..MAX_BED_MM)
                return "Bed width and depth must each be between ${MIN_BED_MM.toInt()} and ${MAX_BED_MM.toInt()} mm."
            if (!height.isFinite() || height !in MIN_HEIGHT_MM..MAX_HEIGHT_MM)
                return "Maximum height must be between ${MIN_HEIGHT_MM.toInt()} and ${MAX_HEIGHT_MM.toInt()} mm."
            if (start.length > MAX_GCODE_CHARS || end.length > MAX_GCODE_CHARS) return "Start and end G-code are each limited to $MAX_GCODE_CHARS characters."
            if (start.any { it == '\u0000' } || end.any { it == '\u0000' }) return "G-code cannot contain control characters."
            return null
        }

        /** Reads a saved value back, or null when absent or invalid: a damaged entry must fall back to the bundled profile, never to a wrong bed. */
        fun fromJson(o: JSONObject?): CustomMachine? {
            o ?: return null
            val c = CustomMachine(o.optDouble("w", Double.NaN), o.optDouble("d", Double.NaN), o.optDouble("h", Double.NaN),
                o.optBoolean("center"), o.optString("start"), o.optString("end"))
            return c.takeIf { it.problem() == null }
        }
    }
}

private fun mm(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

/**
 * The bed as OrcaSlicer's own `printable_area` list: a rectangle from the corner, or centred on the origin (negative
 * coordinates) for machines such as deltas and some CoreXY builds. G-code coordinates follow these numbers directly.
 */
fun customPrintableArea(c: CustomMachine): List<String> {
    val (x0, y0) = if (c.originAtCenter) (-c.bedWidthMm / 2) to (-c.bedDepthMm / 2) else 0.0 to 0.0
    val x1 = x0 + c.bedWidthMm
    val y1 = y0 + c.bedDepthMm
    return listOf("${mm(x0)}x${mm(y0)}", "${mm(x1)}x${mm(y0)}", "${mm(x1)}x${mm(y1)}", "${mm(x0)}x${mm(y1)}")
}

/** A copy of [machineJson] with the custom bed, height and (when given) start/end G-code applied. Throws IllegalArgumentException if [c] is invalid. */
fun applyCustomMachine(machineJson: String, c: CustomMachine): String {
    c.problem()?.let { throw IllegalArgumentException(it) }
    val o = JSONObject(machineJson)
    o.put("printable_area", JSONArray(customPrintableArea(c)))
    o.put("printable_height", mm(c.maxHeightMm))
    // Any exclusion zones belong to the profile's own bed, not this one. "0x0" is OrcaSlicer's own "no exclusion" value.
    if (o.has("bed_exclude_area")) o.put("bed_exclude_area", JSONArray().put("0x0"))
    if (c.startGcode.isNotBlank()) o.put("machine_start_gcode", c.startGcode.replace("\r\n", "\n").replace('\r', '\n'))
    if (c.endGcode.isNotBlank()) o.put("machine_end_gcode", c.endGcode.replace("\r\n", "\n").replace('\r', '\n'))
    return o.toString(4)
}
