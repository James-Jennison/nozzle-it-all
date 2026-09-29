package net.jamesjennison.klippercompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// A Bambu print is started with use_ams:false (BambuPrintProtocol), so a bundle using more than one filament is refused
// before upload (BambuPrinterService.startPrint via bambuBundleFilaments).
class BambuMultiFilamentGuardTest {
    private fun sliceInfo(filaments: Int) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config>\n  <plate>\n")
        repeat(filaments) { append("    <filament id=\"${it + 1}\" tray_info_idx=\"\" type=\"PLA\" color=\"#FF0000\" used_m=\"1\" used_g=\"3\" />\n") }
        append("  </plate>\n</config>\n")
    }

    private fun bundle(filaments: Int?): File = File.createTempFile("guard", ".gcode.3mf").apply {
        deleteOnExit()
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Metadata/plate_1.gcode")); zip.write("G28\n".toByteArray()); zip.closeEntry()
            if (filaments != null) { zip.putNextEntry(ZipEntry("Metadata/slice_info.config")); zip.write(sliceInfo(filaments).toByteArray()); zip.closeEntry() }
        }
    }

    @Test fun countsTheFilamentRowsInSliceInfo() {
        assertEquals(0, BambuPrintProtocol.usedFilaments(sliceInfo(0)))
        assertEquals(1, BambuPrintProtocol.usedFilaments(sliceInfo(1)))
        assertEquals(3, BambuPrintProtocol.usedFilaments(sliceInfo(3)))
        // <filaments> or other tags starting with "filament" are not rows.
        assertEquals(0, BambuPrintProtocol.usedFilaments("<filaments></filaments><filament_maps>1</filament_maps>"))
    }

    @Test fun readsTheCountFromARealBundle() {
        assertEquals(1, bambuBundleFilaments(bundle(1)))
        assertEquals(2, bambuBundleFilaments(bundle(2)))
        assertEquals(0, bambuBundleFilaments(bundle(null)))
    }

    @Test fun aPlainGcodeFileIsNotABundle() {
        val gcode = File.createTempFile("guard", ".gcode").apply { deleteOnExit(); writeText("G28\nG1 X10\n") }
        assertEquals(0, bambuBundleFilaments(gcode))
    }

    // An H2C file sliced with the nozzle rack's dynamic map is refused before upload (BambuPrinterService.startPrint):
    // the plate metadata that says so is read from the bundle's slice_info.
    @Test fun readsTheDynamicNozzleMapFlagFromARealBundle() {
        val file = File.createTempFile("guard", ".gcode.3mf").apply {
            deleteOnExit()
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("Metadata/slice_info.config"))
                zip.write(sliceInfo(1).replace("<plate>\n", "<plate>\n    <metadata key=\"enable_filament_dynamic_map\" value=\"true\"/>\n").toByteArray())
                zip.closeEntry()
            }
        }
        assertTrue(BambuPrintProtocol.slicePlate(bambuBundleSliceInfo(file)!!).dynamicNozzleMap)
        assertEquals(false, BambuPrintProtocol.slicePlate(bambuBundleSliceInfo(bundle(1))!!).dynamicNozzleMap)
        assertEquals(null, bambuBundleSliceInfo(bundle(null)))
    }

    @Test fun theRefusalSaysWhatToDoInstead() {
        assertTrue(BambuPrintProtocol.MULTI_MATERIAL_NOT_SUPPORTED.contains("more than one filament"))
        assertTrue(BambuPrintProtocol.MULTI_MATERIAL_NOT_SUPPORTED.contains("Bambu Studio"))
    }
}
