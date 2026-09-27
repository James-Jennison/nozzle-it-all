package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Flushing volumes between filaments (mm³ purged when one filament replaces another), worked out from their colours by
 * each printer's own slicer (owner rule: follow the printer's own slicer where they differ). Three methods:
 *  - [Method.SNAPMAKER]: Snapmaker Orca at the engine pin (the U1 and other Snapmaker printers):
 *    src/libslic3r/FlushVolCalc.cpp with RGB2HSV (src/slic3r/Utils/ColorSpaceConvert.cpp); support limits 230/420, cap 800.
 *  - [Method.ORCA]: OrcaSlicer 824b216f (every profile from Orca's library): FlushVolCalc.cpp first asks
 *    FlushVolPredictor.cpp's measured flushes (resources/flush/flush_data_*.txt, the machine's nozzle_flush_dataset),
 *    then the same colour formula; support limits 230/700, cap 20000.
 *  - [Method.ELEGOO]: ElegooSlicer 2d507e39a9 (Elegoo printers): ORCA plus its per-printer overrides
 *    (FlushVolumeRules.cpp, resources/profiles/Elegoo/flush/flush_volumes.json) snapped to StandardColorMatcher.cpp's palette.
 * Matrix rules: Plater.cpp Sidebar::auto_calc_flushing_volumes; minimums: get_min_flush_volumes; the multiplier range:
 * WipeTowerDialog.cpp. The engine applies flush_multiplier itself. Every step keeps upstream's float/double precision.
 * schemas/fixtures/flush-volumes.json (scripts/flush_volumes_golden.sh compiles upstream's own code) pins all three. The
 * desktop and Android share this code; the Web App ports it (web/src/project/flush.ts). AGPL-3.0 upstream code.
 */
object FlushVolumes {
    enum class Method(val minFromSupport: Int, val max: Int) { SNAPMAKER(420, 800), ORCA(700, 20000), ELEGOO(700, 20000) }

    const val TO_SUPPORT = 230
    const val MIN_MULTIPLIER = 0f
    const val MAX_MULTIPLIER = 3f
    /** The engine's flush_multiplier default (PrintConfig.cpp), for profiles that don't set it. */
    const val ENGINE_DEFAULT_MULTIPLIER = 0.3f

    // Kept for callers of the Snapmaker-only API.
    const val MIN_FROM_SUPPORT = 420
    const val MAX = 800

    /** Everything the calculation needs besides the colours, from the machine and each slot's filament profile. */
    data class Setup(val minimum: List<Int> = emptyList(), val support: List<Boolean> = emptyList(), val method: Method = Method.SNAPMAKER,
                     val dataset: Int = 0, val printer: String? = null) {
        fun matrix(colours: List<String?>): List<Int> = matrix(colours, minimum, support, method, dataset, printer)
        fun recalcSlot(current: List<Int>, slot: Int, colours: List<String?>): List<Int> = recalcSlot(current, slot, colours, minimum, support, method, dataset, printer)
        val max get() = method.max
    }

    fun setup(machine: JSONObject?, filaments: List<JSONObject?>): Setup = Setup(minimumVolumes(machine, filaments), filaments.map(::isSupport),
        methodFor(machine), datasetFor(machine), machine?.optString("name")?.takeIf { it.isNotBlank() })

    /** Whose calculation a printer uses, from its machine profile: its own slicer's. */
    fun methodFor(machine: JSONObject?): Method {
        val model = listOf("printer_model", "name", "printer_settings_id").firstNotNullOfOrNull { k -> machine?.optString(k)?.takeIf { it.isNotBlank() } }.orEmpty()
        return when {
            model.startsWith("Snapmaker", true) -> Method.SNAPMAKER
            model.startsWith("Elegoo", true) -> Method.ELEGOO
            else -> Method.ORCA
        }
    }

    /** The machine's nozzle_flush_dataset (0 when unset). */
    fun datasetFor(machine: JSONObject?): Int = first(machine, "nozzle_flush_dataset")?.toIntOrNull() ?: 0

    // --- The colour formula (FlushVolCalc.cpp, identical in all three slicers) ------------------------------------------
    private fun toRadians(degree: Float): Float = (degree / 180f * Math.PI).toFloat()
    private fun luminance(r: Float, g: Float, b: Float): Float = (r * 0.3 + g * 0.59 + b * 0.11).toFloat()
    private fun thirdEdge(a: Float, b: Float, degreeAb: Float): Float = sqrt(a * a + b * b - 2 * a * b * kotlin.math.cos(toRadians(degreeAb)))

    private fun hsv(r: Float, g: Float, b: Float): FloatArray {
        val cmax = max(max(r, g), b); val cmin = min(min(r, g), b); val delta = cmax - cmin
        val h = when {
            kotlin.math.abs(delta) < 0.001f -> 0f
            cmax == r -> 60f * ((g - b) / delta % 6f)
            cmax == g -> 60f * ((b - r) / delta + 2)
            else -> 60f * ((r - g) / delta + 4)
        }
        val s = if (kotlin.math.abs(cmax) < 0.001f) 0f else delta / cmax
        return floatArrayOf(h, s, cmax)
    }

    private fun deltaHs(h1: Float, s1: Float, v1: Float, h2: Float, s2: Float, v2: Float): Float {
        val h1r = toRadians(h1); val h2r = toRadians(h2)
        val dx = kotlin.math.cos(h1r) * s1 * v1 - kotlin.math.cos(h2r) * s2 * v2
        val dy = kotlin.math.sin(h1r) * s1 * v1 - kotlin.math.sin(h2r) * s2 * v2
        return min(1.2f, sqrt(dx * dx + dy * dy))
    }

    /** The colour formula's volume before the minimum is added (at least 60). */
    private fun formula(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Float {
        val sr = r1 / 255f; val sg = g1 / 255f; val sb = b1 / 255f
        val dr = r2 / 255f; val dg = g2 / 255f; val db = b2 / 255f
        val from = hsv(sr, sg, sb); val to = hsv(dr, dg, db)
        var hsDist = deltaHs(from[0], from[1], from[2], to[0], to[1], to[2])
        // Colour differences show more going to a bright colour, and from a dark one.
        val fromLumi = luminance(sr, sg, sb); val toLumi = luminance(dr, dg, db)
        val lumiFlush = if (toLumi >= fromLumi) Math.pow((toLumi - fromLumi).toDouble(), 0.7).toFloat() * 560f
        else {
            hsDist = min((0.67 * to[2] + 0.33 * from[2]).toFloat(), hsDist)
            (fromLumi - toLumi) * 80f
        }
        return max(thirdEdge(230f * hsDist, lumiFlush, 120f), 60f)
    }

    /** "#RRGGBB" or "#RRGGBBAA" to (a, r, g, b); anything unreadable is opaque white. */
    fun argb(hex: String?): IntArray {
        val s = hex?.trim()?.removePrefix("#").orEmpty()
        if ((s.length != 6 && s.length != 8) || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return intArrayOf(255, 255, 255, 255)
        val v = s.chunked(2).map { it.toInt(16) }
        return intArrayOf(v.getOrElse(3) { 255 }, v[0], v[1], v[2])
    }

    /**
     * calc_flush_vol: the volume to flush [from] out with [to]. [dataset] is the machine's nozzle_flush_dataset and
     * [printer] its preset name (ELEGOO's overrides are per printer).
     */
    fun calc(from: String?, to: String?, minVolume: Int, method: Method = Method.SNAPMAKER, dataset: Int = 0, printer: String? = null,
             maxVolume: Int = method.max): Int {
        val f = argb(from); val t = argb(to)
        // Transparent materials are treated as white.
        if (f[0] == 0) { f[1] = 255; f[2] = 255; f[3] = 255 }
        if (t[0] == 0) { t[1] = 255; t[2] = 255; t[3] = 255 }
        if (method == Method.SNAPMAKER) return min((formula(f[1], f[2], f[3], t[1], t[2], t[3]) + minVolume).toInt(), maxVolume)
        if (method == Method.ELEGOO && !printer.isNullOrEmpty())
            ElegooRules.lookup(printer, "#%02X%02X%02X".format(f[1], f[2], f[3]), "#%02X%02X%02X".format(t[1], t[2], t[3]))?.let { return it }
        val predictor = Predictor.of(dataset)
        if (dataset != 0) predictor?.predict(f[1], f[2], f[3], t[1], t[2], t[3])?.let { return min(it.toInt(), maxVolume) }
        // calc_flush_vol_rgb: the measured flush for dataset 0, else the formula (returned as an int).
        val measured = if (dataset == 0) predictor?.predict(f[1], f[2], f[3], t[1], t[2], t[3])?.toInt() else null
        var volume: Float = (measured ?: formula(f[1], f[2], f[3], t[1], t[2], t[3]).toInt()).toFloat()
        // As upstream: luminance of the raw 0-255 values against 0-1 thresholds.
        val fromDark = luminance(f[1].toFloat(), f[2].toFloat(), f[3].toFloat()) > 180f / 255f
        val toLight = luminance(t[1].toFloat(), t[2].toFloat(), t[3].toFloat()) < 75f / 255f
        if (dataset != 0 && fromDark && toLight) volume = (volume * 1.3).toFloat()
        volume += minVolume
        return min(volume.toInt(), maxVolume)
    }

    /**
     * The whole flushing-volume matrix (row = from, column = to; flush_volumes_matrix order), as auto_calc_flushing_volumes
     * fills it for every slot: into a support filament is [TO_SUPPORT]; out of one at least the method's minimum.
     */
    fun matrix(colours: List<String?>, minimum: List<Int>, support: List<Boolean> = emptyList(), method: Method = Method.SNAPMAKER,
               dataset: Int = 0, printer: String? = null): List<Int> {
        val n = colours.size
        return List(n * n) { i ->
            val from = i / n; val to = i % n
            when {
                from == to -> 0
                support.getOrElse(to) { false } -> TO_SUPPORT
                else -> calc(colours[from], colours[to], minimum.getOrElse(from) { 0 }, method, dataset, printer)
                    .let { v -> if (support.getOrElse(from) { false }) max(v, method.minFromSupport) else v }
            }
        }
    }

    /**
     * Recomputes only row and column [slot] (a slot whose colour or filament changed), keeping the other entries, as
     * upstream does so a user's own edits to other pairs survive.
     */
    fun recalcSlot(current: List<Int>, slot: Int, colours: List<String?>, minimum: List<Int>, support: List<Boolean> = emptyList(),
                   method: Method = Method.SNAPMAKER, dataset: Int = 0, printer: String? = null): List<Int> {
        val n = colours.size
        val fresh = matrix(colours, minimum, support, method, dataset, printer)
        if (current.size != n * n) return fresh
        return List(n * n) { i -> if (i / n == slot || i % n == slot) fresh[i] else current[i] }
    }

    private fun first(o: JSONObject?, k: String): String? =
        o?.opt(k)?.let { v -> (v as? JSONArray)?.optString(0) ?: v.toString() }?.trim()?.takeIf { it.isNotEmpty() && it != "nil" }

    /** get_min_flush_volumes: per filament, the nozzle's volume less a long retraction's worth when cutting is enabled. */
    fun minimumVolumes(machine: JSONObject?, filaments: List<JSONObject?>): List<Int> {
        val nozzleVolume = first(machine, "nozzle_volume")?.toDoubleOrNull()?.toInt() ?: 0
        val level = first(machine, "enable_long_retraction_when_cut")?.toIntOrNull() ?: 0
        val machineOn = first(machine, "long_retractions_when_cut")?.let { it == "1" || it == "true" } ?: false
        val machineDistance = first(machine, "retraction_distances_when_cut")?.toDoubleOrNull() ?: 18.0
        val machineRetract = if (level != 0 && machineOn) machineDistance.toInt() else 0
        return filaments.map { f ->
            // The filament's own switch: off, on, or unset (nil), which keeps the printer's.
            val filamentOn = first(f, "filament_long_retractions_when_cut")?.let { it == "1" || it == "true" }
            val filamentDistance = first(f, "filament_retraction_distances_when_cut")?.toDoubleOrNull()
            val retract = when {
                filamentOn == false -> 0
                filamentOn == true && level == 2 -> (filamentDistance ?: machineDistance).toInt() // EnableFilament
                else -> machineRetract
            }
            (nozzleVolume - Math.PI * 1.75 * 1.75 / 4 * retract).toInt()
        }
    }

    /** Whether a filament profile is a support material (filament_is_support). */
    fun isSupport(filament: JSONObject?): Boolean = first(filament, "filament_is_support") == "1"

    private fun resource(path: String): String? = FlushVolumes::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }

    /** FlushVolPredictor.cpp: measured flushes between a list of colours, used when both colours are near one of them. */
    private class Predictor(text: String) {
        private val colours = ArrayList<IntArray>()
        private val flush = HashMap<Long, Float>()
        val valid: Boolean

        init {
            val lines = text.lines()
            fun rgb(h: String): IntArray? = if (h.length != 7 || h[0] != '#') null else runCatching { intArrayOf(h.substring(1, 3).toInt(16), h.substring(3, 5).toInt(16), h.substring(5, 7).toInt(16)) }.getOrNull()
            var ok = lines.size >= 2
            lines.getOrNull(1)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.forEach { c -> rgb(c)?.let(colours::add) ?: run { ok = false } }
            for (line in lines.drop(3)) {
                if (line.isBlank()) continue
                val p = line.trim().split(Regex("\\s+"))
                val a = p.getOrNull(0)?.let(::rgb); val b = p.getOrNull(1)?.let(::rgb); val v = p.getOrNull(2)?.toFloatOrNull()
                if (a == null || b == null || v == null) { ok = false; break }
                flush.putIfAbsent(key(a[0], a[1], a[2], b[0], b[1], b[2]), v)
            }
            valid = ok
        }

        private fun key(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Long =
            (r1.toLong() shl 40) or (g1.toLong() shl 32) or (b1.toLong() shl 24) or (r2.toLong() shl 16) or (g2.toLong() shl 8) or b2.toLong()

        fun predict(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Float? {
            if (!valid) return null
            val from = colours.firstOrNull { distance(it, intArrayOf(r1, g1, b1)) <= 5.0f } ?: return null
            val to = colours.firstOrNull { distance(it, intArrayOf(r2, g2, b2)) <= 5.0f } ?: return null
            return flush[key(from[0], from[1], from[2], to[0], to[1], to[2])]
        }

        companion object {
            private val cache = HashMap<Int, Predictor?>()
            private val files = mapOf(0 to "flush_data_standard.txt", 1 to "flush_data_dual_standard.txt", 2 to "flush_data_dual_highflow.txt")
            fun of(dataset: Int): Predictor? = synchronized(cache) {
                if (dataset !in cache) cache[dataset] = files[dataset]?.let { resource("/flush/$it") }?.let(::Predictor)?.takeIf { it.valid }
                cache[dataset]
            }

            // FlushPredict::RGB2LAB and calc_color_distance (CIEDE2000), in double precision as upstream, returned as float.
            private fun lab(c: IntArray): DoubleArray {
                fun gamma(x: Double) = if (x > 0.04045) Math.pow((x + 0.055) / 1.055, 2.4) else x / 12.92
                val r = gamma(c[0] / 255.0) * 100; val g = gamma(c[1] / 255.0) * 100; val b = gamma(c[2] / 255.0) * 100
                val x = 0.412453 * r + 0.357580 * g + 0.180423 * b
                val y = 0.212671 * r + 0.715160 * g + 0.072169 * b
                val z = 0.019334 * r + 0.119193 * g + 0.950227 * b
                val threshold = 0.008856f.toDouble() // a float literal in upstream's double threshold
                fun f(t: Double) = if (t > threshold) Math.pow(t, 1.0 / 3.0) else 7.787 * t + 0.137931
                val xn = f(x / 95.0489); val yn = f(y / 100.0); val zn = f(z / 108.8840)
                return doubleArrayOf(116.0 * yn - 16.0, 500.0 * (xn - yn), 200.0 * (yn - zn))
            }

            private fun deg(d: Double) = d * Math.PI / 180.0

            fun distance(c1: IntArray, c2: IntArray): Float {
                val l1 = lab(c1); val l2 = lab(c2)
                val pow25to7 = Math.pow(25.0, 7.0)
                val cc1 = sqrt(l1[1] * l1[1] + l1[2] * l1[2]); val cc2 = sqrt(l2[1] * l2[1] + l2[2] * l2[2])
                val cMean7 = Math.pow((cc1 + cc2) / 2.0, 7.0)
                val g = 0.5 * (1 - sqrt(cMean7 / (cMean7 + pow25to7)))
                val a1 = (1.0 + g) * l1[1]; val a2 = (1.0 + g) * l2[1]
                val pc1 = sqrt(a1 * a1 + l1[2] * l1[2]); val pc2 = sqrt(a2 * a2 + l2[2] * l2[2])
                fun hue(a: Double, b: Double) = if (a == 0.0 && b == 0.0) 0.0 else Math.atan2(b, a).let { if (it < 0) it + Math.PI * 2 else it }
                val h1 = hue(a1, l1[2]); val h2 = hue(a2, l2[2])
                val dL = l2[0] - l1[0]; val dC = pc2 - pc1
                val cMulti = pc1 * pc2
                val dH = if (cMulti == 0.0) 0.0 else {
                    var d = h2 - h1
                    if (d < -Math.PI) d += 2 * Math.PI else if (d > Math.PI) d -= 2 * Math.PI
                    2 * sqrt(cMulti) * Math.sin(d / 2.0)
                }
                val lMean = (l1[0] + l2[0]) / 2.0; val cMean = (pc1 + pc2) / 2.0
                val hSum = h1 + h2
                val hMean = if (pc1 * pc2 == 0.0) hSum else if (kotlin.math.abs(h1 - h2) <= Math.PI) hSum / 2
                    else if (hSum < 2 * Math.PI) (hSum + 2 * Math.PI) / 2.0 else (hSum - 2 * Math.PI) / 2.0
                val t = 1 - 0.17 * Math.cos(hMean - deg(30.0)) + 0.24 * Math.cos(2 * hMean) + 0.32 * Math.cos(3 * hMean + deg(6.0)) - 0.2 * Math.cos(4 * hMean - deg(63.0))
                val dTheta = deg(30.0) * Math.exp(-Math.pow((hMean - deg(275.0)) / deg(25.0), 2.0))
                val cMeanP7 = Math.pow(cMean, 7.0)
                val rC = 2 * sqrt(cMeanP7 / (cMeanP7 + pow25to7))
                val lm2 = Math.pow(lMean - 50, 2.0)
                val sL = 1 + (0.015 * lm2) / sqrt(20 + lm2); val sC = 1 + 0.045 * cMean; val sH = 1 + 0.015 * cMean * t
                val rT = -Math.sin(2 * dTheta) * rC
                return sqrt(Math.pow(dL / sL, 2.0) + Math.pow(dC / sC, 2.0) + Math.pow(dH / sH, 2.0) + (rT * (dC / sC) * (dH / sH))).toFloat()
            }
        }
    }

    /**
     * ElegooSlicer's per-printer overrides (FlushVolumeRules.cpp): entries and inputs are snapped to the nearest colour of
     * StandardColorMatcher's palette (CIEDE2000), an exact snapped match wins, higher priority first, later rule on a tie.
     */
    private object ElegooRules {
        private class Rule(val printers: List<String>, val priority: Int, val entries: List<Triple<String, String, Int>>)

        // StandardColorMatcher.cpp default_palette().
        private val palette = listOf("#FFFFFF", "#FFF242", "#DBF47A", "#09CC3A", "#077747", "#0B6283", "#0BE2A0", "#74D9F3", "#48A7FA",
            "#2850DF", "#433089", "#A03BF7", "#F32FF8", "#D4B1DD", "#F95D77", "#F72221", "#7C4C00", "#F88D36", "#FCEBD7", "#D2C5A3",
            "#AF7832", "#898989", "#BCBCBC", "#000000")
        private val paletteLab by lazy { palette.map { lab(parse(it)!!) } }

        private val rules: List<Rule> by lazy {
            val root = resource("/flush/elegoo_flush_volumes.json")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@lazy emptyList()
            val list = root.optJSONArray("rules") ?: return@lazy emptyList()
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { r ->
                val printers = r.optJSONArray("printer_name")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
                val entries = r.optJSONArray("flush_volume_entries")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it) } }.orEmpty()
                    .filter { it.length() >= 3 && it.opt(0) is String && it.opt(1) is String && it.opt(2) is Number }
                    .mapNotNull { e -> val f = match(e.getString(0)); val t = match(e.getString(1)); if (f == null || t == null) null else Triple(f, t, e.getDouble(2).toInt()) }
                Rule(printers, r.optInt("priority", 0), entries)
            }
        }

        fun lookup(printer: String, from: String, to: String): Int? {
            val f = match(from) ?: return null; val t = match(to) ?: return null
            var best: Rule? = null; var volume = 0
            for (rule in rules) {
                if (printer !in rule.printers) continue
                val hit = rule.entries.firstOrNull { it.first == f && it.second == t } ?: continue
                if (best == null || rule.priority >= best.priority) { best = rule; volume = hit.third }
            }
            return if (best == null) null else volume
        }

        private fun parse(hex: String): DoubleArray? {
            val s = if (hex.startsWith("#")) hex.substring(1) else hex
            if (s.length != 6 || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            return doubleArrayOf(s.substring(0, 2).toInt(16).toDouble(), s.substring(2, 4).toInt(16).toDouble(), s.substring(4, 6).toInt(16).toDouble())
        }

        private fun match(hex: String): String? {
            val input = lab(parse(hex) ?: return null)
            var best = Double.MAX_VALUE; var index = 0
            paletteLab.forEachIndexed { i, p -> val d = deltaE2000(input, p); if (d < best) { best = d; index = i } }
            return palette[index]
        }

        private fun lab(rgb: DoubleArray): DoubleArray {
            fun gamma(v: Double) = if (v > 0.04045) Math.pow((v + 0.055) / 1.055, 2.4) else v / 12.92
            val r = gamma(rgb[0] / 255.0); val g = gamma(rgb[1] / 255.0); val b = gamma(rgb[2] / 255.0)
            val x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) * 100.0
            val y = (r * 0.2126729 + g * 0.7151522 + b * 0.0721750) * 100.0
            val z = (r * 0.0193339 + g * 0.1191920 + b * 0.9503041) * 100.0
            fun pivot(v: Double) = if (v > 0.008856) Math.pow(v, 1.0 / 3.0) else 7.787 * v + 16.0 / 116.0
            val fx = pivot(x / 95.047); val fy = pivot(y / 100.000); val fz = pivot(z / 108.883)
            return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
        }

        private fun deltaE2000(l1: DoubleArray, l2: DoubleArray): Double {
            val c1 = sqrt(l1[1] * l1[1] + l1[2] * l1[2]); val c2 = sqrt(l2[1] * l2[1] + l2[2] * l2[2])
            val cBar7 = Math.pow((c1 + c2) / 2.0, 7.0)
            val g = 0.5 * (1.0 - sqrt(cBar7 / (cBar7 + Math.pow(25.0, 7.0))))
            val a1p = (1.0 + g) * l1[1]; val a2p = (1.0 + g) * l2[1]
            val c1p = sqrt(a1p * a1p + l1[2] * l1[2]); val c2p = sqrt(a2p * a2p + l2[2] * l2[2])
            fun huePrime(a: Double, b: Double) = (Math.atan2(b, a) * 180.0 / Math.PI).let { if (it < 0.0) it + 360.0 else it }
            val h1p = huePrime(a1p, l1[2]); val h2p = huePrime(a2p, l2[2])
            val dLp = l2[0] - l1[0]; val dCp = c2p - c1p; val hDiff = h2p - h1p
            val dhp = if (c1p * c2p != 0.0) { if (kotlin.math.abs(hDiff) <= 180.0) hDiff else if (hDiff > 180.0) hDiff - 360.0 else hDiff + 360.0 } else 0.0
            val dHp = 2.0 * sqrt(c1p * c2p) * Math.sin((dhp * Math.PI) / 360.0)
            val lBarP = (l1[0] + l2[0]) / 2.0; val cBarP = (c1p + c2p) / 2.0
            var hBarP = if (c1p * c2p == 0.0) h1p + h2p else if (kotlin.math.abs(hDiff) <= 180.0) (h1p + h2p) / 2.0 else (h1p + h2p + 360.0) / 2.0
            if (hBarP >= 360.0) hBarP -= 360.0
            val t = 1.0 - 0.17 * Math.cos((hBarP - 30.0) * Math.PI / 180.0) + 0.24 * Math.cos((2.0 * hBarP) * Math.PI / 180.0) +
                0.32 * Math.cos((3.0 * hBarP + 6.0) * Math.PI / 180.0) - 0.20 * Math.cos((4.0 * hBarP - 63.0) * Math.PI / 180.0)
            val dTheta = 30.0 * Math.exp(-Math.pow((hBarP - 275.0) / 25.0, 2.0))
            val cBarP7 = Math.pow(cBarP, 7.0)
            val rC = 2.0 * sqrt(cBarP7 / (cBarP7 + Math.pow(25.0, 7.0)))
            val sL = 1.0 + (0.015 * Math.pow(lBarP - 50.0, 2.0)) / sqrt(20.0 + Math.pow(lBarP - 50.0, 2.0))
            val sC = 1.0 + 0.045 * cBarP; val sH = 1.0 + 0.015 * cBarP * t
            val rT = -Math.sin((2.0 * dTheta) * Math.PI / 180.0) * rC
            return sqrt(Math.pow(dLp / sL, 2.0) + Math.pow(dCp / sC, 2.0) + Math.pow(dHp / sH, 2.0) + rT * (dCp / sC) * (dHp / sH))
        }
    }
}
