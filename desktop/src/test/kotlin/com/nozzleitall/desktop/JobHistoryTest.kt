package com.nozzleitall.desktop

import com.nozzleitall.desktop.history.*
import com.nozzleitall.printer.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JobHistoryTest {
    private val route = ConnectionRoute.LAN
    private fun printing(file: String, fraction: Float, elapsed: Double = 600.0, layers: Int = 100) =
        PrinterStatus(PrinterState.PRINTING, route, job = JobProgress(file, fraction, elapsed, 10, layers))
    private fun idle(state: PrinterState) = PrinterStatus(state, route)

    @Test fun aJobThatEndsIsRecordedOnceWithHowItEnded() {
        var now = 1_000_000L
        val t = JobTracker { now }
        assertNull(t.observe("p", "CC1", printing("a.gcode", 0.2f)))
        now += 60_000; assertNull(t.observe("p", "CC1", printing("a.gcode", 0.9f, elapsed = 3600.0)))
        now += 60_000
        val r = t.observe("p", "CC1", idle(PrinterState.FINISHED))!!
        assertEquals("finished", r.outcome); assertEquals("a.gcode", r.fileName); assertEquals("CC1", r.printerName)
        assertEquals(3600.0, r.elapsedSeconds!!, 0.0); assertEquals(100, r.totalLayers)
        assertNull("the same ending is not recorded twice", t.observe("p", "CC1", idle(PrinterState.FINISHED)))
    }

    @Test fun stoppedAndFailedAreToldApart() {
        val t = JobTracker()
        t.observe("p", "P", printing("a", 0.3f)); assertEquals("cancelled", t.observe("p", "P", idle(PrinterState.CANCELLED))!!.outcome)
        t.observe("p", "P", printing("b", 0.3f)); assertEquals("failed", t.observe("p", "P", idle(PrinterState.ERROR))!!.outcome)
    }

    @Test fun backToReadyIsFinishedOnlyIfItGotToTheEnd() {
        val t = JobTracker()
        t.observe("p", "P", printing("a", 0.995f)); assertEquals("finished", t.observe("p", "P", idle(PrinterState.READY))!!.outcome)
        t.observe("p", "P", printing("b", 0.4f)); assertEquals("cancelled", t.observe("p", "P", idle(PrinterState.READY))!!.outcome)
    }

    @Test fun losingTheConnectionMidPrintEndsNothing() {
        val t = JobTracker()
        t.observe("p", "P", printing("a", 0.5f))
        assertNull(t.observe("p", "P", idle(PrinterState.OFFLINE)))
        assertNull(t.observe("p", "P", idle(PrinterState.UNKNOWN)))
        assertEquals("finished", t.observe("p", "P", idle(PrinterState.FINISHED))!!.outcome)
    }

    @Test fun aPrinterAlreadyFinishedWhenTheAppStartsIsNotANewResult() {
        val t = JobTracker()
        assertNull(t.observe("p", "P", idle(PrinterState.FINISHED)))
        assertNull(t.observe("p", "P", idle(PrinterState.READY)))
    }

    @Test fun twoPrintersAreTrackedSeparately() {
        val t = JobTracker()
        t.observe("a", "A", printing("x", 0.5f)); t.observe("b", "B", printing("y", 0.5f))
        assertEquals("y", t.observe("b", "B", idle(PrinterState.FINISHED))!!.fileName)
        assertEquals("x", t.observe("a", "A", idle(PrinterState.CANCELLED))!!.fileName)
    }

    @Test fun historySurvivesARestartNewestFirstAndIsCapped() {
        val f = File(Files.createTempDirectory("hist").toFile(), "history.json")
        val store = JobHistoryStore(f, limit = 3)
        assertTrue(store.load().isEmpty())
        val recs = (1..5).map { JobRecord("$it", "p", "P", "f$it.gcode", 0, it.toLong(), "finished", 60.0, 10) }
        store.save(recs)
        assertEquals(listOf("1", "2", "3"), store.load().map { it.id })
        assertEquals(60.0, store.load().first().elapsedSeconds!!, 0.0)
    }

    @Test fun theNotificationSaysWhatHappenedWhereAndHowLong() {
        val r = JobRecord("1", "p", "CC1", "dir/Possum.gcode", 0, 1, "failed", 7500.0, null)
        assertEquals("Print failed on CC1", r.notificationTitle()); assertEquals("Possum.gcode · 2 h 5 min", r.notificationBody())
        assertEquals("Print finished on CC1", r.copy(outcome = "finished").notificationTitle())
        assertEquals("Print stopped on CC1", r.copy(outcome = "cancelled").notificationTitle())
    }

    @Test fun theFleetRecordsAnEndedJobSavesItAndNotifiesUnlessTurnedOff() {
        val tmp = Files.createTempDirectory("fleet").toFile()
        val paths = AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure()
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        val sent = mutableListOf<Pair<String, String>>()
        fleet.notifier = { t, b -> sent += t to b }
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p1", "CC1", "Elegoo CC1", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "moonraker"))
        fleet.recordEnd(entry, printing("Possum.gcode", 0.5f))
        fleet.recordEnd(entry, idle(PrinterState.FINISHED))
        assertEquals(1, fleet.history.value.size); assertEquals(listOf("Print finished on CC1" to "Possum.gcode · 10 min"), sent)
        assertEquals("a restart reads it back", 1, JobHistoryStore(File(paths.data, "history.json")).load().size)
        fleet.settings.value = fleet.settings.value.copy(notifications = false)
        fleet.recordEnd(entry, printing("B.gcode", 0.5f)); fleet.recordEnd(entry, idle(PrinterState.CANCELLED))
        assertEquals(2, fleet.history.value.size); assertEquals("history is kept even with notifications off", 1, sent.size)
        fleet.shutdown()
    }
}
