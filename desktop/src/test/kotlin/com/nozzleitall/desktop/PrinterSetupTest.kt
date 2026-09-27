package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Bed type and nozzle flow follow Snapmaker Orca's printer-card rules (PrinterSetup). */
class PrinterSetupTest {
    private fun machine(id: String) = JSONObject(File(ProfileCatalog.materialize(Files.createTempDirectory("ps").toFile(), id), "machine.json").readText())

    @Test fun bedTypesFollowSnapmakerOrca() {
        val u1 = PrinterSetup.beds(machine("snapmaker_u1"))
        assertTrue(u1.enabled); assertEquals("Textured PEI Plate", u1.default)
        assertEquals(7, u1.choices.size) // support_multi_bed_types: the U1's seven-plate list
        assertTrue(u1.choices.any { it.value == "Graphic Effect Plate" })
        val x1c = PrinterSetup.beds(machine("bambu_x1_carbon"))
        assertTrue(x1c.enabled); assertEquals("Cool Plate", x1c.default)
        val klipper = PrinterSetup.beds(machine("generic_klipper"))
        assertFalse(klipper.enabled); assertEquals("High Temp Plate", klipper.default)
        // A U1 without multi-bed support gets its three plates, and a non-U1 default falls back to Textured PEI.
        val u1Only3 = PrinterSetup.beds(JSONObject().put("printer_model", "Snapmaker U1").put("support_multi_bed_types", "0"))
        assertEquals(listOf("Textured PEI Plate", "High Temp Plate", "Graphic Effect Plate"), u1Only3.choices.map { it.value })
    }

    @Test fun highFlowIsOfferedOnlyWherePrintersDeclareIt() {
        assertTrue(PrinterSetup.supportsHighFlow(JSONObject().put("printer_flow_support", org.json.JSONArray(listOf("standard", "high_flow")))))
        assertFalse(PrinterSetup.supportsHighFlow(machine("generic_klipper")))
        assertEquals(4, PrinterSetup.nozzles(machine("snapmaker_u1")).size)
    }
}
