package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

/** Android offers every single-valued process setting the engine accepts, not only the curated 32. */
class EngineSettingsTest {
    @Test fun theEngineListHoldsHundredsOfSettingsAndNoneOfTheCuratedOnes() {
        assertTrue(EngineSettings.all.size > 250)
        val curated = SettingsCatalog.all.map { it.key }.toSet()
        assertTrue(EngineSettings.all.none { it.key in curated })
        assertEquals(EngineSettings.all.size, EngineSettings.all.map { it.key }.toSet().size)
        EngineSettings.all.forEach { assertTrue(it.key, it.help.isNotBlank() && it.label.isNotBlank() && it.group.isNotBlank()) }
    }

    @Test fun settingsOrcaShowsThatWereMissingAreThere() {
        listOf("zaa_enabled", "tpms_adaptive", "top_surface_expansion", "wall_maximum_resolution", "gap_fill_flow_ratio", "leading_brim_length",
            "top_color_penetration_layers", "fuzzy_skin_ripples_per_layer").forEach { assertNotNull(it, EngineSettings.get(it)) }
    }

    @Test fun curatedEntriesStayAheadAndEngineEntriesStartAtAdvanced() {
        assertEquals(SettingTier.BASIC, SettingsCatalog.get("wall_loops")!!.tier)
        assertTrue(EngineSettings.all.none { it.tier == SettingTier.BASIC })
        assertTrue(EngineSettings.visible(SettingTier.BASIC).isEmpty())
        assertTrue(EngineSettings.visible(SettingTier.ADVANCED).size < EngineSettings.visible(SettingTier.EXPERT).size)
        assertEquals(listOf("zaa_enabled"), EngineSettings.visible(SettingTier.EXPERT, "zaa_enabled").map { it.key })
    }

    @Test fun engineKeysAreValidatedAndSurviveSanitize() {
        assertEquals("1", SettingsCatalog.validate(SettingsCatalog.get("zaa_enabled")!!, "on"))
        assertNull(SettingsCatalog.validate(SettingsCatalog.get("zaa_enabled")!!, "maybe"))
        val pct = EngineSettings.all.first { it.type is SettingType.FloatOrPercent }
        assertEquals("120%", SettingsCatalog.validate(pct, "120%")); assertEquals("0.5", SettingsCatalog.validate(pct, "0.5")); assertNull(SettingsCatalog.validate(pct, "-1")); assertNull(SettingsCatalog.validate(pct, "abc"))
        val enum = EngineSettings.all.first { it.type is SettingType.Choice }
        val (label, value) = (enum.type as SettingType.Choice).options.first()
        assertEquals(value, SettingsCatalog.validate(enum, label))
        val kept = SettingsCatalog.sanitize(mapOf("zaa_enabled" to "1", "not_a_setting" to "1", "wall_loops" to "3"))
        assertEquals(mapOf("zaa_enabled" to "1", "wall_loops" to "3"), kept)
    }

    @Test fun credentialsAndEngineInternalsAreNeverOffered() {
        listOf("printhost_apikey", "printhost_password", "inherits", "compatible_printers").forEach { assertNull(it, EngineSettings.get(it)) }
    }

    @Test fun filamentAndPrinterSettingsAreListedByScope() {
        val filament = EngineSettings.visible(SettingTier.EXPERT, scope = EngineSettings.FILAMENT); val printer = EngineSettings.visible(SettingTier.EXPERT, scope = EngineSettings.PRINTER)
        assertTrue(filament.size > 80); assertTrue(printer.size > 60)
        assertTrue(filament.all { it.scope == EngineSettings.FILAMENT } && printer.all { it.scope == EngineSettings.PRINTER })
        assertTrue(EngineSettings.get("nozzle_temperature")!!.vector); assertEquals(EngineSettings.FILAMENT, EngineSettings.get("nozzle_temperature")!!.scope)
        assertTrue(EngineSettings.visible(SettingTier.EXPERT, "temperature", EngineSettings.FILAMENT).isNotEmpty())
        // The profile's identity and technology are not per-print overrides; neither are the code, notes and shape fields.
        listOf("printer_technology", "printer_model", "printable_area", "machine_start_gcode", "printer_notes").forEach { assertNull(it, EngineSettings.get(it)) }
    }

    @Test fun oneValueIsRepeatedForEachFilamentOrExtruderOfAListSetting() {
        val sample = mapOf("nozzle_temperature" to "215", "wall_loops" to "3", "zaa_enabled" to "1")
        assertEquals(sample, EngineSettings.expand(sample, 1, 1))
        val four = EngineSettings.expand(sample, 4, 1)
        assertEquals("215,215,215,215", four["nozzle_temperature"]); assertEquals("3", four["wall_loops"]); assertEquals("1", four["zaa_enabled"])
        val printerList = EngineSettings.all.first { it.scope == EngineSettings.PRINTER && it.vector }
        assertEquals("5,5", EngineSettings.expand(mapOf(printerList.key to "5"), 4, 2)[printerList.key])
    }
}
