package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Mesh
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The plate tools of Orca's workspace. Arrange, Auto orient and Cut run in the engine, on libslic3r's own code
 * (`nozzle-engine --plate`, engine/native/bridge/plate_ops.cpp); Split is libslic3r's its_split (TriangleMesh.cpp,
 * MeshSplitImpl.hpp: triangles joined through shared edges form one part) ported here, so each part keeps its paint.
 */
object PlateOps {
    class EngineError(message: String) : Exception(message)

    private fun run(request: JSONObject, timeoutSeconds: Long = 120): JSONObject {
        val engine = SliceEngine.locateEngine() ?: throw EngineError("The slicing engine isn't installed with this copy of Nozzle It All.")
        val req = File.createTempFile("nozzle-plate", ".json").apply { deleteOnExit(); writeText(request.toString()) }
        try {
            val p = ProcessBuilder(engine.absolutePath, "--plate", req.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); throw EngineError("The plate tool took too long.") }
            val json = runCatching { JSONObject(out) }.getOrElse { throw EngineError("This engine doesn't have the plate tools yet.") }
            json.optString("error").takeIf { it.isNotBlank() }?.let { throw EngineError(it) }
            return json
        } finally { req.delete() }
    }

    private fun stl(mesh: Mesh, dir: File, name: String) = File(dir, name).apply { writeBytes(SliceEngine.stlBytes(mesh)) }

    data class Placed(val dx: Double, val dy: Double, val rotationDeg: Double, val plate: Int)

    /**
     * Orca's Arrange for [items] (x/y relative to the bed centre), with the printer's own clearances and the process's
     * brim and skirt: how far each object moves and turns, and which plate it lands on (0 = this one).
     */
    fun arrange(profileFiles: List<File>, overrides: Map<String, String>, items: List<PrepItem>, bedW: Float, bedD: Float,
                distanceMm: Double = 0.0, rotate: Boolean = false, work: File): List<Placed> {
        val dir = File(work, "arrange").apply { mkdirs() }
        val objects = JSONArray(items.mapIndexed { i, it ->
            JSONObject().put("model", stl(it.mesh, dir, "o$i.stl").absolutePath).put("x", (it.x - bedW / 2).toDouble()).put("y", (it.y - bedD / 2).toDouble())
                .put("rotation", it.rotZ.toDouble()).put("scale", it.scale.toDouble())
        })
        val o = run(JSONObject().put("op", "arrange").put("profiles", JSONArray(profileFiles.map { it.absolutePath })).put("overrides", JSONObject(overrides))
            .put("objects", objects).put("distance", distanceMm).put("rotate", rotate))
        dir.deleteRecursively()
        val a = o.getJSONArray("objects")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Placed(it.getDouble("dx"), it.getDouble("dy"), it.getDouble("rotation"), it.getInt("plate")) }
    }

    /** Orca's Auto orient: [mesh] turned onto its best face (the rotation baked in, triangle order and paint kept). */
    fun orient(mesh: Mesh, work: File): Mesh {
        val o = run(JSONObject().put("op", "orient").put("model", stl(mesh, work, "orient.stl").absolutePath))
        val m = o.getJSONArray("matrix").let { a -> DoubleArray(9) { a.getDouble(it) } }
        val v = mesh.vertices
        val out = FloatArray(v.size)
        for (i in v.indices step 3) for (r in 0..2) out[i + r] = (m[r * 3] * v[i] + m[r * 3 + 1] * v[i + 1] + m[r * 3 + 2] * v[i + 2]).toFloat()
        return Mesh(out, mesh.triangles, mesh.paint)
    }

    /** Orca's Cut with a horizontal plane at [z] (mesh coordinates), both halves capped by libslic3r. Paint isn't kept. */
    fun cut(mesh: Mesh, z: Float, work: File): Pair<Mesh?, Mesh?> {
        val upper = File(work, "upper.stl"); val lower = File(work, "lower.stl")
        run(JSONObject().put("op", "cut").put("model", stl(mesh, work, "cut.stl").absolutePath).put("z", z.toDouble())
            .put("upper", upper.absolutePath).put("lower", lower.absolutePath))
        fun read(f: File) = MeshIO.readStl(f.readBytes()).takeIf { it.triangleCount > 0 }
        return (read(upper) to read(lower)).also { upper.delete(); lower.delete() }
    }

    /** its_split: the mesh's parts, each the triangles joined to one another through shared edges, vertices renumbered. */
    fun split(mesh: Mesh): List<Mesh> {
        val n = mesh.triangleCount
        // Vertices at exactly the same place are one vertex, as libslic3r merges them on load (its_merge_vertices); an
        // STL repeats every corner per triangle.
        val canonical = HashMap<Triple<Float, Float, Float>, Int>()
        val same = IntArray(mesh.vertexCount) { i -> canonical.getOrPut(Triple(mesh.vertices[i * 3], mesh.vertices[i * 3 + 1], mesh.vertices[i * 3 + 2])) { i } }
        val t = IntArray(mesh.triangles.size) { same[mesh.triangles[it]] }
        // Face neighbours through shared edges (its_face_neighbors), keyed by the edge's vertex pair.
        val edgeFaces = HashMap<Long, MutableList<Int>>()
        fun key(a: Int, b: Int) = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
        for (f in 0 until n) for (e in 0..2) edgeFaces.getOrPut(key(t[f * 3 + e], t[f * 3 + (e + 1) % 3])) { ArrayList(2) } += f
        val part = IntArray(n) { -1 }
        var parts = 0
        val stack = ArrayDeque<Int>()
        for (seed in 0 until n) {
            if (part[seed] >= 0) continue
            part[seed] = parts; stack.addLast(seed)
            while (stack.isNotEmpty()) {
                val f = stack.removeLast()
                for (e in 0..2) for (g in edgeFaces[key(t[f * 3 + e], t[f * 3 + (e + 1) % 3])].orEmpty()) if (part[g] < 0) { part[g] = parts; stack.addLast(g) }
            }
            parts++
        }
        if (parts <= 1) return listOf(mesh)
        return (0 until parts).map { p ->
            val remap = HashMap<Int, Int>(); val verts = ArrayList<Float>(); val tris = ArrayList<Int>(); val paint = ArrayList<String?>()
            for (f in 0 until n) if (part[f] == p) {
                for (k in 0..2) {
                    val vi = t[f * 3 + k]
                    tris += remap.getOrPut(vi) { verts.add(mesh.vertices[vi * 3]); verts.add(mesh.vertices[vi * 3 + 1]); verts.add(mesh.vertices[vi * 3 + 2]); remap.size }
                }
                paint += mesh.paint?.get(f)
            }
            Mesh(verts.toFloatArray(), tris.toIntArray(), if (mesh.paint != null && paint.any { it != null }) paint.toTypedArray() else null)
        }
    }
}
