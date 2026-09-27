package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Mesh
import com.nozzleitall.project.Paint
import com.nozzleitall.project.ProjectManifest
import com.nozzleitall.project.SourceFilament
import com.nozzleitall.project.ProjectFormatException
import com.nozzleitall.project.ThreeMf
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads STL (binary and ASCII), OBJ and 3MF into one merged mesh, with size limits for untrusted files. */
object MeshIO {
    const val MAX_TRIANGLES = 20_000_000

    /** A model file's geometry, plus for a 3MF the filaments it was set up with and the one its first object prints with. */
    class Loaded(val mesh: Mesh, val filaments: List<SourceFilament> = emptyList(), val filament: Int? = null)

    fun read(file: File): Mesh = load(file).mesh

    fun load(file: File): Loaded = when (file.extension.lowercase()) {
        "stl" -> Loaded(readStl(file.readBytes()))
        "obj" -> Loaded(readObj(file.readLines()))
        "3mf" -> ThreeMf.read(file).let { p ->
            // Every object becomes part of one model. The first object's filament is the model's default; another object
            // with a different filament has it painted on, so colours survive the merge.
            val default = p.objects.firstOrNull()?.filament
            Loaded(merge(p.objects.map { o ->
                val v = o.mesh.vertices.copyOf()
                for (i in v.indices step 3) { val q = o.placement.apply(v[i].toDouble(), v[i + 1].toDouble(), v[i + 2].toDouble()); v[i] = q[0].toFloat(); v[i + 1] = q[1].toFloat(); v[i + 2] = q[2].toFloat() }
                val own = o.filament ?: 1
                val paint = if (own == (default ?: 1)) o.mesh.paint
                    else Array<String?>(o.mesh.triangleCount) { i -> o.mesh.paint?.get(i)?.let { s -> Paint.remap(s) { if (it == 0) own else it } } ?: Paint.whole(own) }
                Mesh(v, o.mesh.triangles, paint) }), p.filaments, default)
        }
        else -> throw ProjectFormatException("Nozzle It All opens STL, OBJ and 3MF files.")
    }

    fun merge(meshes: List<Mesh>): Mesh {
        if (meshes.size == 1) return meshes[0]
        val v = FloatArray(meshes.sumOf { it.vertices.size }); val t = IntArray(meshes.sumOf { it.triangles.size })
        val paint = if (meshes.any { it.paint != null }) arrayOfNulls<String>(t.size / 3) else null
        var vo = 0; var to = 0
        meshes.forEach { m ->
            m.vertices.copyInto(v, vo); val base = vo / 3; for (i in m.triangles.indices) t[to + i] = m.triangles[i] + base
            m.paint?.copyInto(paint!!, to / 3)
            vo += m.vertices.size; to += m.triangles.size
        }
        return Mesh(v, t, paint)
    }

    /** The painted filament numbers a mesh uses (state 0, "the object's own", excluded). */
    fun paintedFilaments(mesh: Mesh): List<Int> {
        val out = java.util.TreeSet<Int>(); val seen = HashSet<String>()
        mesh.paint?.forEach { s -> if (s != null && seen.add(s)) runCatching { out += Paint.states(Paint.decode(s)) } }
        out.remove(0)
        return out.toList()
    }

    /**
     * The slot each of the file's filaments 1..[count] prints with: the loaded slot nearest in colour, preferring the same
     * material type. Without the file's colours, filament N goes to slot N (wrapping when the printer has fewer).
     */
    fun matchSlots(count: Int, sources: List<SourceFilament>, slots: List<ProjectManifest.MaterialSlot>): List<Int> {
        if (slots.isEmpty()) return List(count) { 1 }
        fun rgb(h: String?) = h?.removePrefix("#")?.take(6)?.toIntOrNull(16)?.let { floatArrayOf((it shr 16 and 255) / 255f, (it shr 8 and 255) / 255f, (it and 255) / 255f) }
        return List(count) { i ->
            val src = sources.firstOrNull { it.index == i + 1 }
            val want = rgb(src?.colorHex) ?: return@List slots[i % slots.size].slot
            slots.minByOrNull { s ->
                val have = rgb(s.colorHex) ?: floatArrayOf(0.5f, 0.5f, 0.5f)
                // Weighted RGB distance (closer to how different colours look), plus a nudge towards the same material.
                val dr = want[0] - have[0]; val dg = want[1] - have[1]; val db = want[2] - have[2]
                2 * dr * dr + 4 * dg * dg + 3 * db * db + if (src?.type != null && !src.type.equals(s.type, true)) 0.15f else 0f
            }!!.slot
        }
    }

    fun readStl(bytes: ByteArray): Mesh {
        if (bytes.size >= 84) {
            val count = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            if (84 + count * 50 == bytes.size.toLong()) {
                if (count > MAX_TRIANGLES) throw ProjectFormatException("This model has more triangles than Nozzle It All can open.")
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val v = FloatArray((count * 9).toInt()); val t = IntArray((count * 3).toInt()) { it }
                for (i in 0 until count.toInt()) { val base = 84 + i * 50 + 12; for (k in 0 until 9) v[i * 9 + k] = b.getFloat(base + k * 4) }
                return checked(Mesh(v, t))
            }
        }
        val text = String(bytes, Charsets.US_ASCII)
        if (!text.trimStart().startsWith("solid")) throw ProjectFormatException("This STL file is damaged or incomplete.")
        val nums = Regex("vertex\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)").findAll(text).flatMap { m -> m.groupValues.drop(1).map { it.toFloat() } }.toList()
        if (nums.isEmpty() || nums.size % 9 != 0) throw ProjectFormatException("This STL file has no complete triangles.")
        return checked(Mesh(nums.toFloatArray(), IntArray(nums.size / 3) { it }))
    }

    fun readObj(lines: List<String>): Mesh {
        val v = ArrayList<Float>(); val t = ArrayList<Int>()
        for (line in lines) {
            val p = line.trim().split(Regex("\\s+"))
            when (p.firstOrNull()) {
                "v" -> { v += p[1].toFloat(); v += p[2].toFloat(); v += p[3].toFloat() }
                "f" -> {
                    val idx = p.drop(1).map { it.substringBefore('/').toInt().let { i -> if (i < 0) v.size / 3 + i else i - 1 } }
                    for (k in 1 until idx.size - 1) { t += idx[0]; t += idx[k]; t += idx[k + 1] }
                }
            }
            if (t.size / 3 > MAX_TRIANGLES) throw ProjectFormatException("This model has more triangles than Nozzle It All can open.")
        }
        if (t.any { it < 0 || it >= v.size / 3 }) throw ProjectFormatException("This OBJ file refers to points that don't exist.")
        return checked(Mesh(v.toFloatArray(), t.toIntArray()))
    }

    private fun checked(m: Mesh): Mesh {
        if (m.triangleCount == 0) throw ProjectFormatException("The model is empty.")
        if (m.vertices.any { !it.isFinite() }) throw ProjectFormatException("The model contains invalid coordinates.")
        return m
    }
}
