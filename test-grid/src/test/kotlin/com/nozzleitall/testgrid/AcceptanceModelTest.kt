package com.nozzleitall.testgrid

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AcceptanceModelTest {
    private val dir = File(Support.root, "test-grid/src/main/resources/testgrid/models")

    @Test fun bundledModelsAreExactlyWhatTheGeneratorWrites() {
        AcceptanceModel.files().forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(dir, name).readBytes()) }
        assertEquals(Canon.write(AcceptanceModel.manifest()), File(dir, "models.json").readText())
    }

    @Test fun modelLibraryVerifiesHashesBeforeUse() {
        val lib = ModelLibrary.fromResources()
        assertEquals(listOf(AcceptanceModel.SINGLE_ID, AcceptanceModel.MULTI_ID), lib.entries.map { it.id })
        assertEquals(MaterialScope.SINGLE, lib.entry(AcceptanceModel.SINGLE_ID).scope)
        assertEquals(MaterialScope.MULTI, lib.entry(AcceptanceModel.MULTI_ID).scope)
        val tampered = ModelLibrary(JSONObject(File(dir, "models.json").readText())) { n -> File(dir, n).readBytes().also { if (n.endsWith("-a.stl")) it[100] = (it[100] + 1).toByte() } }
        try { tampered.materialize(AcceptanceModel.MULTI_ID, Support.tmp()); org.junit.Assert.fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("SHA-256")) }
    }

    @Test fun everyMeshIsClosedAndTwoManifold() {
        listOf(AcceptanceModel.SINGLE, AcceptanceModel.MULTI_A, AcceptanceModel.MULTI_B).forEach { solid ->
            val tris = AcceptanceModel.mesh(solid)
            val edges = HashMap<List<Double>, Int>()
            tris.forEach { t ->
                listOf(t.a to t.b, t.b to t.c, t.c to t.a).forEach { (p, q) ->
                    // Directed edge +1, its reverse -1: a closed, consistently oriented mesh sums to zero on every edge,
                    // and each undirected edge is used exactly twice.
                    val key = (p.toList() + q.toList())
                    edges[key] = (edges[key] ?: 0) + 1
                }
            }
            edges.forEach { (k, n) ->
                val rev = k.subList(3, 6) + k.subList(0, 3)
                assertEquals("${solid.name} edge $k used $n times", 1, n)
                assertEquals("${solid.name} edge $k has no opposite", 1, edges[rev])
            }
            // Outward normals: the signed volume is positive and matches the box arithmetic.
            val volume = tris.sumOf { t -> (t.a[0] * (t.b[1] * t.c[2] - t.b[2] * t.c[1]) - t.a[1] * (t.b[0] * t.c[2] - t.b[2] * t.c[0]) + t.a[2] * (t.b[0] * t.c[1] - t.b[1] * t.c[0])) / 6 }
            assertTrue("${solid.name} volume $volume", volume > 0)
        }
        val v = AcceptanceModel.mesh(AcceptanceModel.SINGLE).sumOf { t -> (t.a[0] * (t.b[1] * t.c[2] - t.b[2] * t.c[1]) - t.a[1] * (t.b[0] * t.c[2] - t.b[2] * t.c[0]) + t.a[2] * (t.b[0] * t.c[1] - t.b[1] * t.c[0])) / 6 }
        // base 60*60*0.6 - hole 8*8*0.6 + tower ring (400-64)*10 + pillars 2*16*15 + piers 2*80*8 + deck 26*20*1.2 + wall 20*1.2*8
        val expected = 2160.0 - 38.4 + 3360.0 + 480.0 + 1280.0 + 624.0 + 192.0
        assertEquals(expected, v, 0.01)
    }

    @Test fun publishedDimensionsMatchTheGeometry() {
        val d = AcceptanceModel.SINGLE_DIMENSIONS
        val tower = AcceptanceModel.SINGLE.boxes[1]; val hole = AcceptanceModel.SINGLE.holes[0]
        assertEquals(d.getJSONObject("tower").getDouble("outside"), tower.x1 - tower.x0, 1e-9)
        assertEquals(d.getJSONObject("tower").getDouble("hole"), hole.x1 - hole.x0, 1e-9)
        assertEquals(d.getJSONObject("tower").getDouble("height"), tower.z1, 1e-9)
        val p1 = AcceptanceModel.SINGLE.boxes[2]; val p2 = AcceptanceModel.SINGLE.boxes[3]
        assertEquals(d.getJSONObject("pillars").getDouble("gap"), p2.x0 - p1.x1, 1e-9)
        val pier1 = AcceptanceModel.SINGLE.boxes[4]; val pier2 = AcceptanceModel.SINGLE.boxes[5]
        assertEquals(d.getJSONObject("bridge").getDouble("span"), pier2.x0 - pier1.x1, 1e-9)
        assertEquals(d.getJSONObject("thinWall").getDouble("thickness"), AcceptanceModel.SINGLE.boxes[7].let { it.y1 - it.y0 }, 1e-9)
    }

    @Test fun manualInstructionsComeFromTheSuite() {
        val md = ManualInstructions.markdown(Support.suite("cosmos-centauri-carbon"), ModelLibrary.fromResources())
        assertTrue(md.contains("**Approve:** Heat the active nozzle to 60 °C"))
        assertTrue(md.contains("not** evidence for: `elegoo-stock`, `opencentauri-patched`"))
        assertTrue(md.contains("stock-elegoo-m729.gcode"))
        assertTrue(md.contains("Needs detected hardware: canvas"))
    }
}
