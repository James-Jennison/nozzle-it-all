package com.nozzleitall.testgrid

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The standard Nozzle acceptance models, generated here rather than drawn by hand so they are reproducible and
 * verifiable: the same generator version always writes the same bytes, and models.json publishes their SHA-256.
 * `test-grid cli models --check` (and AcceptanceModelTest) regenerate them and compare with the bundled files.
 *
 * Every solid is a union of axis-aligned boxes (minus through-holes) meshed on the rectilinear grid their faces define,
 * one quad per exposed cell face. Faces then always meet edge to edge, so the mesh is closed and 2-manifold by
 * construction, which the test checks edge by edge.
 *
 * nozzle-acceptance-v1 (single material, 60 × 60 mm footprint), all on a 0.6 mm base plate:
 *  - base plate 60 × 60 × 0.6: bed placement, first layer, bed adhesion
 *  - hollow square tower, 20 × 20 outside, 8 × 8 through-hole, 10.6 tall overall: X/Y/Z dimensions, surface quality
 *  - two 4 × 4 pillars 15.6 tall, 12 mm apart: retraction and stringing
 *  - a 1.2 mm deck on two piers with an 18 mm unsupported span, 9.8 tall: bridging
 *  - a 1.2 mm thin wall, 20 long, 8.6 tall: thin-wall and extrusion width
 * nozzle-acceptance-mm-v1 (multi-material, kept separate on purpose): four 10 × 40 × 3 stripes alternating between two
 * parts (tool 1, tool 2), for material/tool change, purge and colour bleed. Never part of single-material acceptance.
 * nozzle-colour-swatch-v1 (multi-material): one 40 × 10 × 6 mm block, sliced as a 50/50 colour mix of two tools. Its
 * 30 layers (at 0.2 mm) alternate between them, and its long sides show whether the two read as one blended colour.
 * nozzle-color-reference-v1 (multi-material): six separate 15 × 4 × 6 mm tiles in one row, 5 mm apart, one part each, so
 * every tile can print its own color mix. Their front faces show the mixes side by side for one photo; the tiles are
 * numbered 1 to 6 from left to right as seen from the front of the printer. A suite may slice only the first few.
 */
object AcceptanceModel {
    const val GENERATOR_VERSION = 1
    const val SINGLE_ID = "nozzle-acceptance-v1"
    const val MULTI_ID = "nozzle-acceptance-mm-v1"
    const val SWATCH_ID = "nozzle-colour-swatch-v1"
    const val REFERENCE_ID = "nozzle-color-reference-v1"
    const val REFERENCE_TILES = 6

    data class Box(val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double) {
        fun contains(x: Double, y: Double, z: Double) = x in x0..x1 && y in y0..y1 && z in z0..z1
    }

    data class Solid(val name: String, val boxes: List<Box>, val holes: List<Box> = emptyList())

    val SINGLE = Solid(SINGLE_ID, listOf(
        Box(0.0, 0.0, 0.0, 60.0, 60.0, 0.6),              // base plate
        Box(6.0, 6.0, 0.6, 26.0, 26.0, 10.6),             // dimensional tower
        Box(38.0, 6.0, 0.6, 42.0, 10.0, 15.6),            // retraction pillar 1
        Box(54.0, 6.0, 0.6, 58.0, 10.0, 15.6),            // retraction pillar 2
        Box(33.0, 32.0, 0.6, 37.0, 52.0, 8.6),            // bridge pier 1
        Box(55.0, 32.0, 0.6, 59.0, 52.0, 8.6),            // bridge pier 2
        Box(33.0, 32.0, 8.6, 59.0, 52.0, 9.8),            // bridge deck
        Box(6.0, 40.0, 0.6, 26.0, 41.2, 8.6),             // thin wall
    ), holes = listOf(Box(12.0, 12.0, 0.0, 20.0, 20.0, 10.6)))

    val MULTI_A = Solid("$MULTI_ID-a", listOf(Box(0.0, 0.0, 0.0, 10.0, 40.0, 3.0), Box(20.0, 0.0, 0.0, 30.0, 40.0, 3.0)))
    val MULTI_B = Solid("$MULTI_ID-b", listOf(Box(10.0, 0.0, 0.0, 20.0, 40.0, 3.0), Box(30.0, 0.0, 0.0, 40.0, 40.0, 3.0)))
    val SWATCH = Solid(SWATCH_ID, listOf(Box(0.0, 0.0, 0.0, 40.0, 10.0, 6.0)))
    val REFERENCE: List<Solid> = (0 until REFERENCE_TILES).map { i -> Solid("$REFERENCE_ID-${i + 1}", listOf(Box(i * 20.0, 0.0, 0.0, i * 20.0 + 15.0, 4.0, 6.0))) }

