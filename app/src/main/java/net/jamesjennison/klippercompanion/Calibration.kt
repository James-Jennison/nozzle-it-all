package net.jamesjennison.klippercompanion

import java.io.File

// Phase 9e: calibration prints. Each test is a generated model plus the slicer overrides it needs and (for the
// towers) a G-code post-processing step that changes a machine setting with height - the standard way these tests
// work. The spec is saved on the project ("calibration" column) so the editor knows to post-process after slicing.
enum class CalibrationKind(val code: String, val label: String, val klipperOnly: Boolean, val help: String) {
    TEMPERATURE("temp", "Temperature tower", false, "Prints a tower whose nozzle temperature steps down each section. Pick the section with the best surface and bridging."),
    PRESSURE_ADVANCE("pa", "Pressure advance tower", true, "Klipper only. Ramps pressure advance with height; measure the height where corners look best and set advance = height × 0.005."),
    FLOW("flow", "Flow cube", false, "A 20 mm open-top cube. Measure the wall thickness with calipers and compare with the expected line width."),
}

data class CalibrationSpec(val kind: CalibrationKind, val startTemp: Int = 230, val stepTemp: Int = -5, val sections: Int = 5) {
    fun encode(): String = when (kind) { CalibrationKind.TEMPERATURE -> "temp:$startTemp:$stepTemp:$sections"; else -> kind.code }
    companion object {
        fun decode(text: String?): CalibrationSpec? {
            if (text.isNullOrBlank()) return null
            val p = text.split(':')
            return when (p[0]) {
                "temp" -> if (p.size == 4) {
                    val start = p[1].toIntOrNull(); val step = p[2].toIntOrNull(); val sections = p[3].toIntOrNull()
                    if (start != null && step != null && sections != null) CalibrationSpec(CalibrationKind.TEMPERATURE, start, step, sections).takeIf { it.valid() } else null
                } else null
                "pa" -> CalibrationSpec(CalibrationKind.PRESSURE_ADVANCE)
                "flow" -> CalibrationSpec(CalibrationKind.FLOW)
                else -> null
            }
        }
    }
    /** Every temperature in the tower must be a sane nozzle temperature. */
    fun valid(): Boolean = kind != CalibrationKind.TEMPERATURE || (sections in 2..12 && step != 0 && (0 until sections).all { startTemp + it * stepTemp in 150..320 })
    private val step get() = stepTemp
}

object Calibration {
    const val BASE_HEIGHT = 1.0f
    const val SECTION_HEIGHT = 10.0f
    private const val PA_HEIGHT = 40.0f

