package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

// The generated catalog (scripts/bundle_vendor_profiles.py) must stay in step with the enum, the asset packs and
// the picker. Runs on the JVM against the real asset files.
class SlicingModelCatalogTest {
    private val assets: File = listOf("src/main/assets/slicer_profiles", "app/src/main/assets/slicer_profiles").map(::File).first { it.isDirectory }

    @Test fun everyEnumValueHasExactlyOneCatalogEntry() {
        val catalogued = SlicingModelCatalog.all.map { it.model }
        assertEquals("duplicate catalog entries", catalogued.size, catalogued.toSet().size)
        assertEquals(SlicingPrinterModel.values().toSet(), catalogued.toSet())
    }

    @Test fun modelsPersistedByEarlierVersionsAreStillThere() {
        // The enum is stored by name(); losing or renaming one would silently reset a saved printer's slicing model.
        listOf("SNAPMAKER_U1", "ELEGOO_CENTAURI_CARBON", "BAMBU_GENERIC", "PRUSA_GENERIC", "GENERIC_KLIPPER", "PRUSA_XL_5T")
            .forEach { assertNotNull(it, runCatching { SlicingPrinterModel.valueOf(it) }.getOrNull()) }
    }

    @Test fun everyPackHasThreeParsableProfilesWithTheKeysTheEngineNeeds() {
        SlicingModelCatalog.all.forEach { info ->
            val dir = File(assets, info.assetDir)
            assertTrue("${info.label}: missing pack ${info.assetDir}", dir.isDirectory)
            val machine = JSONObject(File(dir, "machine.json").readText())
            assertTrue("${info.label}: machine has a bed the app can parse", parseBedShape(machine.toString()).points.size >= 3)
            assertTrue("${info.label}: machine has a start gcode", machine.optString("machine_start_gcode").isNotBlank())
            assertFalse("${info.label}: machine must be flattened (no inherits)", machine.has("inherits"))
            val process = JSONObject(File(dir, "process.json").readText()); assertFalse(info.label, process.has("inherits"))
            val filament = JSONObject(File(dir, "filament.json").readText()); assertFalse(info.label, filament.has("inherits"))
            assertTrue("${info.label}: filament has a type", filament.optJSONArray("filament_type")?.optString(0).orEmpty().isNotBlank())
        }
    }

    @Test fun onlyHardwareConfirmedModelsAreMarkedVerified() {
        assertEquals(
            setOf(SlicingPrinterModel.SNAPMAKER_U1, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, SlicingPrinterModel.GENERIC_KLIPPER),
            SlicingModelCatalog.all.filter { it.verifiedOnHardware }.map { it.model }.toSet(),
        )
    }

    @Test fun bambuAndPrusaLibrariesAreBundled() {
        val labels = SlicingModelCatalog.all.map { it.label }
        listOf("Bambu Lab P1S", "Bambu Lab X1 Carbon", "Bambu Lab A1 mini", "Bambu Lab P2S", "Bambu Lab H2D", "Bambu Lab H2S", "Prusa MK4S", "Prusa CORE One", "Prusa MINI / MINI+", "Prusa XL (single tool)")
            .forEach { assertTrue("$it is bundled", it in labels) }
        assertTrue(SlicingModelCatalog.all.count { it.vendor == SlicingVendor.BAMBU } >= 14)
        assertTrue(SlicingModelCatalog.all.count { it.vendor == SlicingVendor.PRUSA } >= 10)
    }

    @Test fun theWholeVendorLibraryIsBundled() {
        assertTrue("expected 350+ models, got ${SlicingModelCatalog.all.size}", SlicingModelCatalog.all.size >= 350)
        assertTrue(SlicingVendor.values().size >= 55)
        fun n(v: SlicingVendor) = SlicingModelCatalog.all.count { it.vendor == v }
        assertTrue("Creality ${n(SlicingVendor.CREALITY)}", n(SlicingVendor.CREALITY) >= 40)
        assertTrue(n(SlicingVendor.ANYCUBIC) >= 15); assertTrue(n(SlicingVendor.QIDI) >= 10)
        assertTrue(n(SlicingVendor.SOVOL) >= 10); assertTrue(n(SlicingVendor.VORON) >= 5); assertTrue(n(SlicingVendor.ARTILLERY) >= 8)
        assertTrue("every vendor has at least one model", SlicingVendor.values().all { v -> n(v) > 0 })
    }

    @Test fun labelsAreUniqueWithinAVendorSoThePickerIsUnambiguous() {
        SlicingModelCatalog.all.groupBy { it.vendor to it.label }.filterValues { it.size > 1 }.let { assertTrue("duplicate labels: ${it.keys}", it.isEmpty()) }
    }

