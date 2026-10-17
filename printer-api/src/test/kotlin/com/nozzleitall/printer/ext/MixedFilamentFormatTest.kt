package com.nozzleitall.printer.ext

import com.nozzleitall.printer.ext.MixedFilamentFormat.Gradient
import com.nozzleitall.printer.ext.MixedFilamentFormat.Row
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MixedFilamentFormatTest {
    @Test fun aRowRoundTripsThroughTheEnginesJson() {
        val row = Row(listOf(1, 3), listOf(7, 3), Gradient(true, "0.9,0.1", "", false))
        val back = MixedFilamentFormat.parseRow(row.toJson().put("filament", 5).put("color", "#FF5626"))
        assertEquals(row.components, back.components); assertEquals(row.weights, back.weights)
        assertEquals(row.gradient, back.gradient); assertEquals(5, back.filament); assertEquals("#FF5626", back.hex)
        assertEquals("F1 70% + F3 30%", back.label)
    }

    @Test fun requestsCarryThePhysicalFilamentsAndTheRows() {
        val o = MixedFilamentFormat.request("display", listOf("#FF0000" to "PLA", "#0000FF" to null), listOf(Row(listOf(1, 2), listOf(1, 1))))
        assertEquals("display", o.getString("op"))
        assertEquals("PLA", o.getJSONArray("physical").getJSONObject(1).getString("type"))
        assertEquals(1, o.getJSONArray("mixed").length())
    }

    @Test fun theSettingsAProjectStoresGiveTheRowsBack() {
        // What the engine's slice_overrides op returns for one 3:1 row after four loaded filaments.
        val settings = mapOf("filament_is_mixed" to "0,0,0,0,1", "filament_mixed_components" to ";;;;1,3", "filament_mixed_sublayer_ratios" to ";;;;0.7500,0.2500",
            "filament_mixed_gradient" to "0,0,0,0,0", "filament_mixed_gradient_range" to ";;;;", "filament_mixed_gradient_curve" to ";;;;", "filament_mixed_gradient_per_part" to "0,0,0,0,0")
        val row = MixedFilamentFormat.rowsFromSettings(settings, 4).single()
        assertEquals(listOf(1, 3), row.components); assertEquals(listOf(75, 25), row.weights); assertEquals(5, row.filament)
        assertTrue(MixedFilamentFormat.rowsFromSettings(emptyMap(), 4).isEmpty())
    }

    @Test fun overridesAreParsedAsPlainStrings() {
        val o = JSONObject("""{"overrides":{"filament_colour":"#FF0000;#0000FF;#6F006E","enable_mixed_color_sublayer":"1"},"slots":3}""")
        assertEquals("1", MixedFilamentFormat.parseOverrides(o)["enable_mixed_color_sublayer"])
    }

    @Test fun mixedFilamentsAreOfferedOnMultiSlotPrintersOnly() {
        assertEquals(setOf(MixedFilamentFormat.FEATURE), ProfileFeatures.of("klipper", 2))
        assertTrue(ProfileFeatures.of("klipper", 1).isEmpty())
        assertTrue(ProfileFeatures.of("klipper", 4, "elegoo_centauri_carbon_canvas").isEmpty())
    }
}
