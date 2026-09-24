package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class ExtendedAlertsTest {
    private fun conn(state: String, progress: Float = 0f, file: String = "cube.gcode") = PrinterConnection(true, state, PrinterSnapshot(true, state, file, progress))
    private fun kinds(prev: PrinterConnection?, cur: PrinterConnection, extended: Boolean) = PrintAlerts.detect("a", "U1", prev, cur, extended).map { it.kind }

    @Test fun aStartedPrintIsOnlyAlertedWhenExtendedAlertsAreOn() {
        assertEquals(listOf(AlertKind.STARTED), kinds(conn("standby"), conn("printing"), true))
        assertTrue(kinds(conn("standby"), conn("printing"), false).isEmpty())
    }
    @Test fun resumingFromPauseIsNotANewStart() = assertTrue(kinds(conn("paused"), conn("printing"), true).isEmpty())
    @Test fun crossingNinetyPercentAlertsOnceNotOnEveryPoll() {
        assertEquals(listOf(AlertKind.NEARLY_DONE), kinds(conn("printing", 0.88f), conn("printing", 0.91f), true))
        assertTrue(kinds(conn("printing", 0.91f), conn("printing", 0.93f), true).isEmpty())
    }
    @Test fun nothingExtraFiresOnTheFirstObservation() = assertTrue(kinds(null, conn("printing", 0.95f), true).isEmpty())
    @Test fun theStandardAlertsAreUnchangedByTheExtendedFlag() = assertEquals(listOf(AlertKind.COMPLETED), kinds(conn("printing", 1f), conn("complete", 1f), true))
}