    /** Published measurements and the tolerances the reference suites accept (mm). */
    val SINGLE_DIMENSIONS: JSONObject get() = JSONObject(mapOf(
        "footprint" to mapOf("x" to 60.0, "y" to 60.0), "baseThickness" to 0.6,
        "tower" to mapOf("outside" to 20.0, "hole" to 8.0, "height" to 10.6, "toleranceOutside" to 0.25, "toleranceHole" to 0.3, "toleranceHeight" to 0.25),
        "pillars" to mapOf("size" to 4.0, "height" to 15.6, "gap" to 12.0),
        "bridge" to mapOf("span" to 18.0, "deckThickness" to 1.2, "height" to 9.8, "width" to 20.0),
        "thinWall" to mapOf("thickness" to 1.2, "length" to 20.0, "height" to 8.6, "toleranceThickness" to 0.2),
    ).mapValues { EvidenceBuilder.toJsonValue(it.value) })

    val MULTI_DIMENSIONS: JSONObject get() = JSONObject().put("footprint", JSONObject().put("x", 40.0).put("y", 40.0)).put("stripeWidth", 10.0).put("height", 3.0).put("stripes", 4)

    val SWATCH_DIMENSIONS: JSONObject get() = JSONObject().put("footprint", JSONObject().put("x", 40.0).put("y", 10.0)).put("height", 6.0)

    val REFERENCE_DIMENSIONS: JSONObject get() = JSONObject().put("tiles", REFERENCE_TILES).put("tile", JSONObject().put("x", 15.0).put("y", 4.0).put("z", 6.0)).put("gap", 5.0)
        .put("footprint", JSONObject().put("x", REFERENCE_TILES * 20.0 - 5.0).put("y", 4.0))

    fun files(): Map<String, ByteArray> = sortedMapOf(
        "$SINGLE_ID.stl" to stl(SINGLE), "$MULTI_ID-a.stl" to stl(MULTI_A), "$MULTI_ID-b.stl" to stl(MULTI_B), "$SWATCH_ID.stl" to stl(SWATCH),
        *REFERENCE.map { "${it.name}.stl" to stl(it) }.toTypedArray())

    fun manifest(): JSONObject {
        val f = files()
        fun part(name: String, tool: Int) = JSONObject().put("file", name).put("sha256", Canon.sha256(f.getValue(name))).put("bytes", f.getValue(name).size).put("tool", tool)
        return JSONObject().put("format", "nozzle.acceptance-models").put("version", JSONArray().put(1).put(0))
            .put("generator", "com.nozzleitall.testgrid.AcceptanceModel v$GENERATOR_VERSION")
            .put("models", JSONArray()
                .put(JSONObject().put("id", SINGLE_ID).put("title", "Nozzle acceptance model v1 (single material)").put("scope", MaterialScope.SINGLE.id)
                    .put("parts", JSONArray().put(part("$SINGLE_ID.stl", 1))).put("dimensions", SINGLE_DIMENSIONS)
                    .put("features", JSONArray(listOf("bed placement and limits", "first layer", "dimensional accuracy", "retraction", "bridging", "surface quality", "thin walls"))))
                .put(JSONObject().put("id", MULTI_ID).put("title", "Nozzle multi-material acceptance model v1").put("scope", MaterialScope.MULTI.id)
                    .put("parts", JSONArray().put(part("$MULTI_ID-a.stl", 1)).put(part("$MULTI_ID-b.stl", 2))).put("dimensions", MULTI_DIMENSIONS)
                    .put("features", JSONArray(listOf("tool or material change", "purge", "colour bleed at boundaries"))))
                .put(JSONObject().put("id", SWATCH_ID).put("title", "Nozzle colour-mixing swatch v1").put("scope", MaterialScope.MULTI.id)
                    .put("parts", JSONArray().put(part("$SWATCH_ID.stl", 1))).put("dimensions", SWATCH_DIMENSIONS)
                    .put("features", JSONArray(listOf("colour mixing (Full Spectrum or ColorMix)", "layer-by-layer tool alternation", "blended colour"))))
                .put(JSONObject().put("id", REFERENCE_ID).put("title", "Nozzle color reference v1 (six color-mix tiles)").put("scope", MaterialScope.MULTI.id)
                    .put("parts", JSONArray(REFERENCE.map { part("${it.name}.stl", 1) })).put("dimensions", REFERENCE_DIMENSIONS)
                    .put("features", JSONArray(listOf("several color mixes in one print", "mix ratios", "every loaded tool", "comparison with a published color reference")))))
    }

