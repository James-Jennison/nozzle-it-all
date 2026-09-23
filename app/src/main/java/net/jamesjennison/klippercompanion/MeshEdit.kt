package net.jamesjennison.klippercompanion

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Phase 9c: model-space mesh editing (mirror, lay-on-face, auto-orient, ray picking, measuring). Edits produce a
// new triangle soup that the project saves as a fresh STL, so the slicer and the 3D preview see exactly the same
// geometry and undo simply swaps the file reference. All maths is in the mesh's own local space, which a Z
// rotation, uniform scale or XY offset never changes the meaning of.
class TriMesh(val v: FloatArray) {
    init { require(v.size % 9 == 0) { "A triangle needs 9 floats." } }
    val triangleCount get() = v.size / 9
    fun minAlong(axis: Int): Float { var m = Float.MAX_VALUE; var i = axis; while (i < v.size) { m = min(m, v[i]); i += 3 }; return m }
    fun maxAlong(axis: Int): Float { var m = -Float.MAX_VALUE; var i = axis; while (i < v.size) { m = max(m, v[i]); i += 3 }; return m }
    fun center(axis: Int) = (minAlong(axis) + maxAlong(axis)) / 2f
}

data class MeshHit(val triangle: Int, val distance: Float, val point: FloatArray)

object MeshEdit {
    fun fromGeometry(g: MeshGeometry): TriMesh {
        val out = FloatArray(g.triangleCount * 9)
        for (t in 0 until g.triangleCount) for (k in 0 until 3) { val s = t * 18 + k * 6; System.arraycopy(g.vertexData, s, out, t * 9 + k * 3, 3) }
        return TriMesh(out)
    }

    fun triangleNormal(m: TriMesh, t: Int): FloatArray {
        val b = t * 9; val v = m.v
        val ux = v[b + 3] - v[b]; val uy = v[b + 4] - v[b + 1]; val uz = v[b + 5] - v[b + 2]
        val wx = v[b + 6] - v[b]; val wy = v[b + 7] - v[b + 1]; val wz = v[b + 8] - v[b + 2]
        val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        return if (len < 1e-12f) floatArrayOf(0f, 0f, 0f) else floatArrayOf(nx / len, ny / len, nz / len)
    }
    fun triangleArea(m: TriMesh, t: Int): Float {
        val b = t * 9; val v = m.v
        val ux = v[b + 3] - v[b]; val uy = v[b + 4] - v[b + 1]; val uz = v[b + 5] - v[b + 2]
        val wx = v[b + 6] - v[b]; val wy = v[b + 7] - v[b + 1]; val wz = v[b + 8] - v[b + 2]
        val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
        return 0.5f * sqrt(nx * nx + ny * ny + nz * nz)
    }

    /** Mirror across the plane through the mesh centre perpendicular to [axis] (0=X,1=Y,2=Z); winding is flipped so faces stay outward. */
    fun mirror(m: TriMesh, axis: Int): TriMesh {
        val c2 = 2f * m.center(axis)
        val out = m.v.copyOf()
        for (t in 0 until m.triangleCount) {
            val b = t * 9
            for (k in 0 until 3) out[b + k * 3 + axis] = c2 - m.v[b + k * 3 + axis]
            for (i in 0 until 3) { val tmp = out[b + 3 + i]; out[b + 3 + i] = out[b + 6 + i]; out[b + 6 + i] = tmp }
        }
        return onBed(TriMesh(out))
    }

    /** Rotation matrix (row-major 3x3) taking unit vector [a] to unit vector [b]. */
    fun rotationBetween(a: FloatArray, b: FloatArray): DoubleArray {
        val ax = a[0].toDouble(); val ay = a[1].toDouble(); val az = a[2].toDouble()
        val bx = b[0].toDouble(); val by = b[1].toDouble(); val bz = b[2].toDouble()
        val dot = (ax * bx + ay * by + az * bz).coerceIn(-1.0, 1.0)
        if (dot > 1 - 1e-9) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        var kx = ay * bz - az * by; var ky = az * bx - ax * bz; var kz = ax * by - ay * bx
        var angle = acos(dot)
        if (dot < -1 + 1e-9) { // opposite: turn 180° about any axis perpendicular to a
            val px = if (abs(ax) < 0.9) 1.0 else 0.0; val py = if (abs(ax) < 0.9) 0.0 else 1.0
            kx = ay * 0.0 - az * py; ky = az * px - ax * 0.0; kz = ax * py - ay * px; angle = Math.PI
        }
        val len = sqrt(kx * kx + ky * ky + kz * kz); kx /= len; ky /= len; kz /= len
        val c = cos(angle); val s = sin(angle); val o = 1 - c
        return doubleArrayOf(
            c + kx * kx * o, kx * ky * o - kz * s, kx * kz * o + ky * s,
            ky * kx * o + kz * s, c + ky * ky * o, ky * kz * o - kx * s,
            kz * kx * o - ky * s, kz * ky * o + kx * s, c + kz * kz * o,
        )
    }

