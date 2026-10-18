package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.cornerNormals
import com.nozzleitall.project.Mesh
import org.junit.Assert.*
import org.junit.Test

class ShadingNormalsTest {
    // Two flat cap triangles share their boundary vertices with vertical walls.
    private fun cappedMesh() = Mesh(floatArrayOf(
        0f,0f,0f, 1f,0f,0f, 1f,1f,0f, 0f,1f,0f,
        0f,0f,1f, 1f,0f,1f, 1f,1f,1f, 0f,1f,1f
    ), intArrayOf(0,1,2, 0,2,3, 0,4,5, 0,5,1, 1,5,6, 1,6,2, 2,6,7, 2,7,3, 3,7,4, 3,4,0))

    @Test fun planarCapHasOneNormalEvenAtSharedSidewallCorners() {
        val m = cappedMesh(); val vertices = m.vertices.copyOf(); val triangles = m.triangles.copyOf()
        val n = cornerNormals(m)
        for (c in 0 until 6) assertArrayEquals(floatArrayOf(0f,0f,1f), n.copyOfRange(c*3,c*3+3),0.000001f)
        assertArrayEquals(vertices,m.vertices,0f); assertArrayEquals(triangles,m.triangles)
    }

    @Test fun shallowCurvesStillHaveSmoothSharedCornerNormals() {
        val m = Mesh(floatArrayOf(0f,0f,0f, 1f,0f,0f, 0f,1f,0f, 0f,-1f,0.3f),intArrayOf(0,1,2, 1,0,3))
        val n = cornerNormals(m)
        assertArrayEquals(n.copyOfRange(0,3),n.copyOfRange(12,15),0.000001f)
        assertTrue(n[1] > 0f); assertTrue(n[2] > 0.9f)
    }

    @Test fun degenerateFacesAndUnusedVerticesStayFinite() {
        val m = Mesh(floatArrayOf(0f,0f,0f, 1f,0f,0f, 0f,1f,0f, 9f,9f,9f),intArrayOf(0,0,1, 0,1,2))
        val n = cornerNormals(m)
        assertEquals(m.triangleCount*9,n.size); assertTrue(n.all { it.isFinite() })
        assertArrayEquals(floatArrayOf(0f,0f,1f),n.copyOfRange(9,12),0f)
    }
}
