package net.jamesjennison.klippercompanion

import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure math backing the 3D bed-mesh view: smoothing a sparse probed grid into a denser surface,
 * and rotating/projecting it for an orbit camera. No Compose/Android types, so it's fully
 * unit-testable; BedMesh3DView.kt turns this into a Canvas drawing.
 */
object BedMeshGeometry {
    /** One segment of the standard Catmull-Rom cubic through p1..p2, parameterized by t in [0,1]. */
    private fun catmullRom1D(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t; val t3 = t2 * t
        return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
    }

    /**
     * Upsamples one row/column by [subdivisions] extra points between each pair of originals,
     * passing exactly through every original value. A missing p0/p3 past either edge is linearly
     * extrapolated from the nearest two real points (rather than clamped/duplicated), which keeps
     * a straight run of probe points straight all the way to the edge instead of visibly
     * flattening the last segment. Fewer than 2 input points can't be interpolated meaningfully
     * and are returned unchanged.
     */
    fun subdivide1D(values: List<Double>, subdivisions: Int): List<Double> {
        if (values.size < 2 || subdivisions < 1) return values
        val result = ArrayList<Double>((values.size - 1) * (subdivisions + 1) + 1)
        for (i in 0 until values.size - 1) {
            val p0 = if (i == 0) 2 * values[0] - values[1] else values[i - 1]
            val p1 = values[i]
            val p2 = values[i + 1]
            val p3 = if (i == values.size - 2) 2 * values.last() - values[values.size - 2] else values[i + 2]
            for (s in 0 until subdivisions + 1) result.add(catmullRom1D(p0, p1, p2, p3, s.toDouble() / (subdivisions + 1)))
        }
        result.add(values.last())
        return result
    }

    /** Separable 2D Catmull-Rom: subdivide every row, then subdivide the resulting columns. */
    fun subdivideGrid(matrix: List<List<Double>>, subdivisions: Int): List<List<Double>> {
        if (matrix.isEmpty() || matrix.any { it.size != matrix.first().size }) return matrix
        val widened = matrix.map { subdivide1D(it, subdivisions) }
        if (widened.isEmpty() || widened.first().isEmpty()) return widened
        val cols = widened.first().size
        val heightened = (0 until cols).map { c -> subdivide1D(widened.map { it[c] }, subdivisions) }
        val rows = heightened.firstOrNull()?.size ?: return widened
        return (0 until rows).map { r -> (0 until cols).map { c -> heightened[c][r] } }
    }

    data class Vec3(val x: Double, val y: Double, val z: Double)
    /** yaw around the vertical (Z) axis, then pitch around the (rotated) horizontal (X) axis. */
    fun orbit(v: Vec3, yaw: Double, pitch: Double): Vec3 {
        val cy = cos(yaw); val sy = sin(yaw)
        val yawed = Vec3(v.x * cy - v.y * sy, v.x * sy + v.y * cy, v.z)
        val cp = cos(pitch); val sp = sin(pitch)
        return Vec3(yawed.x, yawed.y * cp - yawed.z * sp, yawed.y * sp + yawed.z * cp)
    }
    /** Orthographic projection: (x, z) becomes the screen plane; y (depth, into the screen) is
     * kept only for back-to-front painter's-algorithm sorting, never drawn. */
    data class Projected(val screenX: Double, val screenY: Double, val depth: Double)
    fun project(v: Vec3): Projected = Projected(v.x, -v.z, v.y)
}