    private fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): FloatArray {
        val c = (0 until 8).map { i -> floatArrayOf(if (i and 1 != 0) x1 else x0, if (i and 2 != 0) y1 else y0, if (i and 4 != 0) z1 else z0) }
        fun q(a: Int, b: Int, cc: Int, d: Int) = listOf(a, b, cc, a, cc, d)
        val faces = listOf(q(0, 2, 3, 1), q(4, 5, 7, 6), q(0, 1, 5, 4), q(1, 3, 7, 5), q(3, 2, 6, 7), q(2, 0, 4, 6)).flatten()
        return faces.flatMap { c[it].toList() }.toFloatArray()
    }

    /** A thin base plate plus [sections] stacked blocks, each with a 45° overhang wedge on its +X side. */
    fun temperatureTower(sections: Int): TriMesh {
        var v = box(-10f, -8f, 0f, 10f, 8f, BASE_HEIGHT)
        for (i in 0 until sections) {
            val z0 = BASE_HEIGHT + i * SECTION_HEIGHT
            v += box(-6f, -6f, z0, 6f, 6f, z0 + SECTION_HEIGHT - 0.0f)
            // wedge under a 4 mm overhang tab at the top of the section (45° underside)
            val zt = z0 + SECTION_HEIGHT - 2f
            v += wedge(6f, zt - 4f, zt + 2f)
        }
        return TriMesh(v)
    }

    // a triangular prism sticking out of the +X face: 4 mm out, apex against the block at [zBottom], flat top at [zTop]
    private fun wedge(x: Float, zBottom: Float, zTop: Float): FloatArray {
        val out = 4f; val y0 = -6f; val y1 = 6f
        val a0 = floatArrayOf(x, y0, zBottom + 2f); val a1 = floatArrayOf(x + out, y0, zTop); val a2 = floatArrayOf(x, y0, zTop)
        val b0 = floatArrayOf(x, y1, zBottom + 2f); val b1 = floatArrayOf(x + out, y1, zTop); val b2 = floatArrayOf(x, y1, zTop)
        fun tri(a: FloatArray, b: FloatArray, c: FloatArray) = a + b + c
        return tri(a0, a2, a1) + tri(b0, b1, b2) + tri(a0, a1, b1) + tri(a0, b1, b0) + tri(a1, a2, b2) + tri(a1, b2, b1) + tri(a0, b0, b2) + tri(a0, b2, a2)
    }

    fun pressureAdvanceTower(): TriMesh = TriMesh(box(-15f, -15f, 0f, 15f, 15f, PA_HEIGHT).let { hollowSquare(it) })
    // a square tube reads corners best; approximated by a solid block - the slicer's wall/infill settings make it a shell
    private fun hollowSquare(v: FloatArray) = v

    fun flowCube(): TriMesh = TriMesh(box(-10f, -10f, 0f, 10f, 10f, 20f))

    fun mesh(spec: CalibrationSpec): TriMesh = when (spec.kind) {
        CalibrationKind.TEMPERATURE -> temperatureTower(spec.sections)
        CalibrationKind.PRESSURE_ADVANCE -> pressureAdvanceTower()
        CalibrationKind.FLOW -> flowCube()
    }

    /** Slicer overrides the test needs (validated catalog keys, plus the temperature). */
    fun overrides(spec: CalibrationSpec): Map<String, String> = when (spec.kind) {
        CalibrationKind.TEMPERATURE -> mapOf("nozzle_temperature" to spec.startTemp.toString(), "nozzle_temperature_initial_layer" to spec.startTemp.toString(), "wall_loops" to "2", "sparse_infill_density" to "10%")
        CalibrationKind.PRESSURE_ADVANCE -> mapOf("wall_loops" to "2", "sparse_infill_density" to "0%", "top_shell_layers" to "0", "enable_prime_tower" to "0")
        CalibrationKind.FLOW -> mapOf("wall_loops" to "2", "sparse_infill_density" to "0%", "top_shell_layers" to "0", "bottom_shell_layers" to "3")
    }

    /** Temperature (°C) for the layer at height [z]. */
    fun temperatureAt(spec: CalibrationSpec, z: Float): Int {
        val idx = if (z <= BASE_HEIGHT + 1e-3f) 0 else ((z - BASE_HEIGHT - 1e-3f) / SECTION_HEIGHT).toInt().coerceIn(0, spec.sections - 1)
        return spec.startTemp + idx * spec.stepTemp
    }

    /** Inserts the height-dependent commands. Pure text transform; leaves everything else untouched. */
    fun postProcess(spec: CalibrationSpec, gcode: String): String = when (spec.kind) {
        CalibrationKind.TEMPERATURE -> {
            val out = StringBuilder(gcode.length + 512)
            var current = spec.startTemp
            for (line in gcode.lineSequence().toList().let { ls -> if (gcode.endsWith("\n")) ls.dropLast(1) else ls }) {
                out.append(line).append('\n')
                if (line.startsWith(";Z:")) {
                    val z = line.removePrefix(";Z:").trim().toFloatOrNull()
                    if (z != null) { val t = temperatureAt(spec, z); if (t != current) { out.append("M104 S").append(t).append(" ; calibration: temperature tower\n"); current = t } }
                }
            }
            out.toString()
        }
        CalibrationKind.PRESSURE_ADVANCE -> {
            var done = false
            val out = StringBuilder(gcode.length + 256)
            for (line in gcode.lineSequence().toList().let { ls -> if (gcode.endsWith("\n")) ls.dropLast(1) else ls }) {
                out.append(line).append('\n')
                if (!done && line.startsWith(";Z:")) {
                    out.append("SET_VELOCITY_LIMIT SQUARE_CORNER_VELOCITY=1 ACCEL=500 ; calibration: pressure advance tower\n")
                    out.append("TUNING_TOWER COMMAND=SET_PRESSURE_ADVANCE PARAMETER=ADVANCE START=0 FACTOR=.005 SKIP=$BASE_HEIGHT\n")
                    done = true
                }
            }
            out.toString()
        }
        CalibrationKind.FLOW -> gcode
    }

    /** Rewrites a sliced plain-G-code file in place. Bundles (zips) and non-text files are left alone. */
    fun applyToFile(file: File, spec: CalibrationSpec) {
        if (spec.kind == CalibrationKind.FLOW) return
        val text = file.readText()
        val processed = postProcess(spec, text)
        if (processed != text) file.writeText(processed)
    }
}
