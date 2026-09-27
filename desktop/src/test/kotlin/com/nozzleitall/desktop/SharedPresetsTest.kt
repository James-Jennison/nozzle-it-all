package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.QualityPreset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Desktop's guided presets are the family's (schemas/slicing/guided-presets.json), so a project slices the same everywhere. */
class SharedPresetsTest {
    @Test fun guidedPresetsMatchTheSharedDefinition() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "schemas/slicing").isDirectory }
        val shared = JSONObject(File(root, "schemas/slicing/guided-presets.json").readText()).getJSONArray("presets")
        val expected = (0 until shared.length()).map { shared.getJSONObject(it) }.map { p ->
            Triple(p.getString("id"), p.getString("label"), p.getJSONObject("overrides").let { o -> o.keySet().associateWith { o.getString(it) } }) }
        assertEquals(expected, QualityPreset.entries.map { Triple(it.name.lowercase(), it.label, it.overrides) })
    }
}