    @Test fun groupingKeepsVendorOrderAndHidesEmptyVendors() {
        val all = groupedSlicingModels("")
        assertEquals(SlicingModelCatalog.all.size, all.sumOf { it.second.size })
        assertEquals("Snapmaker", all.first().first.label)
        val prusa = groupedSlicingModels("mk4s")
        assertTrue(prusa.all { (_, rows) -> rows.isNotEmpty() }); assertTrue(prusa.any { it.first == SlicingVendor.PRUSA })
        assertTrue(groupedSlicingModels("zzzz-none").isEmpty())
    }

    @Test fun everyBambuModelIsAFilamentSwapMachine() {
        SlicingModelCatalog.all.filter { it.vendor == SlicingVendor.BAMBU }.forEach {
            assertEquals(it.label, MultiToolFamily.FILAMENT_SWAP, multiToolFamily(it.model, 4))
        }
        assertEquals(MultiToolFamily.SINGLE, multiToolFamily(SlicingPrinterModel.BAMBU_P1S, 1))
    }

    @Test fun searchMatchesLabelsAndVendorsAndTagsAreUnique() {
        assertEquals(listOf(SlicingPrinterModel.BAMBU_P1S), matchingSlicingModels("p1s").map { it.model })
        assertTrue(matchingSlicingModels("PRUSA").all { it.vendor == SlicingVendor.PRUSA })
        assertEquals(SlicingModelCatalog.all.size, matchingSlicingModels("  ").size)
        assertTrue(matchingSlicingModels("no such printer").isEmpty())
        val tags = SlicingPrinterModel.values().map(::slicingModelTag)
        assertEquals("picker tags must be unique", tags.size, tags.toSet().size)
        assertEquals("slicing-model-prusa-xl", slicingModelTag(SlicingPrinterModel.PRUSA_XL_5T))
        assertEquals("slicing-model-centauri-carbon", slicingModelTag(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON))
    }

    @Test fun everyNonCosmosModelResolvesToItsOwnPack() {
        SlicingPrinterModel.values().filter { it != SlicingPrinterModel.ELEGOO_CENTAURI_CARBON }.forEach {
            assertEquals("slicer_profiles/${SlicingModelCatalog.info(it).assetDir}", slicingProfilePack(it, null)!!.assetDir)
        }
        assertNull("COSMOS still needs a live firmware generation", slicingProfilePack(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, null))
    }

    // Found by the first real-device slicing run of the full library (2026-09-26): OrcaSlicer's own validation rejected 16
    // packs (Bambu models and three Anycubic) that lacked a real "G92 E0" line and one Creality pack whose default plate its
    // filament does not support. The generator now repairs both; these keep every pack honest.
    private fun text(v: Any?): String = if (v is org.json.JSONArray) (0 until v.length()).joinToString("\n") { v.getString(it) } else v?.toString().orEmpty()

    @Test fun marlinPacksWithRelativeExtrusionResetTheExtruderEachLayer() {
        val g92 = Regex("""^[ \t]*G92[ \t]*E(0(\.0*)?|\.0+)[ \t]*(;.*)?$""", RegexOption.MULTILINE) // OrcaSlicer's own regex_g92e0_correct
        SlicingModelCatalog.all.forEach { info ->
            val machine = JSONObject(File(File(assets, info.assetDir), "machine.json").readText())
            val marlin = machine.optString("gcode_flavor") in setOf("marlin", "marlin2")
            if (marlin && machine.optString("use_relative_e_distances", "1") != "0") {
                assertTrue("${info.label}: needs a real G92 E0 line at layer change", g92.containsMatchIn(text(machine.opt("before_layer_change_gcode"))) || g92.containsMatchIn(text(machine.opt("layer_change_gcode"))))
            }
        }
    }

    @Test fun multiBedTypeMachinesSelectAPlateTheirFilamentSupports() {
        val plateKeys = mapOf("Cool Plate" to "cool_plate_temp", "Engineering Plate" to "eng_plate_temp", "High Temp Plate" to "hot_plate_temp",
            "Textured PEI Plate" to "textured_plate_temp", "Textured Cool Plate" to "textured_cool_plate_temp", "Supertack Plate" to "supertack_plate_temp")
        SlicingModelCatalog.all.forEach { info ->
            val dir = File(assets, info.assetDir)
            val machine = JSONObject(File(dir, "machine.json").readText())
            if (machine.optString("support_multi_bed_types") != "1") return@forEach
            val process = JSONObject(File(dir, "process.json").readText()); val filament = JSONObject(File(dir, "filament.json").readText())
            val plate = process.optString("curr_bed_type").ifBlank { machine.optString("curr_bed_type").ifBlank { "Cool Plate" } }
            val key = plateKeys.getValue(plate) // vendors write a temperature as an array or as a plain string
            val temp = (filament.optJSONArray(key)?.optString(0) ?: filament.optString(key)).substringBefore(',').toDoubleOrNull() ?: 0.0
            assertTrue("${info.label}: filament does not support the selected plate $plate", temp > 0)
        }
    }
}
