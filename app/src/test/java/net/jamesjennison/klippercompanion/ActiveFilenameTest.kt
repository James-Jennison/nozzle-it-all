package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class ActiveFilenameTest {
    @Test fun completedJobRetainsRawFilenameButClearsActiveDisplayThroughNextPrint() {
        val printing=PrinterSnapshot(true,"printing","folder/old.gcode",.9f)
        assertEquals("folder/old.gcode",printing.activeFilename)
        assertEquals("folder/old.gcode",printing.copy(state="paused").activeFilename)
        val complete=printing.copy(state="complete",progress=1f)
        assertEquals("",complete.activeFilename)
        assertEquals("folder/old.gcode",complete.filename)
        assertEquals("",complete.copy(state="standby").activeFilename)
        assertEquals("next.gcode",complete.copy(state="printing",filename="next.gcode").activeFilename)
    }
    @Test fun progressIsOnlyDisplayedForActiveFiles() {
        val printing=PrinterSnapshot(true,"printing","active.gcode",.75f)
        assertEquals(.75f,printing.activeProgress)
        assertEquals(.75f,printing.copy(state="paused").activeProgress)
        for(state in listOf("complete","standby","cancelled","error")) assertEquals(0f,printing.copy(state=state,progress=1f).activeProgress)
        assertEquals(0f,printing.copy(filename="").activeProgress)
        assertEquals(0f,printing.copy(ready=false).activeProgress)
        assertEquals(0f,printing.copy(progress=Float.NaN).activeProgress)
        assertEquals(1f,printing.copy(progress=2f).activeProgress)
        assertEquals(1f,printing.copy(state="complete",progress=1f).progress)
    }
    @Test fun completedJobDisplaysStandbyWithoutDiscardingResultOrFaults() {
        for(state in listOf("complete","cancelled")) {
            val snapshot=PrinterSnapshot(true,state,"old.gcode",1f)
            assertEquals("standby",snapshot.displayState);assertEquals(state,snapshot.state)
        }
        for(state in listOf("printing","paused","error","standby","shutdown")) assertEquals(state,PrinterSnapshot(true,state).displayState)
        assertEquals("complete",PrinterSnapshot(false,"complete").displayState)
    }
    @Test fun onlyReadyPrintingAndPausedJobsHaveActiveFilenames() {
        for(state in listOf("complete","cancelled","error","standby","unknown","shutdown","")) {
            assertEquals(state,"",PrinterSnapshot(true,state,"retained.gcode").activeFilename)
        }
        for(state in listOf("printing","paused")) {
            assertEquals("",PrinterSnapshot(false,state,"stale.gcode").activeFilename)
            assertEquals("",PrinterSnapshot(true,state,"").activeFilename)
        }
    }
    @Test fun completedSecondaryPrinterDoesNotChangeAnotherPrintersActiveFile() {
        val first="http://first.local/";val second="http://second.local/"
        val state=ScreenState(address=first,connected=true,snapshot=PrinterSnapshot(true,"printing","active.gcode"),savedPrinters=listOf(first,second),
            printerConnections=mapOf(second to PrinterConnection(true,"complete",PrinterSnapshot(true,"complete","finished.gcode"))))
        assertEquals(listOf("active.gcode",""),state.connectedPrinterTiles().map{it.snapshot?.activeFilename})
    }
}
