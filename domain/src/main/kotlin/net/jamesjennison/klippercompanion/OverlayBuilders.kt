package net.jamesjennison.klippercompanion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Phase 9d: geometry for the on-model overlays - painted brush dabs and modifier/blocker region outlines. Pure, so
// it is unit-tested without a GL context; ProjectGLRenderer just uploads and draws the result.
data class PaintDisc(val kind: PaintKind, val center: FloatArray, val normal: FloatArray, val radius: Float, val tool: Int = 0)

object OverlayBuilders {
    private const val DISC_SEGMENTS = 14
    private const val LIFT = 0.06f // mm above the surface, so discs never z-fight the model

    fun paintColor(kind: PaintKind): FloatArray = when (kind) {
        PaintKind.SUPPORT_ENFORCER -> floatArrayOf(0.20f, 0.52f, 1.00f)
        PaintKind.SUPPORT_BLOCKER -> floatArrayOf(0.92f, 0.22f, 0.22f)
        PaintKind.SEAM_ENFORCER -> floatArrayOf(0.20f, 0.80f, 0.32f)
        PaintKind.SEAM_BLOCKER -> floatArrayOf(0.96f, 0.80f, 0.10f)
        PaintKind.MATERIAL -> floatArrayOf(0.96f, 0.96f, 0.96f)
    }
    /** Material strokes are drawn in their tool's own colour. */
    fun paintColor(kind: PaintKind, tool: Int): FloatArray = if (kind == PaintKind.MATERIAL) hexToRgb(DEFAULT_TOOL_COLORS[(tool - 1).coerceAtLeast(0) % DEFAULT_TOOL_COLORS.size]) else paintColor(kind)
    private fun hexToRgb(hex: String) = floatArrayOf(hex.substring(1, 3).toInt(16) / 255f, hex.substring(3, 5).toInt(16) / 255f, hex.substring(5, 7).toInt(16) / 255f)
    fun volumeColor(kind: VolumeKind): FloatArray = when (kind) {
        VolumeKind.MODIFIER -> floatArrayOf(0.80f, 0.35f, 0.92f)
        VolumeKind.SUPPORT_BLOCKER -> floatArrayOf(0.92f, 0.22f, 0.22f)
        VolumeKind.SUPPORT_ENFORCER -> floatArrayOf(0.20f, 0.52f, 1.00f)
    }

    /** One triangle-fan disc per dab, grouped by paint kind. */
    fun paintGroups(discs: List<PaintDisc>): List<OverlayGroup> =
        discs.groupBy { it.kind to it.tool }.map { (key, list) ->
            val out = FloatArray(list.size * DISC_SEGMENTS * 18)
            var o = 0
            for (d in list) {
                val n = d.normal
                // any axis not parallel to the normal gives two in-plane tangents
                val ref = if (abs(n[2]) < 0.9f) floatArrayOf(0f, 0f, 1f) else floatArrayOf(1f, 0f, 0f)
                val tx = n[1] * ref[2] - n[2] * ref[1]; val ty = n[2] * ref[0] - n[0] * ref[2]; val tz = n[0] * ref[1] - n[1] * ref[0]
                val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
                val ux = tx / tl; val uy = ty / tl; val uz = tz / tl
                val vx = n[1] * uz - n[2] * uy; val vy = n[2] * ux - n[0] * uz; val vz = n[0] * uy - n[1] * ux
                val cx = d.center[0] + n[0] * LIFT; val cy = d.center[1] + n[1] * LIFT; val cz = d.center[2] + n[2] * LIFT
                fun rim(i: Int): FloatArray {
                    val a = 2.0 * PI * i / DISC_SEGMENTS
                    val c = cos(a).toFloat() * d.radius; val s = sin(a).toFloat() * d.radius
                    return floatArrayOf(cx + ux * c + vx * s, cy + uy * c + vy * s, cz + uz * c + vz * s)
                }
                for (i in 0 until DISC_SEGMENTS) {
                    val p0 = rim(i); val p1 = rim(i + 1)
                    for (p in listOf(floatArrayOf(cx, cy, cz), p0, p1)) { out[o++] = p[0]; out[o++] = p[1]; out[o++] = p[2]; out[o++] = n[0]; out[o++] = n[1]; out[o++] = n[2] }
                }
            }
            OverlayGroup(paintColor(key.first, key.second), out)
        }

