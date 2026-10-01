package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class ProcessPresetsTest {
    private val json = """
        {"default": "0.20mm Standard @Elegoo CC 0.4 nozzle", "presets": [
         {"name": "0.10mm Color Mixing @Elegoo CC 0.4 nozzle", "label": "0.10mm Color Mixing", "layer_height": "0.1", "infill": "15%",
          "file": "slicer_profiles/_processes/Elegoo/0_10mm_color_mixing_elegoo_cc_0_4_nozzle.json", "made_by": "Nozzle It All", "color_mixing": true},
         {"name": "0.20mm Standard @Elegoo CC 0.4 nozzle", "label": "0.20mm Standard", "layer_height": "0.2", "infill": "15",
          "file": "slicer_profiles/elegoo_centauri_carbon_cosmos_afc/process.json"},
         {"name": "0.08mm HueForge @Creality K2 Plus 0.4 nozzle", "label": "0.08mm HueForge", "layer_height": "0.08", "infill": "", "file": "x.json"}
        ]}
    """.trimIndent()

    @Test fun parsesEveryField() {
        val p = ProcessPresets.parse(json)
        assertEquals("0.20mm Standard @Elegoo CC 0.4 nozzle", p.default.name)
        val mix = p.presets[0]
        assertEquals(0.1, mix.layerHeightMm!!, 0.0)
        assertEquals(15, mix.infillPercent)
        assertTrue(mix.colorMixing)
        assertEquals("0.10mm Color Mixing (Nozzle It All)", mix.displayLabel)
        assertEquals("0.20mm Standard", p.default.displayLabel)
        assertEquals(15, p.default.infillPercent) // a bare number some vendor presets use
        assertNull(p.presets[2].infillPercent)
        assertSame(mix, p.colorMixingPreset)
        assertTrue(p.hasChoice)
    }

    @Test fun resolveIsTheDefaultForNullAndNothingForAnUnknownName() {
        val p = ProcessPresets.parse(json)
        assertEquals(p.default, p.resolve(null))
        assertEquals("0.10mm Color Mixing @Elegoo CC 0.4 nozzle", p.resolve("0.10mm Color Mixing @Elegoo CC 0.4 nozzle")?.name)
        assertNull(p.resolve("0.20mm Standard @Snapmaker U1 (0.4 nozzle)"))
    }

    @Test fun aDefaultThatIsNotListedIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ProcessPresets.parse("""{"default": "missing", "presets": [{"name": "a", "file": "a.json"}]}""") }
    }

    @Test fun aPackWithoutProcessesJsonOffersOnlyItsOwnProcess() {
        val p = ProcessPresets.single("0.20mm Standard @Generic", "slicer_profiles/generic/process.json", 0.2, 15)
        assertFalse(p.hasChoice)
        assertEquals("0.20mm Standard", p.default.label)
        assertNull(p.colorMixingPreset)
    }
}
