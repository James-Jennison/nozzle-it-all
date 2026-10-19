package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Mesh
import com.nozzleitall.project.Paint
import kotlin.math.sqrt

/** Immutable draft: corrections and cleanup never change the project until Apply. */
class AutoPaintReview(val faceRegions: IntArray, val slots: List<Int>, val originalSlots: List<Int> = slots) {
    init { require(faceRegions.all { it in slots.indices }) }
    fun corrected(region: Int, slot: Int) = AutoPaintReview(faceRegions, slots.toMutableList().also { it[region] = slot }, originalSlots)
    fun reset() = AutoPaintReview(faceRegions, originalSlots)
    fun faces() = IntArray(faceRegions.size) { slots[faceRegions[it]] }

    companion object {
        /** Normalize existing paint to actual slot IDs before overlaying AI slots; zero really keeps existing paint. */
        fun base(mesh: Mesh, mapping: List<Int>, baseSlot: Int): Mesh = mesh.withPaint(paint = mesh.paint?.map { s ->
            s?.let { Paint.remap(it) { n -> if (n == 0) 0 else mapping.getOrNull(n - 1) ?: baseSlot } }
        }?.toTypedArray())
    }
}

/**
 * One conservative boundary pass. Only retract a one-face tip when two longer edges agree, on a smooth manifold patch.
 * Never touch zero/keep, sharp creases, open/non-manifold edges, or small connected colour details (eight faces or fewer).
 * Geometry, topology and paint in the input are never mutated. This cannot invent semantic feature boundaries.
 */
internal fun cleanPaintBoundaries(mesh: Mesh, slots: IntArray): IntArray {
    require(slots.size == mesh.triangleCount)
    val v = mesh.vertices; val t = mesh.triangles; val n = slots.size
    val canonical = HashMap<Triple<Float, Float, Float>, Int>()
    val same = IntArray(mesh.vertexCount) { i -> canonical.getOrPut(Triple(v[i * 3], v[i * 3 + 1], v[i * 3 + 2])) { i } }
    val edgeFaces = HashMap<Long, MutableList<Pair<Int, Int>>>()
    val lengths = Array(n) { DoubleArray(3) }
    val normals = Array(n) { DoubleArray(3) }
    for (f in 0 until n) {
        val a = t[f * 3] * 3; val b = t[f * 3 + 1] * 3; val c = t[f * 3 + 2] * 3
        val u = DoubleArray(3) { (v[b + it] - v[a + it]).toDouble() }; val w = DoubleArray(3) { (v[c + it] - v[a + it]).toDouble() }
        val normal = doubleArrayOf(u[1]*w[2]-u[2]*w[1], u[2]*w[0]-u[0]*w[2], u[0]*w[1]-u[1]*w[0])
        val len = sqrt(normal.sumOf { it * it })
        if (len > 0) for (k in 0..2) normals[f][k] = normal[k] / len
        for (e in 0..2) {
            val p = t[f * 3 + e]; val q = t[f * 3 + (e + 1) % 3]
            lengths[f][e] = sqrt((0..2).sumOf { k -> val d = (v[p*3+k]-v[q*3+k]).toDouble(); d*d })
            val x = minOf(same[p], same[q]); val y = maxOf(same[p], same[q])
            edgeFaces.getOrPut((x.toLong() shl 32) or y.toLong()) { mutableListOf() }.add(f to e)
        }
    }
    val neighbors = Array(n) { IntArray(3) { -1 } }
    edgeFaces.values.filter { it.size == 2 && it[0].first != it[1].first }.forEach { pair ->
        val (a, ea) = pair[0]; val (b, eb) = pair[1]; neighbors[a][ea] = b; neighbors[b][eb] = a
    }
    val component = IntArray(n) { -1 }; val sizes = mutableListOf<Int>()
    for (seed in 0 until n) if (component[seed] < 0) {
        val id = sizes.size; val queue = ArrayDeque<Int>(); queue.add(seed); component[seed] = id; var count = 0
        while (queue.isNotEmpty()) { val f = queue.removeFirst(); count++
            for (g in neighbors[f]) if (g >= 0 && component[g] < 0 && slots[g] == slots[f]) { component[g] = id; queue.add(g) }
        }
        sizes.add(count)
    }
    return slots.copyOf().also { out ->
        for (f in 0 until n) {
            if (slots[f] <= 0 || sizes[component[f]] <= 8 || neighbors[f].any { it < 0 }) continue
            if (neighbors[f].any { g -> (0..2).sumOf { normals[f][it] * normals[g][it] } < 0.94 }) continue
            val other = neighbors[f].map { slots[it] }.filter { it > 0 && it != slots[f] }
            val candidate = other.groupingBy { it }.eachCount().entries.firstOrNull { it.value >= 2 }?.key ?: continue
            val removed = (0..2).filter { slots[neighbors[f][it]] == candidate }.sumOf { lengths[f][it] }
            val added = lengths[f].sum() - removed
            if (removed > added * 1.1) out[f] = candidate
        }
    }
}