    /** Wireframe (GL_LINES, position + normal) of a region, [center] in the preview frame. */
    fun volumeOutline(v: ShapeVolume, center: FloatArray): OverlayGroup {
        val lines = ArrayList<FloatArray>()
        val hx = v.size[0] / 2f; val hy = v.size[1] / 2f; val hz = v.size[2] / 2f
        fun p(x: Float, y: Float, z: Float) = floatArrayOf(center[0] + x, center[1] + y, center[2] + z)
        fun seg(a: FloatArray, b: FloatArray) { lines.add(a); lines.add(b) }
        when (v.shape) {
            VolumeShape.BOX -> {
                for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) seg(p(sx * hx, sy * hy, -hz), p(sx * hx, sy * hy, hz))
                for (sz in listOf(-1f, 1f)) {
                    seg(p(-hx, -hy, sz * hz), p(hx, -hy, sz * hz)); seg(p(hx, -hy, sz * hz), p(hx, hy, sz * hz))
                    seg(p(hx, hy, sz * hz), p(-hx, hy, sz * hz)); seg(p(-hx, hy, sz * hz), p(-hx, -hy, sz * hz))
                }
            }
            VolumeShape.CYLINDER -> {
                val n = 24
                for (z in listOf(-hz, hz)) for (i in 0 until n) {
                    val a0 = 2.0 * PI * i / n; val a1 = 2.0 * PI * (i + 1) / n
                    seg(p(cos(a0).toFloat() * hx, sin(a0).toFloat() * hy, z), p(cos(a1).toFloat() * hx, sin(a1).toFloat() * hy, z))
                }
                for (i in 0 until 4) { val a = PI / 2 * i; seg(p(cos(a).toFloat() * hx, sin(a).toFloat() * hy, -hz), p(cos(a).toFloat() * hx, sin(a).toFloat() * hy, hz)) }
            }
            VolumeShape.SPHERE -> {
                val n = 24
                for (i in 0 until n) {
                    val a0 = 2.0 * PI * i / n; val a1 = 2.0 * PI * (i + 1) / n
                    val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat(); val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()
                    seg(p(c0 * hx, s0 * hy, 0f), p(c1 * hx, s1 * hy, 0f))
                    seg(p(c0 * hx, 0f, s0 * hz), p(c1 * hx, 0f, s1 * hz))
                    seg(p(0f, c0 * hy, s0 * hz), p(0f, c1 * hy, s1 * hz))
                }
            }
        }
        val out = FloatArray(lines.size * 6)
        lines.forEachIndexed { i, pt -> out[i * 6] = pt[0]; out[i * 6 + 1] = pt[1]; out[i * 6 + 2] = pt[2]; out[i * 6 + 5] = 1f }
        return OverlayGroup(volumeColor(v.kind), out, lines = true)
    }

    /** Where each stroke landed on the mesh (preview frame), for the overlay. Strokes that miss the mesh are skipped. */
    fun discsFor(strokes: List<PaintStroke>, mesh: TriMesh, origin: FloatArray): List<PaintDisc> = strokes.mapNotNull { s ->
        val o = floatArrayOf(s.origin[0] + origin[0], s.origin[1] + origin[1], s.origin[2] + origin[2])
        val hit = MeshEdit.rayHit(mesh, o, s.dir) ?: return@mapNotNull null
        var n = MeshEdit.triangleNormal(mesh, hit.triangle)
        if (n[0] * s.dir[0] + n[1] * s.dir[1] + n[2] * s.dir[2] > 0f) n = floatArrayOf(-n[0], -n[1], -n[2]) // face the viewer
        PaintDisc(s.kind, hit.point, n, s.radius, s.tool)
    }
}
