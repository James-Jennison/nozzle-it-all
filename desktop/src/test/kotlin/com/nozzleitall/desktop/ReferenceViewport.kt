package com.nozzleitall.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import com.nozzleitall.desktop.prepare.*
import kotlin.math.*

// Frozen427e6a40 kernels: correctness oracle only, never shipped.
private fun displayBase(c: Color): Color {
    val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    return if (lum < 0.18f) lerpColor(c, Color(0.34f, 0.36f, 0.40f), 0.55f * (1f - lum / 0.18f)) else c
}

internal fun DrawScope.referenceShadow(proj: Projector, o: PlateObject, d: DisplayMesh) {
    val v = d.mesh.vertices; val t = d.mesh.triangles; val tris = d.tris
    val b = d.bounds; val mx = (b[0] + b[3]) / 2; val my = (b[1] + b[4]) / 2
    val cr = cos(Math.toRadians(o.rotZ.toDouble())).toFloat(); val sr = sin(Math.toRadians(o.rotZ.toDouble())).toFloat()
    val step = max(1, tris.size / 20_000); val tmp = FloatArray(3)
    val positions = ArrayList<Offset>(tris.size / step * 3 + 3)
    var k = 0
    while (k < tris.size) {
        val tri = tris[k]
        for (c in 0..2) {
            val vi = t[tri * 3 + c] * 3
            val lx = (v[vi] - mx) * o.ex; val ly = (v[vi + 1] - my) * o.ey
            proj.p(o.x + lx * cr - ly * sr, o.y + lx * sr + ly * cr, 0f, tmp, 0); positions += Offset(tmp[0], tmp[1])
        }
        k += step
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = 0.28f })
        canvas.drawVertices(Vertices(VertexMode.Triangles, positions, positions, List(positions.size) { Color.Black }, emptyList()), BlendMode.Dst, Paint())
        canvas.restore()
    }
}

internal fun DrawScope.referenceObject(proj: Projector, o: PlateObject, d: DisplayMesh, surface: ReferenceDepthSurface) {
    val v = d.mesh.vertices; val t = d.mesh.triangles; val tris = d.tris; val normals = d.normals
    val b = d.bounds; val mx = (b[0] + b[3]) / 2; val my = (b[1] + b[4]) / 2; val zBase = if (o.ez >= 0f) b[2] else b[5]
    val cr = cos(Math.toRadians(o.rotZ.toDouble())).toFloat(); val sr = sin(Math.toRadians(o.rotZ.toDouble())).toFloat()
    val n = tris.size
    val screen = FloatArray(n * 9); val cornerColor = IntArray(n * 3)
    // Lights live in view space so a model reads the same from any angle: a key light from the upper left, a softer fill
    // from the right, a highlight, and a rim on silhouettes that separates the model from the background.
    val key = norm(-0.45f, -0.60f, 0.66f); val fill = norm(0.70f, 0.05f, 0.45f)
    val half = norm(key[0], key[1], key[2] + 1f)
    fun shade(c: Color) = displayBase(if (o.selected) lerpColor(c, Color.White, 0.18f) else c)
    val own = shade(o.color)
    val palette = o.paintColors.map { it?.let(::shade) ?: own }
    val states = d.states
    val fn = FloatArray(3); val vn = FloatArray(3); val w = FloatArray(9)
    for ((k, tri) in tris.withIndex()) {
        val base = states?.get(tri)?.let { s -> if (s == 0) own else palette.getOrNull(s) ?: own } ?: own
        for (c in 0..2) {
            val vi = t[tri * 3 + c] * 3
            val lx = (v[vi] - mx) * o.ex; val ly = (v[vi + 1] - my) * o.ey; val lz = (v[vi + 2] - zBase) * o.ez
            w[c * 3] = o.x + lx * cr - ly * sr; w[c * 3 + 1] = o.y + lx * sr + ly * cr; w[c * 3 + 2] = lz
            proj.p(w[c * 3], w[c * 3 + 1], w[c * 3 + 2], screen, k * 9 + c * 3)
        }
        val ux = w[3] - w[0]; val uy = w[4] - w[1]; val uz = w[5] - w[2]; val qx = w[6] - w[0]; val qy = w[7] - w[1]; val qz = w[8] - w[2]
        var nx = uy * qz - uz * qy; var ny = uz * qx - ux * qz; var nz = ux * qy - uy * qx
        val len = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0 } ?: 1f; nx /= len; ny /= len; nz /= len
        proj.dir(nx, ny, nz, fn)
        // Two-sided: meshes with inconsistent winding still light from the side we see.
        val flip = if (fn[2] < 0f) -1f else 1f
        for (c in 0..2) {
            // A normal follows the inverse of the size, so a stretched or mirrored object keeps lighting right.
            val ni = (tri * 3 + c) * 3
            var ax = normals[ni] / o.ex; var ay = normals[ni + 1] / o.ey; var az = normals[ni + 2] / o.ez
            val nl = sqrt(ax * ax + ay * ay + az * az).takeIf { it > 0 } ?: 1f; ax /= nl; ay /= nl; az /= nl
            val rx = ax * cr - ay * sr; val ry = ax * sr + ay * cr
            proj.dir(rx, ry, az, vn)
            // Smooth where the surface is curved; keep the face normal at sharp edges (over about 40 degrees).
            val dot = (vn[0] * fn[0] + vn[1] * fn[1] + vn[2] * fn[2])
            val sx: Float; val sy: Float; val sz: Float
            if (dot * dot > 0.58f && dot > 0) { sx = vn[0] * flip; sy = vn[1] * flip; sz = vn[2] * flip } else { sx = fn[0] * flip; sy = fn[1] * flip; sz = fn[2] * flip }
            val diffuse = max(0f, sx * key[0] + sy * key[1] + sz * key[2])
            val soft = max(0f, sx * fill[0] + sy * fill[1] + sz * fill[2])
            val spec = max(0f, sx * half[0] + sy * half[1] + sz * half[2]).let { s -> s * s * s * s * s * s * s * s * s * s * s * s * s * s * s * s }
            val rim = (1f - max(0f, sz)).let { r -> r * r * r }
            val light = 0.30f + 0.62f * diffuse + 0.20f * soft
            val red = (base.red * light + 0.22f * spec + 0.10f * rim).coerceIn(0f, 1f)
            val green = (base.green * light + 0.22f * spec + 0.10f * rim).coerceIn(0f, 1f)
            val blue = (base.blue * light + 0.22f * spec + 0.12f * rim).coerceIn(0f, 1f)
            cornerColor[k * 3 + c] = Color(red, green, blue).toArgbInt()
        }
        surface.triangle(screen, k*9, cornerColor, k*3, proj.eye)
    }
}

private fun norm(x: Float, y: Float, z: Float): FloatArray { val l = sqrt(x * x + y * y + z * z); return floatArrayOf(x / l, y / l, z / l) }
private fun Color.toArgbInt(): Int = toArgb()
