package com.nozzleitall.desktop

import com.nozzleitall.desktop.screens.JobBarModel
import com.nozzleitall.printer.*
import org.junit.Assert.*
import org.junit.Test

class JobBarTest {
    private fun status(state: PrinterState, job: JobProgress? = null) = PrinterStatus(state, ConnectionRoute.LAN, job = job)
    private val job = JobProgress("a.gcode", 0.5f, 3600.0)

    @Test fun onlyPrintingAndPausedPrintersWithAJobAreShown() {
        val items = JobBarModel.items(listOf(
            Triple("1", "U1", status(PrinterState.PRINTING, job)), Triple("2", "CC1", status(PrinterState.READY)),
            Triple("3", "Old", status(PrinterState.PAUSED, job)), Triple("4", "Off", status(PrinterState.OFFLINE, job)), Triple("5", "Odd", status(PrinterState.PRINTING))))
        assertEquals(listOf("1", "3"), items.map { it.printerId })
        assertFalse(items[0].paused); assertTrue(items[1].paused)
    }

    @Test fun theTimeLeftFollowsTheProgress() = assertEquals(3600.0, JobBarModel.items(listOf(Triple("1", "U1", status(PrinterState.PRINTING, job)))).single().remainingSeconds!!, 0.5)
}
