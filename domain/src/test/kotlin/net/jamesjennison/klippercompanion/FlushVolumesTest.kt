package net.jamesjennison.klippercompanion

import net.jamesjennison.klippercompanion.FlushVolumes.Method
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** FlushVolumes against the slicers' own code (schemas/fixtures/flush-volumes.json, scripts/flush_volumes_golden.sh). */
class FlushVolumesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "schemas/fixtures").isDirectory }
    private val golden = JSONObject(File(root, "schemas/fixtures/flush-volumes.json").readText())
    private fun rows(k: String) = golden.getJSONArray(k).let { a -> (0 until a.length()).map { a.getJSONArray(it) } }

    @Test fun snapmakerMatchesUpstreamOnEveryGoldenPair() {
        val wrong = rows("pairs").filter { p -> FlushVolumes.calc(p.getString(0), p.getString(1), p.getInt(2), Method.SNAPMAKER) != p.getInt(3) }
        assertTrue("${wrong.size} differ, e.g. ${wrong.take(5)}", wrong.isEmpty())
    }

    @Test fun orcaMatchesUpstreamOnEveryGoldenPair() {
        val all = rows("orca")
        val wrong = all.filter { p -> FlushVolumes.calc(p.getString(0), p.getString(1), p.getInt(2), Method.ORCA, p.getInt(3)) != p.getInt(5) }
        assertTrue("${wrong.size} of ${all.size} differ, e.g. ${wrong.take(5)}", wrong.isEmpty())
        // The measured flushes are really in use: some pairs aren't the formula's (which never goes below 60).
        assertTrue(all.any { it.getInt(3) == 0 && it.getInt(5) < 60 })
    }

    @Test fun elegooMatchesUpstreamOnEveryGoldenPair() {
        val all = rows("elegoo")
        val wrong = all.filter { p -> FlushVolumes.calc(p.getString(0), p.getString(1), p.getInt(2), Method.ELEGOO, p.getInt(3), p.getString(4)) != p.getInt(5) }
        assertTrue("${wrong.size} of ${all.size} differ, e.g. ${wrong.take(5)}", wrong.isEmpty())
        assertEquals(900, FlushVolumes.calc("#000000", "#FFFFFF", 107, Method.ELEGOO, 0, "Elegoo Centauri Carbon 2 0.4 nozzle"))
        assertNotEquals(900, FlushVolumes.calc("#000000", "#FFFFFF", 107, Method.ELEGOO, 0, "Elegoo Centauri Carbon 0.4 nozzle"))
    }

    @Test fun eachPrinterUsesItsOwnSlicersMethod() {
        fun m(model: String) = FlushVolumes.methodFor(JSONObject().put("printer_model", model))
        assertEquals(Method.SNAPMAKER, m("Snapmaker U1")); assertEquals(Method.ELEGOO, m("Elegoo Centauri Carbon 2"))
        assertEquals(Method.ORCA, m("Bambu Lab P1S")); assertEquals(Method.ORCA, m("Prusa MK4S"))
    }

    @Test fun matrixFollowsUpstreamsSupportRules() {
        for (method in Method.values()) {
            val m = FlushVolumes.matrix(listOf("#000000", "#FFFFFF", "#FF0000"), listOf(0, 0, 0), listOf(false, false, true), method)
            assertEquals(listOf(0, 0, 0), listOf(m[0], m[4], m[8]))
            assertEquals(FlushVolumes.TO_SUPPORT, m[2]); assertEquals(FlushVolumes.TO_SUPPORT, m[5])
            assertTrue(m[6] >= method.minFromSupport && m[7] >= method.minFromSupport)
        }
    }

    @Test fun recalculatingOneSlotKeepsOtherEdits() {
        val colours = listOf("#000000", "#FFFFFF", "#FF0000")
        val edited = FlushVolumes.matrix(colours, listOf(0, 0, 0)).toMutableList().apply { this[1] = 999 }
        val after = FlushVolumes.recalcSlot(edited, 2, listOf("#000000", "#FFFFFF", "#00FF00"), listOf(0, 0, 0))
        assertEquals(999, after[1])
        assertEquals(FlushVolumes.calc("#000000", "#00FF00", 0), after[2])
    }

    @Test fun minimumVolumesFromNozzleAndCutRetraction() {
        val machine = JSONObject().put("nozzle_volume", "107").put("enable_long_retraction_when_cut", "2")
            .put("long_retractions_when_cut", JSONArray(listOf("1"))).put("retraction_distances_when_cut", JSONArray(listOf("18")))
        val off = JSONObject().put("filament_long_retractions_when_cut", JSONArray(listOf("0")))
        val own = JSONObject().put("filament_long_retractions_when_cut", JSONArray(listOf("1"))).put("filament_retraction_distances_when_cut", JSONArray(listOf("10")))
        val unset = JSONObject().put("filament_long_retractions_when_cut", JSONArray(listOf("nil")))
        // 107 - pi * 1.75^2 / 4 * retraction, truncated as upstream does.
        assertEquals(listOf(107, (107 - Math.PI * 1.75 * 1.75 / 4 * 10).toInt(), (107 - Math.PI * 1.75 * 1.75 / 4 * 18).toInt()),
            FlushVolumes.minimumVolumes(machine, listOf(off, own, unset)))
        assertEquals(listOf(0), FlushVolumes.minimumVolumes(null, listOf(null)))
    }
}
