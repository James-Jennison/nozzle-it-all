package com.nozzleitall.desktop.prepare

import java.io.File

/**
 * Sliced output, reduced to what the preview draws: per layer, the extruding moves (x1,y1,x2,y2) and which toolhead made
 * each. Travel moves are kept separately so they can be shown on request. Bounded so a huge file can't exhaust memory.
 */
class GcodePreview(val layers: List<Layer>, val truncated: Boolean) {
    /** [kinds] is each segment's line type (an index into [FEATURES], or [OTHER]) and [speeds] its feed rate in mm/s. */
    class Layer(val z: Float, val segments: FloatArray, val tools: ByteArray, val travel: FloatArray,
                val kinds: ByteArray = ByteArray(tools.size), val speeds: FloatArray = FloatArray(tools.size)) { val count get() = tools.size }

    /** The line types the slicer names (`;TYPE:` comments), in the order the legend lists them. */
    val kindsUsed: List<Int> by lazy { val seen = BooleanArray(FEATURES.size + 1); layers.forEach { l -> l.kinds.forEach { seen[it.toInt()] = true } }; seen.indices.filter { seen[it] } }
    /** The toolheads that printed anything. */
    val toolsUsed: List<Int> by lazy { val seen = java.util.TreeSet<Int>(); layers.forEach { l -> l.tools.forEach { seen += it.toInt() } }; seen.toList() }
    /** Slowest and fastest extrusion in the file, mm/s (for the speed view's scale). */
    val speedRange: Pair<Float, Float> by lazy {
        var lo = Float.MAX_VALUE; var hi = 0f
        layers.forEach { l -> l.speeds.forEach { if (it > 0f) { if (it < lo) lo = it; if (it > hi) hi = it } } }
        if (hi == 0f) 0f to 0f else lo to hi
    }

