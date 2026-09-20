package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class PrintAlertsTest {
    private fun connection(connected: Boolean, state: String? = null, filename: String = "") =
        PrinterConnection(connected, state ?: "x", state?.let { PrinterSnapshot(true, it, filename) })

    @Test fun firstObservationNeverAlerts() {
        val current = connection(true, "printing")
        assertTrue(PrintAlerts.detect("a", "Garage", null, current).isEmpty())
    }
    @Test fun printingToCompleteRaisesCompletedWithFilename() {
        val previous = connection(true, "printing", "vase.gcode")
        val current = connection(true, "complete", "vase.gcode")
        val alerts = PrintAlerts.detect("a", "Garage", previous, current)
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.COMPLETED, alerts[0].kind)
        assertEquals("vase.gcode", alerts[0].filename)
        assertTrue(alerts[0].message.contains("Garage"))
        assertTrue(alerts[0].message.contains("vase.gcode"))
    }
    @Test fun pausedToCompleteAlsoRaisesCompleted() {
        val previous = connection(true, "paused", "vase.gcode")
        val current = connection(true, "complete", "vase.gcode")
        assertEquals(AlertKind.COMPLETED, PrintAlerts.detect("a", "Garage", previous, current).single().kind)
    }
    @Test fun printingToErrorRaisesError() {
        val previous = connection(true, "printing", "vase.gcode")
        val current = connection(true, "error", "vase.gcode")
        assertEquals(AlertKind.ERROR, PrintAlerts.detect("a", "Garage", previous, current).single().kind)
    }
    @Test fun printingToCancelledRaisesCancelled() {
        val previous = connection(true, "printing", "vase.gcode")
        val current = connection(true, "cancelled", "vase.gcode")
        assertEquals(AlertKind.CANCELLED, PrintAlerts.detect("a", "Garage", previous, current).single().kind)
    }
    @Test fun printingToPausedRaisesPausedWithFilename() {
        val previous = connection(true, "printing", "vase.gcode")
        val current = connection(true, "paused", "vase.gcode")
        val alerts = PrintAlerts.detect("a", "Garage", previous, current)
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.PAUSED, alerts[0].kind)
        assertEquals("vase.gcode", alerts[0].filename)
        assertTrue(alerts[0].message.contains("paused"))
    }
    @Test fun stayingPausedDoesNotReAlert() {
        val previous = connection(true, "paused", "vase.gcode")
        val current = connection(true, "paused", "vase.gcode")
        assertTrue(PrintAlerts.detect("a", "Garage", previous, current).isEmpty())
    }
    @Test fun resumingFromPausedDoesNotAlert() {
        // A resume was never alert-worthy before this change and still isn't - only entering
        // paused is new.
        val previous = connection(true, "paused", "vase.gcode")
        val current = connection(true, "printing", "vase.gcode")
        assertTrue(PrintAlerts.detect("a", "Garage", previous, current).isEmpty())
    }
    @Test fun standbyToPrintingDoesNotAlert() {
        val previous = connection(true, "standby")
        val current = connection(true, "printing", "vase.gcode")
        assertTrue(PrintAlerts.detect("a", "Garage", previous, current).isEmpty())
    }
    @Test fun repeatedSameStateDoesNotReAlert() {
        val previous = connection(true, "complete", "vase.gcode")
        val current = connection(true, "complete", "vase.gcode")
        assertTrue(PrintAlerts.detect("a", "Garage", previous, current).isEmpty())
    }
    @Test fun connectedToDisconnectedRaisesOffline() {
        val previous = connection(true, "printing", "vase.gcode")
        val current = PrinterConnection(false, "Unavailable", null)
        val alerts = PrintAlerts.detect("a", "Garage", previous, current)
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.OFFLINE, alerts[0].kind)
    }
    @Test fun disconnectedToConnectedRaisesBackOnline() {
        val previous = PrinterConnection(false, "Unavailable", null)
        val current = connection(true, "printing", "vase.gcode")
        val alerts = PrintAlerts.detect("a", "Garage", previous, current)
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.BACK_ONLINE, alerts[0].kind)
    }
    @Test fun reconnectingIntoAnAlreadyCompleteStateOnlyRaisesBackOnline() {
        // We don't know *when* it actually finished while we were offline, so don't
        // claim COMPLETED — only that the connection itself came back.
        val previous = PrinterConnection(false, "Unavailable", null)
        val current = connection(true, "complete", "vase.gcode")
        val alerts = PrintAlerts.detect("a", "Garage", previous, current)
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.BACK_ONLINE, alerts[0].kind)
    }
    @Test fun disconnectedStaysDisconnectedDoesNotReAlert() {
        val previous = PrinterConnection(false, "Unavailable", null)
        val current = PrinterConnection(false, "Unavailable", null)
        assertTrue(PrintAlerts.detect("a", "Garage", previous, current).isEmpty())
    }
}
