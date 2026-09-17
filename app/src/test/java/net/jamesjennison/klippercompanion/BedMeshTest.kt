package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BedMeshTest {
    @Test fun parsesProfileNameBoundsAndMatrix() {
        val data = JSONObject("""{"status":{"bed_mesh":{"profile_name":"default","mesh_min":[10,10],"mesh_max":[200,200],"probed_matrix":[[0.01,0.02],[-0.01,0.0]]}}}""")
        val status = BedMesh.parse(data)
        assertEquals("default", status.profileName)
        assertEquals(listOf(10.0, 10.0), status.meshMin)
        assertEquals(listOf(200.0, 200.0), status.meshMax)
        assertEquals(listOf(listOf(0.01, 0.02), listOf(-0.01, 0.0)), status.probedMatrix)
        assertTrue(status.hasMesh)
        assertEquals(listOf(0.01, 0.02, -0.01, 0.0), status.flatValues)
    }
    @Test fun missingMeshReportsNoMesh() {
        val data = JSONObject("""{"status":{}}""")
        val status = BedMesh.parse(data)
        assertFalse(status.hasMesh)
        assertEquals("", status.profileName)
        assertTrue(status.probedMatrix.isEmpty())
    }
    @Test fun emptyRowsAlsoReportNoMesh() {
        val data = JSONObject("""{"status":{"bed_mesh":{"profile_name":"x","probed_matrix":[]}}}""")
        assertFalse(BedMesh.parse(data).hasMesh)
    }
    @Test fun oversizedMatrixIsRejected() {
        val row = (1..65).map { 0.0 }
        val rows = "[" + (1..2).joinToString(","){ row.toString() } + "]"
        val data = JSONObject("""{"status":{"bed_mesh":{"profile_name":"x","probed_matrix":$rows}}}""")
        assertThrows(IllegalArgumentException::class.java) { BedMesh.parse(data) }
    }
}
