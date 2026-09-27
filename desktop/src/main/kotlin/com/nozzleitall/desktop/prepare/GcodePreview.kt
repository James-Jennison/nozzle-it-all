package com.nozzleitall.desktop.prepare

import java.io.File

/**
 * Sliced output, reduced to what the preview draws: per layer, the extruding moves (x1,y1,x2,y2) and which toolhead made
 * each. Travel moves are kept separately so they can be shown on request. Bounded so a huge file can't exhaust memory.
 */
class GcodePreview(val layers: List<Layer>, val truncated: Boolean) {
    class Layer(val z: Float, val segments: FloatArray, val tools: ByteArray, val travel: FloatArray) { val count get() = tools.size }

    companion object {
        const val MAX_SEGMENTS = 4_000_000

        fun parse(file: File): GcodePreview {
            val layers = ArrayList<Layer>()
            var seg = FloatArrayBuilder(); var tools = ByteArrayBuilder(); var travel = FloatArrayBuilder()
            var x = 0f; var y = 0f; var z = 0f; var e = 0f; var relativeE = false; var absolute = true; var tool = 0
            var layerZ = -1f; var total = 0; var truncated = false
            fun flush() { if (tools.size > 0 || travel.size > 0) layers += Layer(layerZ, seg.toArray(), tools.toArray(), travel.toArray()); seg = FloatArrayBuilder(); tools = ByteArrayBuilder(); travel = FloatArrayBuilder() }
            file.useLines { lines -> for (raw in lines) {
                if (raw.startsWith(";LAYER_CHANGE") || raw.startsWith("; CHANGE_LAYER")) { flush(); layerZ = z; continue }
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
                            when (w[0]) { 'X' -> nx = if (absolute) v else x + v; 'Y' -> ny = if (absolute) v else y + v; 'Z' -> nz = if (absolute) v else z + v; 'E' -> ne = v } }
                        val extruding = ne?.let { if (relativeE) it > 0f else it > e } ?: false
                        ne?.let { if (!relativeE) e = it }
                        if (nz != z) { if (layerZ < 0) layerZ = nz; z = nz }
                        if ((nx != x || ny != y) && total < MAX_SEGMENTS) {
                            if (extruding) { seg.add(x, y, nx, ny); tools.add(tool.toByte()); total++ } else travel.add(x, y, nx, ny)
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

class FloatArrayBuilder { private var a = FloatArray(1024); var size = 0; private set
    fun add(vararg v: Float) { if (size + v.size > a.size) a = a.copyOf(maxOf(a.size * 2, size + v.size)); v.copyInto(a, size); size += v.size }
    fun toArray() = a.copyOf(size) }
class ByteArrayBuilder { private var a = ByteArray(256); var size = 0; private set
    fun add(v: Byte) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun toArray() = a.copyOf(size) }