    /** Rotates about the mesh's bounding-box centre, then rests it on z = 0. */
    fun rotated(m: TriMesh, r: DoubleArray): TriMesh {
        val cx = m.center(0); val cy = m.center(1); val cz = m.center(2)
        val out = FloatArray(m.v.size)
        var i = 0
        while (i < m.v.size) {
            val x = (m.v[i] - cx).toDouble(); val y = (m.v[i + 1] - cy).toDouble(); val z = (m.v[i + 2] - cz).toDouble()
            out[i] = (cx + r[0] * x + r[1] * y + r[2] * z).toFloat()
            out[i + 1] = (cy + r[3] * x + r[4] * y + r[5] * z).toFloat()
            out[i + 2] = (cz + r[6] * x + r[7] * y + r[8] * z).toFloat()
            i += 3
        }
        return onBed(TriMesh(out))
    }

    /** Lay the face with outward [normal] flat on the bed. */
    fun layOnFace(m: TriMesh, normal: FloatArray): TriMesh = rotated(m, rotationBetween(normal, floatArrayOf(0f, 0f, -1f)))

    fun onBed(m: TriMesh): TriMesh {
        val z = m.minAlong(2)
        if (abs(z) < 1e-6f) return m
        val out = m.v.copyOf(); var i = 2; while (i < out.size) { out[i] -= z; i += 3 }
        return TriMesh(out)
    }

    /** Result of [autoOrient]: the down-facing normal to lay on the bed, and its score vs the current orientation. */
    data class Orientation(val downNormal: FloatArray, val score: Double, val currentScore: Double)

    /**
     * Picks the orientation with the least overhang (needing support), preferring a large flat base and a low height.
     * Candidates are the bed-facing normals of the biggest planar regions plus the six axes. Returns null when no
     * candidate is clearly better than the current orientation.
     */
    fun autoOrient(m: TriMesh, overhangDeg: Double = 45.0): Orientation? {
        if (m.triangleCount == 0) return null
        val areas = FloatArray(m.triangleCount) { triangleArea(m, it) }
        val normals = Array(m.triangleCount) { triangleNormal(m, it) }
        val buckets = HashMap<Triple<Int, Int, Int>, Pair<FloatArray, Double>>()
        for (t in 0 until m.triangleCount) {
            val n = normals[t]; if (areas[t] <= 0f) continue
            val key = Triple((n[0] * 12).toInt(), (n[1] * 12).toInt(), (n[2] * 12).toInt())
            val prev = buckets[key]
            buckets[key] = if (prev == null) n to areas[t].toDouble() else prev.first to prev.second + areas[t]
        }
        val candidates = ArrayList<FloatArray>()
        buckets.values.sortedByDescending { it.second }.take(14).forEach { candidates.add(it.first) }
        for (axis in 0 until 3) for (sign in listOf(-1f, 1f)) candidates.add(FloatArray(3).also { it[axis] = sign })
        val sinLimit = sin(Math.toRadians(overhangDeg))

        fun score(down: FloatArray): Double {
            var minProj = Double.MAX_VALUE; var maxProj = -Double.MAX_VALUE
            // projection of a vertex on `down`: larger = lower (closer to the bed once `down` maps to -Z)
            var i = 0
            while (i < m.v.size) { val p = (m.v[i] * down[0] + m.v[i + 1] * down[1] + m.v[i + 2] * down[2]).toDouble(); if (p > maxProj) maxProj = p; if (p < minProj) minProj = p; i += 3 }
            val height = maxProj - minProj
            var overhang = 0.0; var base = 0.0
            for (t in 0 until m.triangleCount) {
                val n = normals[t]; val along = (n[0] * down[0] + n[1] * down[1] + n[2] * down[2]).toDouble()
                val triLow = (0 until 3).maxOf { k -> (m.v[t * 9 + k * 3] * down[0] + m.v[t * 9 + k * 3 + 1] * down[1] + m.v[t * 9 + k * 3 + 2] * down[2]).toDouble() }
                if (along > 0.999 && maxProj - triLow < 0.05) base += areas[t] // flat and resting on the bed
                else if (along > sinLimit) overhang += areas[t]
            }
            return overhang - 0.5 * base + 0.05 * height
        }
        val current = score(floatArrayOf(0f, 0f, -1f))
        var best: FloatArray? = null; var bestScore = current
        for (c in candidates) { val s = score(c); if (s < bestScore - 1e-6) { bestScore = s; best = c } }
        val chosen = best ?: return null
        return if (current - bestScore > 0.02 * max(1.0, abs(current))) Orientation(chosen, bestScore, current) else null
    }

