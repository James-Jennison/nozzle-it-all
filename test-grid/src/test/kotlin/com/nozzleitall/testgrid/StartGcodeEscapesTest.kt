package com.nozzleitall.testgrid

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A profile's G-code is read as the engine loads it: Snapmaker Orca's U1 machines keep machine_start_gcode with C-style
 * escapes, and the simulated slice must still carry PRINT_START as a command of its own.
 */
class StartGcodeEscapesTest {
    @Test fun u1LibraryMachineCarriesPrintStartAsItsOwnCommand() {
        val machine = File(Support.root, "engine/profiles/library/snapmaker_u1/machine/snapmaker_u1_0_4_nozzle.json")
        assertTrue("the library keeps the escaped text", machine.readText().contains("\\\\n"))
        val slicer = SimulatedSlicer({ _, f -> if (f == "machine.json") machine.readBytes() else null }, Support.tmp())
        val profile = slicer.profile("snapmaker_u1_0_4_nozzle")
        val parts = Support.env(Support.tmp(), Support.Clock()).models.materialize("nozzle-acceptance-v1", Support.tmp())
        val gcode = (slicer.slice(SliceRequest("nozzle-acceptance-v1", parts, profile, "u1")) as SliceResult.Success).gcode
        val lines = gcode.readLines()
        assertTrue("the slice wrote G-code", lines.size > 10)
        val sum = GcodeScan.scan(gcode)
        assertTrue("${sum.macros}", "PRINT_START" in sum.macros && "PRINT_END" in sum.macros)
        assertTrue("PRINT_START is its own command", lines.any { it.substringBefore(';').trim() == "PRINT_START" })
        assertTrue("no line carries an escaped line break", lines.none { "\\n" in it })
    }
}
