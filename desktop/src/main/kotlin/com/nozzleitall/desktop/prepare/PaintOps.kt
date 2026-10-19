package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Mesh
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Orca's paint tools, run by the engine on libslic3r's TriangleSelector (`nozzle-engine --paint`, the engine's
 * nozzle/bridge/native/paint_ops.cpp): a stroke splits and stores triangles exactly as Orca's support, seam, colour and
 * fuzzy-skin painting does. The strings are the ones a 3MF stores (`paint_color`, `paint_supports`, `paint_seam`,
 * `paint_fuzzy_skin`), one per triangle.
 */
object PaintOps {
    class EngineError(message: String) : Exception(message)

    /** The kinds of paint Orca has. [state] meanings: support and seam 1 = enforcer, 2 = blocker; fuzzy skin 1 = fuzzy; colour N = filament N. */
    enum class Kind(val label: String) { SUPPORT("Support"), SEAM("Seam"), COLOUR("Colour"), FUZZY("Fuzzy skin");
        fun of(mesh: Mesh): Array<String?>? = when (this) { SUPPORT -> mesh.supportPaint; SEAM -> mesh.seamPaint; COLOUR -> mesh.paint; FUZZY -> mesh.fuzzyPaint }
        fun with(mesh: Mesh, paint: Array<String?>?): Mesh = when (this) {
            SUPPORT -> mesh.withPaint(supportPaint = paint); SEAM -> mesh.withPaint(seamPaint = paint)
            COLOUR -> mesh.withPaint(paint = paint); FUZZY -> mesh.withPaint(fuzzyPaint = paint)
        }
    }

    enum class Mode(val label: String, val wire: String) { BRUSH("Brush", "brush"), FILL("Bucket fill", "fill"), SMART("Smart fill", "smart") }

    /** A ray in the mesh's own coordinates, with the brush radius there. */
    data class Stroke(val origin: DoubleArray, val dir: DoubleArray, val radius: Double, val state: Int)

    private val stls = java.util.Collections.synchronizedMap(java.util.WeakHashMap<IntArray, File>())

    private fun stlOf(mesh: Mesh): File = stls.getOrPut(mesh.triangles) {
        File.createTempFile("nozzle-paint", ".stl").apply { deleteOnExit(); writeBytes(SliceEngine.stlBytes(mesh)) }
    }

    private fun run(request: JSONObject, timeoutSeconds: Long = 120): JSONObject {
        val engine = SliceEngine.locateEngine() ?: throw EngineError("The slicing engine isn't installed with this copy of Nozzle It All.")
        val req = File.createTempFile("nozzle-paint", ".json").apply { deleteOnExit(); writeText(request.toString()) }
        try {
            val p = ProcessBuilder(engine.absolutePath, "--paint", req.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); throw EngineError("Painting took too long.") }
            val json = runCatching { JSONObject(out) }.getOrElse { throw EngineError("This engine doesn't have the paint tools yet.") }
            json.optString("error").takeIf { it.isNotBlank() }?.let { throw EngineError(it) }
            return json
        } finally { req.delete() }
    }

    private fun toWire(paint: Array<String?>?): JSONArray {
        val a = JSONArray()
        paint?.forEachIndexed { i, s -> if (s != null) a.put(JSONArray().put(i).put(s)) }
        return a
    }

    private fun fromWire(wire: JSONArray, triangles: Int): Array<String?>? {
        if (wire.length() == 0) return null
        val out = arrayOfNulls<String>(triangles)
        for (k in 0 until wire.length()) { val e = wire.getJSONArray(k); out[e.getInt(0)] = e.getString(1) }
        return out
    }

    /** [mesh] with [strokes] applied to the paint of [kind]. */
    fun stroke(mesh: Mesh, kind: Kind, strokes: List<Stroke>, mode: Mode = Mode.BRUSH, angleDeg: Double = 30.0): Mesh {
        if (strokes.isEmpty()) return mesh
        val req = JSONObject().put("op", "stroke").put("model", stlOf(mesh).absolutePath).put("paint", toWire(kind.of(mesh))).put("mode", mode.wire).put("angle", angleDeg)
            .put("strokes", JSONArray(strokes.map { s -> JSONObject().put("origin", JSONArray(s.origin.toList())).put("dir", JSONArray(s.dir.toList())).put("radius", s.radius).put("state", s.state) }))
        val out = run(req)
        return kind.with(mesh, fromWire(out.getJSONArray("paint"), mesh.triangleCount))
    }
}
