package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Mesh
import com.nozzleitall.project.ProjectFormatException
import com.nozzleitall.project.ThreeMf
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads STL (binary and ASCII), OBJ and 3MF into one merged mesh, with size limits for untrusted files. */
object MeshIO {
    const val MAX_TRIANGLES = 20_000_000

    fun read(file: File): Mesh = when (file.extension.lowercase()) {
        "stl" -> readStl(file.readBytes())
        "obj" -> readObj(file.readLines())
        "3mf" -> ThreeMf.read(file).objects.let { objs -> merge(objs.map { o ->
            val v = o.mesh.vertices.copyOf()
            for (i in v.indices step 3) { val p = o.placement.apply(v[i].toDouble(), v[i + 1].toDouble(), v[i + 2].toDouble()); v[i] = p[0].toFloat(); v[i + 1] = p[1].toFloat(); v[i + 2] = p[2].toFloat() }
            Mesh(v, o.mesh.triangles) }) }
        else -> throw ProjectFormatException("Nozzle It All opens STL, OBJ and 3MF files.")
    }

    fun merge(meshes: List<Mesh>): Mesh {
        val v = FloatArray(meshes.sumOf { it.vertices.size }); val t = IntArray(meshes.sumOf { it.triangles.size })
        var vo = 0; var to = 0
        meshes.forEach { m -> m.vertices.copyInto(v, vo); val base = vo / 3; for (i in m.triangles.indices) t[to + i] = m.triangles[i] + base; vo += m.vertices.size; to += m.triangles.size }
        return Mesh(v, t)
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