    companion object {
        const val MAX_SEGMENTS = 4_000_000
        /** Slicer line types, with the colour each is drawn in (ARGB). The last index, [OTHER], is anything unnamed. */
        val FEATURES: List<Pair<String, Long>> = listOf(
            "Outer wall" to 0xFFFF7D38, "Inner wall" to 0xFFFFD84A, "Overhang wall" to 0xFF3D5BFF, "Sparse infill" to 0xFFC0463E, "Internal solid infill" to 0xFFB46AD8,
            "Top surface" to 0xFFE8433F, "Bottom surface" to 0xFF2FB5A8, "Bridge" to 0xFF5AA9E6, "Gap infill" to 0xFFF2F2F2, "Support" to 0xFF58C46A,
            "Support interface" to 0xFF2E8B57, "Skirt" to 0xFF9AA5B1, "Brim" to 0xFFC7B299, "Prime tower" to 0xFFE19AC8, "Custom" to 0xFF8C8C8C)
        val OTHER: Int = FEATURES.size

        /** The index for a `;TYPE:` name; slicers differ a little in wording (Wipe tower, Internal bridge, Floating vertical shell). */
        fun featureIndex(name: String): Int {
            val n = name.trim()
            FEATURES.indexOfFirst { it.first.equals(n, true) }.takeIf { it >= 0 }?.let { return it }
            return when {
                n.contains("wipe tower", true) || n.contains("prime tower", true) -> FEATURES.indexOfFirst { it.first == "Prime tower" }
                n.contains("bridge", true) -> FEATURES.indexOfFirst { it.first == "Bridge" }
                n.contains("overhang", true) -> FEATURES.indexOfFirst { it.first == "Overhang wall" }
                n.contains("support", true) -> FEATURES.indexOfFirst { it.first == "Support" }
                else -> OTHER
            }
        }

        /** A colour along blue → green → yellow → red for [t] in 0..1 (ARGB). */
        fun speedColour(t: Float): Long {
            val x = t.coerceIn(0f, 1f)
            val (r, g, b) = when {
                x < 0.33f -> { val k = x / 0.33f; Triple(0.20f, 0.35f + 0.50f * k, 1.0f - 0.55f * k) }
                x < 0.66f -> { val k = (x - 0.33f) / 0.33f; Triple(0.20f + 0.75f * k, 0.85f + 0.05f * k, 0.45f - 0.30f * k) }
                else -> { val k = (x - 0.66f) / 0.34f; Triple(0.95f, 0.90f - 0.65f * k, 0.15f - 0.05f * k) }
            }
            return 0xFF000000 or ((r * 255).toLong() shl 16) or ((g * 255).toLong() shl 8) or (b * 255).toLong()
        }

        fun parse(file: File): GcodePreview {
            val layers = ArrayList<Layer>()
            var seg = FloatArrayBuilder(); var tools = ByteArrayBuilder(); var travel = FloatArrayBuilder(); var kinds = ByteArrayBuilder(); var speeds = FloatArrayBuilder()
            var kind = OTHER; var feed = 0f
            var x = 0f; var y = 0f; var z = 0f; var e = 0f; var relativeE = false; var absolute = true; var tool = 0
            var layerZ = -1f; var total = 0; var truncated = false
            fun flush() { if (tools.size > 0 || travel.size > 0) layers += Layer(layerZ, seg.toArray(), tools.toArray(), travel.toArray(), kinds.toArray(), speeds.toArray()); seg = FloatArrayBuilder(); tools = ByteArrayBuilder(); travel = FloatArrayBuilder(); kinds = ByteArrayBuilder(); speeds = FloatArrayBuilder() }
            file.useLines { lines -> for (raw in lines) {
                if (raw.startsWith(";LAYER_CHANGE") || raw.startsWith("; CHANGE_LAYER")) { flush(); layerZ = z; continue }
                if (raw.startsWith(";TYPE:")) { kind = featureIndex(raw.substring(6)); continue }
                val line = raw.substringBefore(';').trim(); if (line.isEmpty()) continue
                val cmd = line.substringBefore(' ')
                when {
                    cmd == "G90" -> absolute = true
                    cmd == "G91" -> absolute = false
                    cmd == "M82" -> relativeE = false
                    cmd == "M83" -> relativeE = true
                    cmd.length in 2..3 && cmd[0] == 'T' && cmd.substring(1).all(Char::isDigit) -> tool = cmd.substring(1).toInt()
                    cmd == "G92" -> line.split(' ').forEach { if (it.startsWith("E")) e = it.substring(1).toFloatOrNull() ?: e }
                    cmd == "G0" || cmd == "G1" -> {
                        var nx = x; var ny = y; var nz = z; var ne: Float? = null
                        line.split(' ').drop(1).forEach { w -> val v = w.substring(1).toFloatOrNull() ?: return@forEach
                            when (w[0]) { 'X' -> nx = if (absolute) v else x + v; 'Y' -> ny = if (absolute) v else y + v; 'Z' -> nz = if (absolute) v else z + v; 'E' -> ne = v; 'F' -> feed = v / 60f } }
                        val extruding = ne?.let { if (relativeE) it > 0f else it > e } ?: false
                        ne?.let { if (!relativeE) e = it }
                        if (nz != z) { if (layerZ < 0) layerZ = nz; z = nz }
                        if ((nx != x || ny != y) && total < MAX_SEGMENTS) {
                            if (extruding) { seg.add(x, y, nx, ny); tools.add(tool.toByte()); kinds.add(kind.toByte()); speeds.add(feed); total++ } else travel.add(x, y, nx, ny)
                        } else if (total >= MAX_SEGMENTS) truncated = true
                        x = nx; y = ny
                    }
                }
            } }
            flush()
            return GcodePreview(layers.filter { it.count > 0 }, truncated)
        }
    }
}

/** How the preview colours the print. */
enum class PreviewView(val label: String) { LINE_TYPE("Line type"), TOOL("Filament"), SPEED("Speed") }

class FloatArrayBuilder { private var a = FloatArray(1024); var size = 0; private set
    fun add(vararg v: Float) { if (size + v.size > a.size) a = a.copyOf(maxOf(a.size * 2, size + v.size)); v.copyInto(a, size); size += v.size }
    fun toArray() = a.copyOf(size) }
class ByteArrayBuilder { private var a = ByteArray(256); var size = 0; private set
    fun add(v: Byte) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun toArray() = a.copyOf(size) }
