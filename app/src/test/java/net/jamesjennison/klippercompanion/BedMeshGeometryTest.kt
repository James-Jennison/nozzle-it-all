package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class BedMeshGeometryTest {
    @Test fun subdivide1DIsUnchangedBelowTwoPointsOrZeroSubdivisions() {
        assertEquals(listOf(1.0), BedMeshGeometry.subdivide1D(listOf(1.0), 3))
        assertEquals(emptyList<Double>(), BedMeshGeometry.subdivide1D(emptyList(), 3))
        assertEquals(listOf(1.0, 2.0), BedMeshGeometry.subdivide1D(listOf(1.0, 2.0), 0))
    }
    @Test fun subdivide1DPassesThroughEveryOriginalValue() {
        val values = listOf(0.0, 1.0, 4.0, 2.0, 3.0)
        val result = BedMeshGeometry.subdivide1D(values, 3)
        // Each original point lands at index i*(subdivisions+1) in the widened list.
        values.forEachIndexed { i, v -> assertEquals(v, result[i * 4], 1e-9) }
    }
    @Test fun subdivide1DHasTheExpectedSize() {
        val values = listOf(0.0, 1.0, 2.0, 3.0)
        assertEquals((values.size - 1) * (5 + 1) + 1, BedMeshGeometry.subdivide1D(values, 5).size)
    }
    @Test fun subdivide1DOfALinearRampStaysLinear() {
        val values = listOf(0.0, 2.0, 4.0, 6.0)
        val result = BedMeshGeometry.subdivide1D(values, 3)
        val step = result.last() / (result.size - 1)
        result.forEachIndexed { i, v -> assertEquals(i * step, v, 1e-9) }
    }
    @Test fun subdivideGridPassesThroughOriginalPointsAndSizesCorrectly() {
        val matrix = listOf(listOf(0.0, 1.0, 0.0), listOf(1.0, 2.0, 1.0), listOf(0.0, 1.0, 0.0))
        val result = BedMeshGeometry.subdivideGrid(matrix, 2)
        val expectedSize = (matrix.size - 1) * (2 + 1) + 1 // = 7 per dimension
        assertEquals(expectedSize, result.size)
        assertEquals(expectedSize, result.first().size)
        for (r in matrix.indices) for (c in matrix[r].indices) assertEquals(matrix[r][c], result[r * 3][c * 3], 1e-9)
    }
    @Test fun subdivideGridRejectsRaggedInputUnchanged() {
        val ragged = listOf(listOf(0.0, 1.0), listOf(0.0))
        assertEquals(ragged, BedMeshGeometry.subdivideGrid(ragged, 3))
    }
    @Test fun subdivideGridOfEmptyIsEmpty() {
        assertTrue(BedMeshGeometry.subdivideGrid(emptyList(), 3).isEmpty())
    }
    @Test fun orbitAtZeroAnglesIsIdentity() {
        val v = BedMeshGeometry.Vec3(1.0, 2.0, 3.0)
        val r = BedMeshGeometry.orbit(v, 0.0, 0.0)
        assertEquals(v.x, r.x, 1e-9); assertEquals(v.y, r.y, 1e-9); assertEquals(v.z, r.z, 1e-9)
    }
    @Test fun yawRotatesXIntoYAtNinetyDegrees() {
        val r = BedMeshGeometry.orbit(BedMeshGeometry.Vec3(1.0, 0.0, 0.0), PI / 2, 0.0)
        assertEquals(0.0, r.x, 1e-6); assertEquals(1.0, r.y, 1e-6); assertEquals(0.0, r.z, 1e-6)
    }
    @Test fun pitchRotatesYIntoZAtNinetyDegrees() {
        val r = BedMeshGeometry.orbit(BedMeshGeometry.Vec3(0.0, 1.0, 0.0), 0.0, PI / 2)
        assertEquals(0.0, r.x, 1e-6); assertEquals(0.0, r.y, 1e-6); assertEquals(1.0, r.z, 1e-6)
    }
    @Test fun projectMapsXZToScreenAndYToDepth() {
        val p = BedMeshGeometry.project(BedMeshGeometry.Vec3(1.0, 2.0, 3.0))
        assertEquals(1.0, p.screenX, 1e-9); assertEquals(-3.0, p.screenY, 1e-9); assertEquals(2.0, p.depth, 1e-9)
    }
}
