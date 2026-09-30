package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Android's slicing recipe checked against the fixture the Web App's tests also check (schemas/fixtures/multitool.json). */
class SharedFixturesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "schemas/fixtures").isDirectory }

    @Test fun multiMaterialRecipeMatchesTheSharedFixture() {
        val f = JSONObject(File(root, "schemas/fixtures/multitool.json").readText())
        val input = f.getJSONObject("input")
        val slots = input.getJSONArray("slots").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.mapIndexed { i, s ->
            MaterialProfile("s$i", "Slot ${i + 1}", s.getString("type"), colorHex = s.optString("colorHex"), tempNozzleC = if (s.has("nozzleC")) s.getInt("nozzleC") else null, source = MaterialSource.CUSTOM)
        }
        val fallback = MaterialProfile("fallback", "Fallback", "PLA", tempNozzleC = input.getInt("fallbackNozzleC"), source = MaterialSource.BUNDLED)
        val actual = MultiToolFilamentConfig.overridesFor(input.getDouble("diameter"), slots, fallback)
        val expected = f.getJSONObject("expected").let { e -> e.keySet().associateWith { e.getString(it) } }
        assertEquals(expected, actual)
    }
}
