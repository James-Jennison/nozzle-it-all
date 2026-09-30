package com.nozzleitall.project

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThreeMfTest {
    private fun cube(size: Float = 20f): Mesh {
        val s = size
        val v = floatArrayOf(0f, 0f, 0f, s, 0f, 0f, s, s, 0f, 0f, s, 0f, 0f, 0f, s, s, 0f, s, s, s, s, 0f, s, s)
        val t = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
        return Mesh(v, t)
    }

    private fun manifest(extra: JSONObject.() -> Unit = {}) = ProjectManifest.parse(JSONObject().put("schema", "nozzle.project")
        .put("version", org.json.JSONArray().put(1).put(0)).put("projectId", "p-123").put("revision", 4).put("name", "Two cubes")
        .put("createdBy", JSONObject().put("app", "Nozzle It All").put("platform", "desktop").put("version", "0.1.0"))
        .put("modifiedBy", JSONObject().put("app", "Nozzle It All").put("platform", "desktop").put("version", "0.1.0"))
        .put("printer", JSONObject().put("model", "Snapmaker U1").put("firmware", "PAXX"))
        .put("plates", org.json.JSONArray().put(JSONObject().put("index", 1).put("objects", org.json.JSONArray()
            .put(JSONObject().put("objectId", 1).put("materialSlot", 2)).put(JSONObject().put("objectId", 2).put("materialSlot", 1)))))
        .put("materials", org.json.JSONArray().put(JSONObject().put("slot", 1).put("type", "PLA").put("colorHex", "#BE38F3").put("toolhead", 0))
            .put(JSONObject().put("slot", 2).put("type", "PETG").put("colorHex", "#FFFFFF").put("toolhead", 1)))
        .put("settings", JSONObject().put("preset", "standard").put("overrides", JSONObject().put("sparse_infill_density", "20%")))
        .apply(extra))

    private fun sample(m: ProjectManifest? = manifest()) = Project3mf(
        listOf(ModelObject(1, "Cube A", cube(), Transform.translate(100.0, 100.0, 0.0)), ModelObject(2, "Cube B", cube(10f), Transform.translate(150.0, 120.0, 0.0))),
        mapOf("Title" to "Two cubes", "Designer" to "Someone", "Application" to "Other App"), m,
        mapOf("Metadata/project_settings.config" to "{\"layer_height\":\"0.2\"}".toByteArray(), "Auxiliaries/Other/notes.txt" to "keep me".toByteArray()))

    @Test fun roundTripKeepsGeometryPlacementMetadataManifestAndUnknownEntries() {
        val back = ThreeMf.read(ByteArrayInputStream(ThreeMf.write(sample())))
        assertEquals(2, back.objects.size)
        assertEquals(12, back.objects[0].mesh.triangleCount)
        assertEquals(Transform.translate(150.0, 120.0, 0.0), back.objects[1].placement)
        assertEquals("Someone", back.metadata["Designer"])
        assertEquals("p-123", back.metadata[ProjectManifest.META_PROJECT_ID])
        assertEquals(manifest().canonicalText(), back.manifest!!.canonicalText())
        assertEquals("keep me", String(back.passthrough["Auxiliaries/Other/notes.txt"]!!))
        assertTrue(back.passthrough.containsKey("Metadata/project_settings.config"))
        assertNull(back.manifestProblem)
    }

    @Test fun unknownManifestFieldsSurviveAnOlderReader() {
        val newer = manifest {
            put("version", org.json.JSONArray().put(1).put(3))
            put("futureTopLevel", JSONObject().put("x", 1))
            put("extensions", JSONObject().put("android", JSONObject().put("cameraOrbit", 42)))
            getJSONArray("materials").getJSONObject(0).put("spoolmanId", 77)
            getJSONObject("printer").put("serialHint", "U1-9")
        }
        val text = ThreeMf.read(ByteArrayInputStream(ThreeMf.write(sample(newer)))).manifest!!.toJson()
        assertEquals(1, text.getJSONObject("futureTopLevel").getInt("x"))
        assertEquals(3, text.getJSONArray("version").getInt(1)) // the newer minor version is not downgraded
        assertEquals(42, text.getJSONObject("extensions").getJSONObject("android").getInt("cameraOrbit"))
        assertEquals(77, text.getJSONArray("materials").getJSONObject(0).getInt("spoolmanId"))
        assertEquals("U1-9", text.getJSONObject("printer").getString("serialHint"))
    }

    @Test fun newerMajorVersionIsRefusedWithAPlainMessage() {
        val bytes = ThreeMf.write(sample()).let { replaceEntry(it, ProjectManifest.ARCHIVE_PATH, manifest().toJson().put("version", org.json.JSONArray().put(2).put(0)).toString().toByteArray()) }
        val e = assertThrows(IncompatibleProjectException::class.java) { ThreeMf.read(ByteArrayInputStream(bytes)) }
        assertTrue(e.message!!.contains("newer Nozzle It All"))
    }

    @Test fun damagedManifestStillOpensTheModels() {
        val bytes = replaceEntry(ThreeMf.write(sample()), ProjectManifest.ARCHIVE_PATH, "{not json".toByteArray())
        val p = ThreeMf.read(ByteArrayInputStream(bytes))
        assertEquals(2, p.objects.size)
        assertNull(p.manifest)
        assertNotNull(p.manifestProblem)
    }

    @Test fun manifestFromAnotherProjectIsNotApplied() {
        val other = manifest { put("projectId", "someone-else") }
        val bytes = replaceEntry(ThreeMf.write(sample()), ProjectManifest.ARCHIVE_PATH, other.toJson().toString().toByteArray())
        val p = ThreeMf.read(ByteArrayInputStream(bytes))
        assertNull(p.manifest); assertNotNull(p.manifestProblem)
    }

    @Test fun truncatedFileFailsSafely() {
        val bytes = ThreeMf.write(sample())
        val e = assertThrows(ProjectFormatException::class.java) { ThreeMf.read(ByteArrayInputStream(bytes.copyOf(bytes.size / 2))) }
        assertTrue(e.message, e.message!!.isNotBlank())
    }

    @Test fun pathTraversalAndXmlEntitiesAreRejected() {
        val evil = zip("../../etc/passwd" to "x".toByteArray(), "3D/3dmodel.model" to "<model/>".toByteArray())
        assertThrows(ProjectFormatException::class.java) { ThreeMf.read(ByteArrayInputStream(evil)) }
        val xxe = zip("3D/3dmodel.model" to """<?xml version="1.0"?><!DOCTYPE m [<!ENTITY e SYSTEM "file:///etc/passwd">]><model>&e;</model>""".toByteArray())
        assertThrows(ProjectFormatException::class.java) { ThreeMf.read(ByteArrayInputStream(xxe)) }
    }

    @Test fun triangleLimitIsEnforced() {
        assertThrows(ProjectFormatException::class.java) { ThreeMf.read(ByteArrayInputStream(ThreeMf.write(sample())), ReadLimits(maxTriangles = 5)) }
    }

    @Test fun orcaStyleComponentsInSeparatePartsAreFlattened() {
        // The shape Orca-derived apps write: a wrapper object whose component points at a mesh in 3D/Objects/.
        val part = """<?xml version="1.0" encoding="UTF-8"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources>
<object id="1" type="model"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="0" y="10" z="0"/></vertices>
<triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh></object></resources><build/></model>"""
        val root = """<?xml version="1.0" encoding="UTF-8"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06">
<metadata name="Application">OrcaSlicer</metadata><metadata name="nozzle:ProjectId">p-123</metadata><resources>
<object id="2" name="Wedge" type="model"><components><component p:path="/3D/Objects/wedge_1.model" objectid="1" transform="1 0 0 0 1 0 0 0 1 5 0 0"/></components></object>
</resources><build><item objectid="2" transform="1 0 0 0 1 0 0 0 1 100 100 0"/></build></model>"""
        val bytes = zip("3D/3dmodel.model" to root.toByteArray(), "3D/Objects/wedge_1.model" to part.toByteArray(),
            "Metadata/model_settings.config" to "<config/>".toByteArray(), ProjectManifest.ARCHIVE_PATH to manifest().toJson().toString().toByteArray())
        val p = ThreeMf.read(ByteArrayInputStream(bytes))
        val o = p.objects.single()
        assertEquals("Wedge", o.name)
        assertEquals(1, o.mesh.triangleCount)
        assertEquals(15f, o.mesh.vertices[3], 0f) // component translation baked into the mesh
        assertEquals(Transform.translate(100.0, 100.0, 0.0), o.placement)
        assertNotNull(p.manifest)
        assertFalse(p.passthrough.keys.any { it.startsWith("3D/") })
        // And it writes back as a canonical single-file 3MF that reads the same.
        val again = ThreeMf.read(ByteArrayInputStream(ThreeMf.write(p)))
        assertEquals(15f, again.objects.single().mesh.vertices[3], 0f)
        // Per-part settings are folded into the geometry's paint on read, so they aren't carried (they'd name old ids).
        assertFalse(again.passthrough.containsKey("Metadata/model_settings.config"))
    }

    @Test fun atomicWriteNeverLeavesAHalfWrittenProject() {
        val dir = kotlin.io.path.createTempDirectory("proj").toFile()
        val target = File(dir, "p.3mf")
        ThreeMf.writeAtomically(sample(), target)
        val before = target.readBytes()
        ThreeMf.writeAtomically(sample(manifest { put("revision", 5) }), target)
        assertEquals(5, ThreeMf.read(target).manifest!!.revision)
        assertTrue(before.isNotEmpty())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun replaceEntry(archive: ByteArray, name: String, bytes: ByteArray): ByteArray {
        val all = ThreeMf.readEntries(ByteArrayInputStream(archive)).toMutableMap()
        all[name] = bytes
        return zip(*all.map { it.key to it.value }.toTypedArray())
    }
}
