package com.nozzleitall.project

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Projects move between platforms: this Kotlin library (Desktop, and Android as it adopts it) writes
 * schemas/fixtures/kotlin-written.3mf, which the Web App's tests read, and reads web-written.3mf, which the Web App's
 * tests write. Both fixtures are committed so each side is checked against the other's real output.
 */
class CrossPlatformFixtureTest {
    private val fixtures = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "schemas/fixtures") }.first { it.isDirectory }

    private fun cube(s: Float) = Mesh(floatArrayOf(0f, 0f, 0f, s, 0f, 0f, s, s, 0f, 0f, s, 0f, 0f, 0f, s, s, 0f, s, s, s, s, 0f, s, s),
        intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7))

    @Test fun writesTheFixtureTheWebAppReads() {
        val manifest = ProjectManifest.parse(JSONObject().put("schema", "nozzle.project").put("version", org.json.JSONArray().put(1).put(0))
            .put("projectId", "fixture-kotlin").put("revision", 3).put("name", "Two cubes")
            .put("createdBy", JSONObject().put("app", "Nozzle It All").put("platform", "desktop").put("version", "fixture"))
            .put("modifiedBy", JSONObject().put("app", "Nozzle It All").put("platform", "desktop").put("version", "fixture")).put("modifiedAtMillis", 1)
            .put("plates", org.json.JSONArray().put(JSONObject().put("index", 1).put("name", "Plate 1").put("objects", org.json.JSONArray()
                .put(JSONObject().put("objectId", 1).put("name", "A").put("materialSlot", 2)).put(JSONObject().put("objectId", 2).put("name", "B").put("materialSlot", 1)))))
            .put("materials", org.json.JSONArray().put(JSONObject().put("slot", 1).put("type", "PLA").put("colorHex", "#BE38F3")).put(JSONObject().put("slot", 2).put("type", "PETG").put("colorHex", "#FFFFFF")))
            .put("settings", JSONObject().put("preset", "standard").put("overrides", JSONObject().put("sparse_infill_density", "20%")))
            .put("extensions", JSONObject().put("android", JSONObject().put("keep", true)))
            .put("futureTopLevel", JSONObject().put("fromKotlin", true)))
        val project = Project3mf(listOf(ModelObject(1, "A", cube(20f), Transform.translate(100.0, 100.0, 0.0)), ModelObject(2, "B", cube(10f), Transform.translate(150.0, 120.0, 0.0))),
            mapOf("Title" to "Two cubes"), manifest, mapOf("Metadata/project_settings.config" to "{\"layer_height\":\"0.2\"}".toByteArray()))
        val target = File(fixtures, "kotlin-written.3mf")
        val current = runCatching { ThreeMf.read(target).manifest?.canonicalText() }.getOrNull()
        if (current != manifest.canonicalText()) ThreeMf.writeAtomically(project, target)
        assertEquals("fixture-kotlin", ThreeMf.read(target).manifest!!.projectId)
    }

    @Test fun readsWhatTheWebAppWrote() {
        val f = File(fixtures, "web-written.3mf")
        assertTrue("run the Web App tests (npm test in web/) to generate ${f.path}", f.exists())
        val p = ThreeMf.read(ByteArrayInputStream(f.readBytes()))
        val m = p.manifest!!
        assertEquals("fixture-web", m.projectId)
        assertEquals("web", m.createdBy.platform)
        assertEquals(listOf(2, 1), m.plates.single().objects.map { it.materialSlot })
        assertEquals(Transform.translate(150.0, 120.0, 0.0), p.objects[1].placement)
        assertEquals(12, p.objects[0].mesh.triangleCount)
        // Unknown data written by the Web App survives a Kotlin read and rewrite.
        val again = ThreeMf.read(ByteArrayInputStream(ThreeMf.write(p))).manifest!!.toJson()
        assertEquals(1, again.getJSONObject("futureTopLevel").getInt("x"))
        assertEquals(42, again.getJSONObject("extensions").getJSONObject("web").getInt("cameraOrbit"))
        assertEquals("keep me", String(p.passthrough.getValue("Auxiliaries/Other/notes.txt")))
    }
}