    fun applyOrientation(m: TriMesh, o: Orientation): TriMesh = layOnFace(m, o.downNormal)

    /** Möller–Trumbore against every triangle; nearest hit in front of the origin. [dir] need not be normalised. */
    fun rayHit(m: TriMesh, origin: FloatArray, dir: FloatArray): MeshHit? {
        var best: MeshHit? = null
        val v = m.v
        for (t in 0 until m.triangleCount) {
            val b = t * 9
            val e1x = v[b + 3] - v[b]; val e1y = v[b + 4] - v[b + 1]; val e1z = v[b + 5] - v[b + 2]
            val e2x = v[b + 6] - v[b]; val e2y = v[b + 7] - v[b + 1]; val e2z = v[b + 8] - v[b + 2]
            val px = dir[1] * e2z - dir[2] * e2y; val py = dir[2] * e2x - dir[0] * e2z; val pz = dir[0] * e2y - dir[1] * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) < 1e-12f) continue
            val inv = 1f / det
            val tx = origin[0] - v[b]; val ty = origin[1] - v[b + 1]; val tz = origin[2] - v[b + 2]
            val u = (tx * px + ty * py + tz * pz) * inv; if (u < 0f || u > 1f) continue
            val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
            val w = (dir[0] * qx + dir[1] * qy + dir[2] * qz) * inv; if (w < 0f || u + w > 1f) continue
            val dist = (e2x * qx + e2y * qy + e2z * qz) * inv
            if (dist > 1e-4f && (best == null || dist < best.distance)) best = MeshHit(t, dist, floatArrayOf(origin[0] + dir[0] * dist, origin[1] + dir[1] * dist, origin[2] + dir[2] * dist))
        }
        return best
    }

    /** Cuts with the horizontal plane at local height [z] (native, capped). Either half is null when the plane misses it. */
    fun cut(m: TriMesh, z: Float): Pair<TriMesh?, TriMesh?> {
        val (upper, lower) = org.orcaslicer.engine.NativeEngine.nativeCutMesh(m.v, z).let { it[0] to it[1] }
        return upper.takeIf { it.isNotEmpty() }?.let { onBed(TriMesh(it)) } to lower.takeIf { it.isNotEmpty() }?.let { onBed(TriMesh(it)) }
    }

    fun distance(a: FloatArray, b: FloatArray): Float = sqrt((a[0] - b[0]).let { it * it } + (a[1] - b[1]).let { it * it } + (a[2] - b[2]).let { it * it })

    fun writeBinaryStl(m: TriMesh, out: OutputStream) {
        val buf = ByteBuffer.allocate(84 + 50 * m.triangleCount).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(80); buf.putInt(m.triangleCount)
        for (t in 0 until m.triangleCount) {
            val n = triangleNormal(m, t); buf.putFloat(n[0]).putFloat(n[1]).putFloat(n[2])
            for (i in 0 until 9) buf.putFloat(m.v[t * 9 + i])
            buf.putShort(0)
        }
        out.write(buf.array())
    }
    fun writeBinaryStl(m: TriMesh, file: File) = file.outputStream().buffered().use { writeBinaryStl(m, it) }
}

/** Maps between a placed object's world space and its own local mesh space (offset, Z rotation and uniform scale about [pivot]). */
class PlacedFrame(private val t: ModelTransform, private val pivot: FloatArray) {
    private val cosR = cos(Math.toRadians(t.rotationZDeg.toDouble())).toFloat()
    private val sinR = sin(Math.toRadians(t.rotationZDeg.toDouble())).toFloat()
    fun toWorld(p: FloatArray): FloatArray {
        val lx = (p[0] - pivot[0]) * t.scale; val ly = (p[1] - pivot[1]) * t.scale; val lz = (p[2] - pivot[2]) * t.scale
        return floatArrayOf(pivot[0] + lx * cosR - ly * sinR + t.offsetXMm, pivot[1] + lx * sinR + ly * cosR + t.offsetYMm, pivot[2] + lz)
    }
    fun rayToLocal(origin: FloatArray, dir: FloatArray): Pair<FloatArray, FloatArray> {
        fun p(x: Float, y: Float, z: Float, isPoint: Boolean): FloatArray {
            val ox = if (isPoint) x - t.offsetXMm - pivot[0] else x; val oy = if (isPoint) y - t.offsetYMm - pivot[1] else y
            val rx = ox * cosR + oy * sinR; val ry = -ox * sinR + oy * cosR
            val oz = if (isPoint) z - pivot[2] else z
            return if (isPoint) floatArrayOf(pivot[0] + rx / t.scale, pivot[1] + ry / t.scale, pivot[2] + oz / t.scale) else floatArrayOf(rx / t.scale, ry / t.scale, oz / t.scale)
        }
        return p(origin[0], origin[1], origin[2], true) to p(dir[0], dir[1], dir[2], false)
    }
}
