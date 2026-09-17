package net.jamesjennison.klippercompanion

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SavedPrinterMonitorTest {
    @Test fun throwingCloseDoesNotPreventBatchCleanupOrKeepPolling() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var reads = 0
        var closes = 0
        var status = emptyMap<String, PrinterConnection>()
        val monitor = SavedPrinterMonitor(this, dispatcher, { address -> object : PrinterService {
            override val address = address
            override fun snapshot(): PrinterSnapshot { reads++; return PrinterSnapshot(true, "standby") }
            override fun catalog() = error("No catalog access")
            override fun image(camera: Camera) = error("No image access")
            override fun command(command: PrinterCommand) = error("No commands")
            override fun close() { closes++; if(address == "http://first.local/") throw IllegalStateException("Fixture close failure") }
        } }, { status = it })
        monitor.reconcile(linkedSetOf("http://first.local/", "http://second.local/")); runCurrent()
        assertEquals(2, reads)
        monitor.stop(); assertTrue(status.isEmpty()); assertEquals(2, closes)
        advanceTimeBy(10_000); runCurrent(); assertEquals(2, reads)
        monitor.stop(); assertEquals(2, closes)
    }
    @Test fun removedSessionCannotPublishLateSnapshotAndClosesOnlyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var closes = 0
        var status = emptyMap<String, PrinterConnection>()
        lateinit var monitor: SavedPrinterMonitor
        val service = object : PrinterService {
            override val address = "http://fixture.local/"
            override fun snapshot(): PrinterSnapshot {
                monitor.stop()
                return PrinterSnapshot(true, "standby")
            }
            override fun catalog() = error("Monitor must only read snapshot")
            override fun image(camera: Camera) = error("No camera polling")
            override fun command(command: PrinterCommand) = error("No command dispatch")
            override fun close() { closes++ }
        }
        monitor = SavedPrinterMonitor(this, dispatcher, { service }, { status = it })
        monitor.reconcile(setOf(service.address)); runCurrent()
        assertTrue(status.isEmpty()); assertEquals(1, closes)
        monitor.stop(); assertEquals(1, closes)
    }
}
