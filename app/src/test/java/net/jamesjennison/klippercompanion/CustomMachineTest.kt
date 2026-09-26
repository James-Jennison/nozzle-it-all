package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CustomMachineTest {
    private val genericKlipper: String = listOf("src/main/assets/slicer_profiles/generic_klipper/machine.json", "app/src/main/assets/slicer_profiles/generic_klipper/machine.json")
        .map(::File).first { it.exists() }.readText()
    private val voron = CustomMachine(300.0, 300.0, 280.0, startGcode = "PRINT_START BED=[bed_temperature_initial_layer_single]", endGcode = "PRINT_END")

    @Test fun validationCatchesEveryBadInputInPlainLanguage() {
        assertNull(voron.problem())
        assertNotNull(voron.copy(bedWidthMm = 10.0).problem()); assertNotNull(voron.copy(bedDepthMm = 5000.0).problem())
        assertNotNull(voron.copy(maxHeightMm = 5.0).problem()); assertNotNull(voron.copy(bedWidthMm = Double.NaN).problem())
        assertNotNull(voron.copy(maxHeightMm = Double.POSITIVE_INFINITY).problem())
        assertNotNull(voron.copy(startGcode = "G28\u0000").problem()); assertNotNull(voron.copy(endGcode = "x".repeat(CustomMachine.MAX_GCODE_CHARS + 1)).problem())
        assertNull(voron.copy(bedWidthMm = CustomMachine.MIN_BED_MM, maxHeightMm = CustomMachine.MAX_HEIGHT_MM).problem())
    }

    @Test fun cornerOriginMakesARectangleFromZero() {
        assertEquals(listOf("0x0", "300x0", "300x300", "0x300"), customPrintableArea(voron))
        assertEquals(listOf("0x0", "235.5x0", "235.5x220", "0x220"), customPrintableArea(CustomMachine(235.5, 220.0, 250.0)))
    }

    @Test fun centreOriginUsesNegativeCoordinates() {
        assertEquals(listOf("-150x-150", "150x-150", "150x150", "-150x150"), customPrintableArea(voron.copy(originAtCenter = true)))
    }

    @Test fun applyingItChangesTheBedAndHeightTheAppReads() {
        val shape = parseBedShape(applyCustomMachine(genericKlipper, voron.copy(bedWidthMm = 180.0, bedDepthMm = 200.0, maxHeightMm = 160.0)))
        assertEquals(listOf(0f to 0f, 180f to 0f, 180f to 200f, 0f to 200f), shape.points); assertEquals(160f, shape.heightMm, 0f)
        val centred = parseBedShape(applyCustomMachine(genericKlipper, voron.copy(originAtCenter = true)))
        assertEquals(-150f to -150f, centred.points.first()); assertEquals(150f to 150f, centred.points[2])
    }

    @Test fun blankGcodeKeepsTheProfilesOwnAndGivenGcodeReplacesIt() {
        val original = JSONObject(genericKlipper)
        val kept = JSONObject(applyCustomMachine(genericKlipper, voron.copy(startGcode = "", endGcode = "")))
        assertEquals(original.getString("machine_start_gcode"), kept.getString("machine_start_gcode")); assertEquals(original.optString("machine_end_gcode"), kept.optString("machine_end_gcode"))
        val changed = JSONObject(applyCustomMachine(genericKlipper, voron.copy(startGcode = "A\r\nB\rC", endGcode = "PRINT_END")))
        assertEquals("A\nB\nC", changed.getString("machine_start_gcode")); assertEquals("PRINT_END", changed.getString("machine_end_gcode"))
    }

    @Test fun everythingElseInTheProfileIsUntouchedAndTheNozzleStays() {
        val original = JSONObject(genericKlipper); val patched = JSONObject(applyCustomMachine(genericKlipper, voron))
        val changed = setOf("printable_area", "printable_height", "machine_start_gcode", "machine_end_gcode")
        original.keys().asSequence().filter { it !in changed }.forEach { assertEquals(it, original.get(it).toString(), patched.get(it).toString()) }
        assertEquals(original.get("nozzle_diameter").toString(), patched.get("nozzle_diameter").toString())
    }

    @Test fun exclusionZonesFromTheProfilesOwnBedAreDropped() {
        val withZone = JSONObject(genericKlipper).put("bed_exclude_area", JSONArray().put("0x0").put("10x0").put("10x10")).toString()
        assertEquals("[\"0x0\"]", JSONObject(applyCustomMachine(withZone, voron)).getJSONArray("bed_exclude_area").toString())
    }

    @Test fun anInvalidMachineIsRefusedWhenApplied() { assertThrows(IllegalArgumentException::class.java) { applyCustomMachine(genericKlipper, voron.copy(bedWidthMm = 1.0)) } }

    @Test fun savedValuesRoundTripAndDamagedOnesFallBackToNothing() {
        assertEquals(voron, CustomMachine.fromJson(voron.toJson()))
        assertNull(CustomMachine.fromJson(null)); assertNull(CustomMachine.fromJson(JSONObject()))
        assertNull("a saved bed that is out of range must not be used", CustomMachine.fromJson(voron.toJson().put("w", 9999)))
    }

    @Test fun editingStartsFromTheBundledProfilesOwnValues() {
        val d = defaultCustomMachineFrom(genericKlipper)
        assertEquals(250.0, d.bedWidthMm, 0.0); assertEquals(250.0, d.bedDepthMm, 0.0); assertEquals(250.0, d.maxHeightMm, 0.0)
        assertFalse(d.originAtCenter); assertTrue(d.startGcode.contains("START_PRINT"))
        assertEquals("a profile with a centred bed reads back as centred", true, defaultCustomMachineFrom(applyCustomMachine(genericKlipper, voron.copy(originAtCenter = true))).originAtCenter)
    }

    @Test fun theCosmosPackIsNeverOverridden() {
        assertNull(slicingProfilePack(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, null, voron))
        assertNull(slicingProfilePack(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, CosmosProfileGeneration.CURRENT, voron)!!.custom)
        assertEquals(voron, slicingProfilePack(SlicingPrinterModel.GENERIC_KLIPPER, null, voron)!!.custom)
        assertNull(slicingProfilePack(SlicingPrinterModel.GENERIC_KLIPPER, null)!!.custom)
    }

    @Test fun aBackupCarriesTheCustomMachine() {
        val list = listOf(PrinterProfile("http://voron.local/", "Voron", slicingModel = SlicingPrinterModel.GENERIC_KLIPPER, customMachine = voron), PrinterProfile("http://plain.local/", "Plain"))
        val restored = SettingsBackup.decode(SettingsBackup.encode(list, "correct horse".toCharArray()), "correct horse".toCharArray())
        assertEquals(list, restored)
    }
}
