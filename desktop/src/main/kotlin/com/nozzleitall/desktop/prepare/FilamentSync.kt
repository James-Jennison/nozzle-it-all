package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.ProjectManifest
import com.nozzleitall.project.SourceFilament
import kotlin.math.*

/**
 * Which loaded slot prints each of a model file's filaments, the way Snapmaker Orca decides it (ported from
 * src/slic3r/GUI/filamentsync/FilamentSyncAlgorithm.cpp and src/slic3r/Utils/ColorSpaceConvert.cpp; AGPL-3.0, see
 * THIRD_PARTY_NOTICES.md). Opening a file never matches by colour: filament N uses slot N ([byNumber], Snapmaker's
 * compute_direct_override). Matching by colour is a deliberate action ([byColour], its compute_color_match): CIEDE2000
 * in CIELAB, the same material type first, each file filament independently.
 */
object FilamentSync {
    /** Filament N (1-based) in slot N, wrapping round the loaded slots when the file has more filaments. */
    fun byNumber(count: Int, slots: List<ProjectManifest.MaterialSlot>): List<Int> =
        if (slots.isEmpty()) List(count) { 1 } else List(count) { i -> slots[i % slots.size].slot }

    /** Each of the file's filaments 1..[count] to the loaded slot nearest in colour, preferring the same material type. */
    fun byColour(count: Int, sources: List<SourceFilament>, slots: List<ProjectManifest.MaterialSlot>): List<Int> {
        if (slots.isEmpty()) return List(count) { 1 }
        val direct = byNumber(count, slots)
        val machineLab = slots.map { lab(it.colorHex) }
        return List(count) { i ->
            val src = sources.firstOrNull { it.index == i + 1 } ?: return@List direct[i]
            val want = lab(src.colorHex)
            fun nearest(candidates: List<Int>): Int? = candidates.minByOrNull { j -> deltaE00(want, machineLab[j]) }
            // Pass 1: the same filament type; pass 2 (fallback): any loaded filament. Ties keep the lowest slot.
            val same = slots.indices.filter { slots[it].type != null && slots[it].type == src.type }
            (nearest(same) ?: nearest(slots.indices.toList()))?.let { slots[it].slot } ?: direct[i]
        }
    }

    /** sRGB (IEC 61966-2-1) → linear → XYZ (D65) → CIELAB, as FilamentSyncAlgorithm.cpp's rgb_to_lab. */
    fun lab(hex: String?): FloatArray {
        val v = hex?.removePrefix("#")?.take(6)?.toIntOrNull(16) ?: 0
        fun lin(c: Int): Float { val x = c / 255f; return if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat() }
        val r = lin(v shr 16 and 255); val g = lin(v shr 8 and 255); val b = lin(v and 255)
        val x = 0.4124564f * r + 0.3575761f * g + 0.1804375f * b
        val y = 0.2126729f * r + 0.7151522f * g + 0.0721750f * b
        val z = 0.0193339f * r + 0.1191920f * g + 0.9503041f * b
        val d = 6f / 29f
        fun f(t: Float) = if (t > d * d * d) Math.cbrt(t.toDouble()).toFloat() else t / (3f * d * d) + 4f / 29f
        val fx = f(x / 0.95047f); val fy = f(y / 1.00000f); val fz = f(z / 1.08883f)
        return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }

    /** CIEDE2000, exactly as ColorSpaceConvert.cpp's DeltaE00 (including its hue-average handling). */
    fun deltaE00(p: FloatArray, q: FloatArray): Float {
        val (l1, a1, b1) = Triple(p[0].toDouble(), p[1].toDouble(), p[2].toDouble())
        val (l2, a2, b2) = Triple(q[0].toDouble(), q[1].toDouble(), q[2].toDouble())
        fun rad2deg(r: Double) = 360.0 * r / (2.0 * PI)
        fun deg2rad(d: Double) = (2.0 * PI * d) / 360.0
        val avgL = (l1 + l2) / 2.0
        val c1 = sqrt(a1 * a1 + b1 * b1); val c2 = sqrt(a2 * a2 + b2 * b2)
        val avgC = (c1 + c2) / 2.0
        val g = (1.0 - sqrt(avgC.pow(7) / (avgC.pow(7) + 25.0.pow(7)))) / 2.0
        val a1p = a1 * (1.0 + g); val a2p = a2 * (1.0 + g)
        val c1p = sqrt(a1p * a1p + b1 * b1); val c2p = sqrt(a2p * a2p + b2 * b2)
        val avgCp = (c1p + c2p) / 2.0
        var h1p = rad2deg(atan2(b1, a1p)); if (h1p < 0.0) h1p += 360.0
        var h2p = rad2deg(atan2(b2, a2p)); if (h2p < 0.0) h2p += 360.0
        val avghp = if (abs(h1p - h2p) > 180.0) (h1p + h2p + 360.0) / 2.0 else (h1p + h2p) / 2.0
        val t = 1.0 - 0.17 * cos(deg2rad(avghp - 30.0)) + 0.24 * cos(deg2rad(2.0 * avghp)) + 0.32 * cos(deg2rad(3.0 * avghp + 6.0)) - 0.2 * cos(deg2rad(4.0 * avghp - 63.0))
        var deltahp = h2p - h1p
        if (abs(deltahp) > 180.0) { if (h2p <= h1p) deltahp += 360.0 else deltahp -= 360.0 }
        val deltalp = l2 - l1; val deltacp = c2p - c1p
        deltahp = 2.0 * sqrt(c1p * c2p) * sin(deg2rad(deltahp) / 2.0)
        val sl = 1.0 + ((0.015 * (avgL - 50.0).pow(2)) / sqrt(20.0 + (avgL - 50.0).pow(2)))
        val sc = 1.0 + 0.045 * avgCp
        val sh = 1.0 + 0.015 * avgCp * t
        val deltaro = 30.0 * exp(-(((avghp - 275.0) / 25.0).pow(2)))
        val rc = 2.0 * sqrt(avgCp.pow(7) / (avgCp.pow(7) + 25.0.pow(7)))
        val rt = -rc * sin(2.0 * deg2rad(deltaro))
        return sqrt((deltalp / sl).pow(2) + (deltacp / sc).pow(2) + (deltahp / sh).pow(2) + rt * (deltacp / sc) * (deltahp / sh)).toFloat()
    }
}
