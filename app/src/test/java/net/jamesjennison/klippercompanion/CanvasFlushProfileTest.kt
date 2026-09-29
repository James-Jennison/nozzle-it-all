package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The COSMOS CANVAS pack flushes like Elegoo's own CANVAS profile (ElegooSlicer, flush_multiplier 1). Without a value the
 * engine's default 0.3 applies: on the owner's CANVAS printer that purged 20.8 mm per colour change and black bled into cyan.
 */
class CanvasFlushProfileTest {
    private fun process(dir: String) = JSONObject(File(assets(), "slicer_profiles/$dir/process.json").readText())
    private fun assets(): File = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    @Test fun cosmosCanvasPackUsesElegoosFlushMultiplier() {
        assertEquals("1", process("elegoo_centauri_carbon_cosmos_afc").getString("flush_multiplier"))
        assertEquals(process("elegoo_centauri_carbon_canvas").getString("flush_multiplier"), process("elegoo_centauri_carbon_cosmos_afc").getString("flush_multiplier"))
        assertEquals("1", process("elegoo_centauri_carbon_cosmos_afc").getString("enable_prime_tower"))
    }
}