    data class Triangle(val n: DoubleArray, val a: DoubleArray, val b: DoubleArray, val c: DoubleArray)

    fun mesh(solid: Solid): List<Triangle> {
        val all = solid.boxes + solid.holes
        val xs = all.flatMap { listOf(it.x0, it.x1) }.distinct().sorted()
        val ys = all.flatMap { listOf(it.y0, it.y1) }.distinct().sorted()
        val zs = all.flatMap { listOf(it.z0, it.z1) }.distinct().sorted()
        fun filled(i: Int, j: Int, k: Int): Boolean {
            if (i !in 0 until xs.size - 1 || j !in 0 until ys.size - 1 || k !in 0 until zs.size - 1) return false
            val cx = (xs[i] + xs[i + 1]) / 2; val cy = (ys[j] + ys[j + 1]) / 2; val cz = (zs[k] + zs[k + 1]) / 2
            return solid.boxes.any { it.contains(cx, cy, cz) } && solid.holes.none { it.contains(cx, cy, cz) }
        }
        val out = ArrayList<Triangle>()
        fun quad(n: DoubleArray, p0: DoubleArray, p1: DoubleArray, p2: DoubleArray, p3: DoubleArray) {
            // p0..p3 counter-clockwise seen from outside (along +n).
            out += Triangle(n, p0, p1, p2); out += Triangle(n, p0, p2, p3)
        }
        for (i in 0 until xs.size - 1) for (j in 0 until ys.size - 1) for (k in 0 until zs.size - 1) {
            if (!filled(i, j, k)) continue
            val x0 = xs[i]; val x1 = xs[i + 1]; val y0 = ys[j]; val y1 = ys[j + 1]; val z0 = zs[k]; val z1 = zs[k + 1]
            fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
            if (!filled(i - 1, j, k)) quad(doubleArrayOf(-1.0, 0.0, 0.0), p(x0, y0, z0), p(x0, y0, z1), p(x0, y1, z1), p(x0, y1, z0))
            if (!filled(i + 1, j, k)) quad(doubleArrayOf(1.0, 0.0, 0.0), p(x1, y0, z0), p(x1, y1, z0), p(x1, y1, z1), p(x1, y0, z1))
            if (!filled(i, j - 1, k)) quad(doubleArrayOf(0.0, -1.0, 0.0), p(x0, y0, z0), p(x1, y0, z0), p(x1, y0, z1), p(x0, y0, z1))
            if (!filled(i, j + 1, k)) quad(doubleArrayOf(0.0, 1.0, 0.0), p(x0, y1, z0), p(x0, y1, z1), p(x1, y1, z1), p(x1, y1, z0))
            if (!filled(i, j, k - 1)) quad(doubleArrayOf(0.0, 0.0, -1.0), p(x0, y0, z0), p(x0, y1, z0), p(x1, y1, z0), p(x1, y0, z0))
            if (!filled(i, j, k + 1)) quad(doubleArrayOf(0.0, 0.0, 1.0), p(x0, y0, z1), p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1))
        }
        return out
    }

    /** Binary STL, little-endian, fixed header: identical bytes for identical geometry on every platform. */
    fun stl(solid: Solid): ByteArray {
        val tris = mesh(solid)
        val buf = ByteBuffer.allocate(84 + 50 * tris.size).order(ByteOrder.LITTLE_ENDIAN)
        val header = "${solid.name} - Nozzle Test Grid AcceptanceModel v$GENERATOR_VERSION".padEnd(80, ' ').take(80)
        buf.put(header.toByteArray(Charsets.US_ASCII))
        buf.putInt(tris.size)
        tris.forEach { t ->
            listOf(t.n, t.a, t.b, t.c).forEach { v -> v.forEach { buf.putFloat(it.toFloat()) } }
            buf.putShort(0)
        }
        return buf.array()
    }
}
