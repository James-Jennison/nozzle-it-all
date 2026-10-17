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
}
