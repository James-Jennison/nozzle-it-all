package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class SlicedFileFilamentsTest {
    @Test fun readsTypesAndColoursFromTheSettingsBlock() {
        val gcode = """
            ; HEADER_BLOCK_START
            T0
            G1 X10
            T1
            ; default_filament_colour = ;
            ; filament_colour = #FF0000;#00ff00;"#0000FF"
            ; filament_type = PLA;PETG;
        """.trimIndent().lineSequence()
        assertEquals(listOf(
            SlicedFileFilaments.Filament(0, "PLA", "#FF0000"),
            SlicedFileFilaments.Filament(1, "PETG", "#00FF00"),
            SlicedFileFilaments.Filament(2, null, "#0000FF")), SlicedFileFilaments.read(gcode))
    }

    @Test fun aFileWithoutTheBlockDeclaresNothing() {
        assertTrue(SlicedFileFilaments.read(sequenceOf("G28", "T0", "G1 X1")).isEmpty())
    }
}
