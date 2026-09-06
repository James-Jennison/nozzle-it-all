package net.jamesjennison.klippercompanion

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PrinterModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private class Fake(override val address: String = "http://fixture.local/") : PrinterService {
        var reads = 0; var sent = 0
        var value = PrinterSnapshot(true, "printing")
        var beforeRead: (() -> Unit)? = null
        override fun snapshot(): PrinterSnapshot { reads++; beforeRead?.invoke(); return value }
        override fun catalog() = Catalog(emptyList(),emptyList(),emptyList(),emptyList())
        override fun image(camera: Camera) = byteArrayOf()
        override fun command(command: PrinterCommand) { sent++ }
        override fun close() {}
    }
    private val pause = PrinterCommand("Pause", "printer/print/pause", allowedStates=setOf("printing"))
    @Test fun savedProfilesMigrateNormalizeAndRejectInvalidAddresses() {
        val model = PrinterModel(initialAddress = "http://first.local", initialPrinters = listOf("http://first.local/", "http://second.local", "not a URL", "https://user:password@example.com"))
        assertEquals(listOf("http://first.local/", "http://second.local/"), model.state.value.savedPrinters)
        assertEquals("http://first.local/", model.state.value.address)
        assertEquals("", PrinterModel(initialAddress = "not a URL").state.value.address)
    }
    @Test fun switchingProfilesClearsOldStateAndRejectsOldConfirmation() = runTest(dispatcher) {
        val first = Fake("http://first.local/"); val second = Fake("http://second.local/")
        var saved = emptyList<String>()
        val model = PrinterModel(serviceFactory = { if(it == first.address) first else second }, clock = { 100_000 }, io = dispatcher, saveSettings = { _, printers -> saved = printers })
        model.foreground(true); model.connect(first.address); runCurrent()
        val oldGeneration = model.state.value.generation
        model.connect(second.address)
        assertFalse(model.state.value.connected); assertNull(model.state.value.snapshot)
        assertTrue(model.state.value.catalog.cameras.isEmpty())
        runCurrent()
        model.execute(pause, oldGeneration); runCurrent()
        assertEquals(0, first.sent); assertEquals(0, second.sent)
        assertEquals(listOf(first.address, second.address), saved)
        val generation = model.state.value.generation
        val reads = second.reads
        model.connect(second.address); runCurrent()
        assertEquals(generation, model.state.value.generation)
        assertEquals(reads, second.reads)
        assertEquals(2, saved.size)
        model.disconnect()
    }
    @Test fun forgettingActiveProfileDisconnectsAndDoesNotRestoreOnResume() = runTest(dispatcher) {
        val fake = Fake(); var savedAddress = "initial"; var profiles = emptyList<String>()
        val model = PrinterModel(saveSettings = { address, printers -> savedAddress = address; profiles = printers }, serviceFactory = { fake }, clock = { 100_000 }, io = dispatcher)
        model.foreground(true); model.connect(fake.address); runCurrent()
        val reads = fake.reads
        model.forgetPrinter(fake.address)
        assertFalse(model.state.value.connected); assertEquals("", savedAddress); assertTrue(profiles.isEmpty())
        model.foreground(false); model.foreground(true); advanceTimeBy(4000); runCurrent()
        assertEquals(reads, fake.reads); assertEquals(0, fake.sent)
    }
    @Test fun clearDuringCommandPreflightDoesNotSendOrUpdateDisposedState() = runTest(dispatcher) {
        val fake=Fake();val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        val store=androidx.lifecycle.ViewModelStore();store.put("printer",model)
        model.foreground(true);model.connect(fake.address);runCurrent()
        fake.beforeRead={store.clear()}
        model.execute(pause,model.state.value.generation);runCurrent()
        assertEquals(0,fake.sent)
        assertEquals("Sending command…",model.state.value.commandNotice)
    }
    @Test fun staleGenerationAndStaleTimeNeverDispatch() = runTest(dispatcher) {
        val fake=Fake();var now=100_000L
        val model=PrinterModel(serviceFactory={fake},clock={now},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent()
        val gen=model.state.value.generation
        model.execute(pause,gen-1);runCurrent();assertEquals(0,fake.sent)
        now += 11_000
        model.execute(pause,gen);runCurrent();assertEquals(0,fake.sent)
        model.disconnect()
    }
    @Test fun preflightChangedStateNeverDispatches() = runTest(dispatcher) {
        val fake=Fake();val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent()
        fake.value=PrinterSnapshot(true,"complete")
        model.execute(pause,model.state.value.generation);runCurrent()
        assertEquals(0,fake.sent);assertFalse(model.state.value.busy)
        model.disconnect()
    }
    @Test fun foregroundStopsAndResumesOneLoop() = runTest(dispatcher) {
        val fake=Fake();val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent();assertEquals(1,fake.reads)
        model.foreground(false);advanceTimeBy(8_000);runCurrent();assertEquals(1,fake.reads)
        model.foreground(true);runCurrent();assertEquals(2,fake.reads)
        advanceTimeBy(2_000);runCurrent();assertEquals(3,fake.reads)
        model.disconnect()
    }
    @Test fun latePollAfterDisconnectCannotRestoreConnectedState() = runTest(dispatcher) {
        val fake=Fake();lateinit var model:PrinterModel
        model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        fake.beforeRead = { model.disconnect() }
        model.foreground(true);model.connect(fake.address);runCurrent()
        assertFalse(model.state.value.connected);assertNull(model.state.value.snapshot)
        assertEquals("Disconnected.",model.state.value.message)
    }
    @Test fun latePollCannotOverwriteReplacementConnection() = runTest(dispatcher) {
        val first=Fake();val second=Fake().apply { value=PrinterSnapshot(true,"paused") }
        lateinit var model:PrinterModel
        model=PrinterModel(serviceFactory={ if(it=="first") first else second },clock={100_000},io=dispatcher)
        first.beforeRead = { model.connect("second") }
        model.foreground(true);model.connect("first");runCurrent()
        assertEquals("paused",model.state.value.snapshot?.state)
        assertEquals(1,first.reads);assertEquals(1,second.reads)
        model.disconnect()
    }
}
