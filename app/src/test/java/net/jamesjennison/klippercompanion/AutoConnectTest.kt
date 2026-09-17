package net.jamesjennison.klippercompanion

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AutoConnectTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val reads = mutableMapOf<String, Int>()
    private val unavailable = mutableSetOf<String>()
    private var sends = 0
    private val first = "http://first.local/"
    private val second = "http://second.local/"
    private val third = "http://third.local/"
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun model(selected: String = first, known: List<String> = listOf(first, second)): PrinterModel {
        val model = PrinterModel(initialAddress=selected, initialPrinters=known, io=dispatcher,
            clock={100_000}, serviceFactory={ address -> object : PrinterService {
                override val address = address
                override fun snapshot(): PrinterSnapshot {
                    reads[address] = (reads[address] ?: 0) + 1
                    if(address in unavailable) throw ApiFailure("Unavailable fixture")
                    return PrinterSnapshot(true, "standby")
                }
                override fun catalog() = Catalog(emptyList(), emptyList(), emptyList(), emptyList())
                override fun image(camera: Camera) = byteArrayOf()
                override fun command(command: PrinterCommand) { sends++ }
                override fun close() {}
            } })
        store.put("model", model)
        return model
    }
    @Test fun openingConnectsEveryKnownPrinterWithoutSendingCommands() = runTest(dispatcher) {
        val model = model(second, listOf(first, second, third))
        runCurrent(); assertTrue(reads.isEmpty())
        model.foreground(true); runCurrent()
        assertEquals(setOf(first, second, third), reads.keys)
        assertEquals(second, model.state.value.address)
        assertTrue(model.state.value.connected)
        assertTrue(model.state.value.printerConnections.values.all { it.connected })
        model.foreground(true); runCurrent()
        assertTrue(reads.values.all { it == 1 })
        assertEquals(0, sends)
        store.clear()
    }
    @Test fun oneOfflinePrinterRetriesIndependentlyAndNeverChangesSelection() = runTest(dispatcher) {
        unavailable.add(first)
        val model = model()
        model.foreground(true); runCurrent()
        assertFalse(model.state.value.connected)
        assertTrue(model.state.value.printerConnections.getValue(second).connected)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(first, model.state.value.address)
        assertEquals(2, reads[first]); assertEquals(2, reads[second])
        unavailable.clear(); advanceTimeBy(2_000); runCurrent()
        assertTrue(model.state.value.connected); assertEquals(0, sends)
        store.clear()
    }
    @Test fun backgroundStopsAllSessionsAndResumeRestartsThemOnce() = runTest(dispatcher) {
        val model = model()
        model.foreground(true); runCurrent(); model.foreground(false)
        val previous = reads.toMap()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(previous, reads); assertTrue(model.state.value.printerConnections.isEmpty())
        model.foreground(true); model.foreground(true); runCurrent()
        assertTrue(reads.values.all { it == 2 }); assertEquals(0, sends)
        store.clear()
    }
    @Test fun disconnectSuppressesSelectedPrinterAcrossResumeButKeepsOthersConnected() = runTest(dispatcher) {
        val model = model()
        model.foreground(true); runCurrent(); model.disconnect()
        model.foreground(false); model.foreground(true); runCurrent()
        assertEquals(1, reads[first]); assertEquals(2, reads[second])
        assertFalse(model.state.value.connected)
        model.connect(first); runCurrent(); assertEquals(2, reads[first])
        assertEquals(0, sends)
        store.clear()
    }
    @Test fun switchingMovesOnlyTheExplicitTargetAndForgettingStopsItsSession() = runTest(dispatcher) {
        val model = model()
        model.foreground(true); runCurrent(); val generation = model.state.value.generation
        model.connect(second); runCurrent()
        assertEquals(second, model.state.value.address)
        assertTrue(model.state.value.generation > generation)
        assertEquals(setOf(first), model.state.value.printerConnections.keys)
        model.forgetPrinter(first); val oldReads = reads[first]
        advanceTimeBy(4_000); runCurrent()
        assertEquals(oldReads, reads[first]); assertTrue(model.state.value.printerConnections.isEmpty())
        assertEquals(0, sends)
        store.clear()
    }
    @Test fun invalidOrEmptySavedAddressesNeverReachFactory() = runTest(dispatcher) {
        val model = model("invalid", listOf("", "invalid"))
        model.foreground(true); runCurrent()
        assertTrue(reads.isEmpty()); assertEquals("", model.state.value.address)
        store.clear()
    }
    @Test fun noPreviousSelectionUsesFirstSavedPrinterAndManualDisconnectBeforeOpenWins() = runTest(dispatcher) {
        val model = model("", listOf(second, first))
        model.foreground(true); runCurrent(); assertEquals(second, model.state.value.address)
        store.clear()
        reads.clear()
        val stopped = model()
        stopped.disconnect(); stopped.foreground(true); runCurrent()
        assertFalse(reads.containsKey(first)); assertEquals(1, reads[second])
        store.clear()
    }
    @Test fun unavailableUnselectedPrinterRecoversAndAddressEditStopsOldEndpoint() = runTest(dispatcher) {
        unavailable.add(second)
        val model = model()
        model.foreground(true); runCurrent()
        assertTrue(model.state.value.connected)
        assertFalse(model.state.value.printerConnections.getValue(second).connected)
        unavailable.clear(); advanceTimeBy(2_000); runCurrent()
        assertTrue(model.state.value.printerConnections.getValue(second).connected)
        model.updateProfile(second, third, "Replacement")
        val oldReads = reads[second]
        advanceTimeBy(4_000); runCurrent()
        assertEquals(oldReads, reads[second])
        assertEquals(setOf(third), model.state.value.printerConnections.keys)
        assertEquals(first, model.state.value.address)
        assertEquals(0, sends)
        store.clear()
    }

    @Test fun selectedAddressEditKeepsNewTargetMonitoredButInvalidatesOldControlSession() = runTest(dispatcher) {
        val model = model()
        model.foreground(true); runCurrent()
        val generation = model.state.value.generation
        assertTrue(model.state.value.connected)
        model.updateProfile(first, third, "New endpoint")
        val previousReads = reads[first]
        runCurrent()
        assertEquals(third, model.state.value.address)
        assertFalse(model.state.value.connected)
        assertTrue(model.state.value.generation > generation)
        assertTrue(model.state.value.printerConnections.getValue(third).connected)
        model.execute(PrinterCommand("Fixture", "printer/gcode/script", allowedStates=setOf("standby")), generation)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(previousReads, reads[first])
        assertEquals(0, sends)
        model.connect(third); runCurrent()
        assertTrue(model.state.value.connected)
        assertFalse(model.state.value.printerConnections.containsKey(third))
        assertEquals(third, model.state.value.address)
        assertEquals(0, sends)
        store.clear()
    }

}
