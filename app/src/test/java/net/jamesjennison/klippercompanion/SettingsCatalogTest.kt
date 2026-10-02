package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class SettingsCatalogTest {
    private fun def(key: String) = SettingsCatalog.get(key)!!
    @Test fun keysAreUniqueAndEveryChoiceHasOptions() {
        assertEquals(SettingsCatalog.all.size, SettingsCatalog.all.map { it.key }.toSet().size)
        SettingsCatalog.all.forEach { d -> (d.type as? SettingType.Choice)?.let { assertTrue(d.key, it.options.size >= 2) }; assertTrue(d.key, d.help.isNotBlank()) }
    }
    @Test fun tiersDiscloseProgressively() {
        val basic = SettingsCatalog.visible(SettingTier.BASIC).size; val adv = SettingsCatalog.visible(SettingTier.ADVANCED).size; val exp = SettingsCatalog.visible(SettingTier.EXPERT, "", MultiToolFamily.FILAMENT_SWAP).size
        assertTrue(basic in 1 until adv); assertTrue(adv < exp); assertEquals(SettingsCatalog.all.size, exp)
    }
    @Test fun searchMatchesLabelKeyGroupAndHelp() {
        assertTrue(SettingsCatalog.visible(SettingTier.EXPERT, "gyroid").isEmpty()) // option names are not searched
        assertEquals(listOf("wall_loops"), SettingsCatalog.visible(SettingTier.EXPERT, "WALL COUNT").map { it.key })
        assertTrue(SettingsCatalog.visible(SettingTier.EXPERT, "support").all { d -> listOf(d.label, d.key, d.group, d.help).any { it.lowercase().contains("support") } })
        assertTrue(SettingsCatalog.visible(SettingTier.EXPERT, "speed").size >= 5)
        assertTrue(SettingsCatalog.visible(SettingTier.BASIC, "ironing").isEmpty()) // expert-only hidden at Basic
    }
    @Test fun validationEnforcesRangesAndFormatsExactly() {
        assertEquals("4", SettingsCatalog.validate(def("wall_loops"), " 4 "))
        for (bad in listOf("0", "21", "abc", "", "2.5")) assertNull(bad, SettingsCatalog.validate(def("wall_loops"), bad))
        assertEquals("0.25", SettingsCatalog.validate(def("initial_layer_print_height"), "0.25"))
        assertNull(SettingsCatalog.validate(def("initial_layer_print_height"), "NaN")); assertNull(SettingsCatalog.validate(def("initial_layer_print_height"), "9"))
        assertEquals("5", SettingsCatalog.validate(def("brim_width"), "5.0"))
        assertEquals("1", SettingsCatalog.validate(def("spiral_mode"), "on")); assertNull(SettingsCatalog.validate(def("spiral_mode"), "maybe"))
        assertEquals("gyroid", SettingsCatalog.validate(def("sparse_infill_pattern"), "Gyroid")); assertNull(SettingsCatalog.validate(def("sparse_infill_pattern"), "zigzag; rm"))
    }
    @Test fun sanitizeDropsUnknownKeysAndInvalidValues() {
        assertEquals(mapOf("wall_loops" to "3"), SettingsCatalog.sanitize(mapOf("wall_loops" to "3", "wall_loops2" to "1", "top_shell_layers" to "-1", "gcode_flavor" to "x")))
    }
    @Test fun customProfilesRoundTripAndSanitizeOnDecode() {
        val p = CustomProfile("Strong \"PETG\"", "GENERIC_KLIPPER", mapOf("wall_loops" to "5", "sparse_infill_pattern" to "gyroid"))
        assertEquals(p, CustomProfile.decode(p.encode()))
        val tampered = """{"name":"x","base":"b","overrides":{"wall_loops":"5","machine_start_gcode":"M112"}}"""
        assertEquals(mapOf("wall_loops" to "5"), CustomProfile.decode(tampered)!!.overrides)
        assertNull(CustomProfile.decode("not json"))
        val based = p.copy(basePreset = "0.12mm Fine @Elegoo CC 0.4 nozzle")
        assertEquals(based, CustomProfile.decode(based.encode()))
        assertNull(CustomProfile.decode(p.encode())!!.basePreset)
    }
    @Test fun compareReportsOnlyDifferingKeys() {
        val diff = compareOverrides(mapOf("wall_loops" to "3", "brim_width" to "5"), mapOf("wall_loops" to "4", "brim_width" to "5", "spiral_mode" to "1"))
        assertEquals(listOf(SettingDifference("spiral_mode", null, "1"), SettingDifference("wall_loops", "3", "4")), diff)
        assertTrue(compareOverrides(mapOf("a" to "1"), mapOf("a" to "1")).isEmpty())
    }
}
