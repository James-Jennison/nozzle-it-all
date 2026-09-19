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
        var cameras = emptyList<Camera>()
        var failHistory=false
        override fun history(start: Int): HistoryPage { if(failHistory) throw ApiFailure("Fixture failure");return HistoryPage(emptyList(),50) }
        var reads = 0; var sent = 0
        var value = PrinterSnapshot(true, "printing")
        var beforeRead: (() -> Unit)? = null
        var afterSend: (() -> Unit)? = null
        override fun snapshot(): PrinterSnapshot { reads++; beforeRead?.invoke(); return value }
        override fun catalog() = Catalog(emptyList(),emptyList(),cameras,emptyList())
        override fun image(camera: Camera) = byteArrayOf()
        override fun command(command: PrinterCommand) { sent++; afterSend?.invoke() }
        var closed = false
        override fun close() { closed = true }
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
        model.disconnect(); model.foreground(false)
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
        assertEquals("Printer state changed. Refresh before sending a command.", model.state.value.commandNotice)
        model.disconnect(); model.foreground(false)
    }
    @Test fun backgroundDuringPreflightCancelsDispatchAndDoesNotRetryOnResume() = runTest(dispatcher) {
        val fake=Fake();val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent()
        fake.beforeRead={model.foreground(false)}
        model.execute(pause,model.state.value.generation);runCurrent()
        assertEquals(0,fake.sent);assertFalse(model.state.value.busy)
        assertTrue(model.state.value.commandNotice.contains("Outcome unknown"))
        fake.beforeRead=null
        model.foreground(true);runCurrent()
        assertEquals(0,fake.sent)
        model.disconnect(); model.foreground(false)
    }
    @Test fun backgroundBeforeCommandCoroutineStartsReleasesBusyState() = runTest(dispatcher) {
        val fake=Fake();val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent()
        model.execute(pause,model.state.value.generation)
        model.foreground(false);runCurrent()
        assertEquals(0,fake.sent);assertFalse(model.state.value.busy)
        model.foreground(true);runCurrent()
        model.execute(pause,model.state.value.generation);runCurrent()
        assertEquals(1,fake.sent)
        model.disconnect(); model.foreground(false)
    }
    @Test fun backgroundAfterDispatchPreservesUnknownOutcomeWithoutReplay() = runTest(dispatcher) {
        val fake=Fake();val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent()
        fake.afterSend={model.foreground(false)}
        model.execute(pause,model.state.value.generation);runCurrent()
        assertEquals(1,fake.sent);assertFalse(model.state.value.busy)
        assertTrue(model.state.value.commandNotice.contains("Outcome unknown"))
        fake.afterSend=null
        model.foreground(true);runCurrent();advanceTimeBy(4000);runCurrent()
        assertEquals(1,fake.sent)
        assertTrue(model.state.value.commandNotice.contains("Outcome unknown"))
        model.disconnect(); model.foreground(false)
    }
    @Test fun preflightChangedStateNeverDispatches() = runTest(dispatcher) {
        val fake=Fake();val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent()
        fake.value=PrinterSnapshot(true,"complete")
        model.execute(pause,model.state.value.generation);runCurrent()
        assertEquals(0,fake.sent);assertFalse(model.state.value.busy)
        model.disconnect(); model.foreground(false)
    }
    @Test fun foregroundStopsAndResumesOneLoop() = runTest(dispatcher) {
        val fake=Fake();val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent();assertEquals(1,fake.reads)
        model.foreground(false);advanceTimeBy(8_000);runCurrent();assertEquals(1,fake.reads)
        model.foreground(true);runCurrent();assertEquals(2,fake.reads)
        advanceTimeBy(2_000);runCurrent();assertEquals(3,fake.reads)
        model.disconnect(); model.foreground(false)
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
        model.disconnect(); model.foreground(false)
    }
    @Test fun switchingAndForgettingAreBlockedDuringCommandPreflight() = runTest(dispatcher) {
        val first=Fake("http://first.local/");val second=Fake("http://second.local/")
        val model=PrinterModel(serviceFactory={if(it==first.address) first else second},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(first.address);runCurrent()
        val generation=model.state.value.generation
        first.beforeRead={
            model.disconnect();model.connect(second.address);model.forgetPrinter(first.address)
            assertEquals(generation,model.state.value.generation)
            assertEquals(first.address,model.state.value.address)
        }
        model.execute(pause,generation);runCurrent()
        assertEquals(1,first.sent);assertEquals(0,second.sent)
        first.beforeRead=null;model.disconnect()
    }

    @Test fun namedProfilesPersistOrderFavoritesAndEditsWithoutMovingACommandTarget() = runTest(dispatcher) {
        val first="http://first.local/";val second="http://second.local/"
        var saved=emptyList<PrinterProfile>();var selected=""
        val model=PrinterModel(initialAddress=first,initialPrinters=listOf(first,second),saveProfiles={address,profiles->selected=address;saved=profiles})
        model.updateProfile(first,first,"Workshop","");model.favoriteProfile(first);model.moveProfile(first,1)
        assertEquals(listOf(second,first),model.state.value.savedPrinters)
        assertEquals("Workshop",saved.last().name);assertTrue(saved.last().favorite)
        val restored=PrinterModel(initialAddress=selected,initialProfiles=saved)
        assertEquals(saved,restored.state.value.profiles)
        restored.updateProfile(first,second,"Duplicate","")
        assertEquals(saved,restored.state.value.profiles)
        restored.updateProfile(first,"http://third.local/","New printer","")
        assertEquals("http://third.local/",restored.state.value.address);assertFalse(restored.state.value.connected)
    }
    @Test fun editingConnectedPrinterApiKeyDisconnectsInsteadOfKeepingTheStaleSession() = runTest(dispatcher) {
        val fake=Fake();val model=PrinterModel(initialProfiles=listOf(PrinterProfile(fake.address)),serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent()
        assertTrue(model.state.value.connected)
        model.updateProfile(fake.address,fake.address,"","new-key")
        assertFalse(model.state.value.connected)
        assertTrue(fake.closed)
        assertEquals("new-key",model.state.value.profiles.single().apiKey)
        model.foreground(false)
    }

    @Test fun failedHistoryPageClearsPreviousPageSizeAndRefreshRecovers() = runTest(dispatcher) {
        val fake=Fake();val model=PrinterModel(serviceFactory={fake},clock={100_000},io=dispatcher)
        model.foreground(true);model.connect(fake.address);runCurrent()
        model.loadHistory(0);runCurrent();assertEquals(50,model.state.value.historyPageSize)
        fake.failHistory=true;model.loadHistory(50)
        assertEquals(0,model.state.value.historyPageSize);runCurrent()
        assertEquals(0,model.state.value.historyPageSize);assertFalse(model.state.value.historyLoading)
        assertTrue(model.state.value.historyNote.isNotBlank());assertTrue(model.state.value.history.isEmpty())
        fake.failHistory=false;model.loadHistory(0);runCurrent()
        assertEquals(50,model.state.value.historyPageSize);assertEquals("",model.state.value.historyNote)
        model.disconnect(); model.foreground(false)
    }

    @Test fun cameraPreferencePersistsAndDoesNotBleedAcrossPrinters() = runTest(dispatcher) {
        val first=Fake("http://first.local/").apply { cameras=listOf(Camera("A","","/a","webrtc-camerastreamer","a"),Camera("B","","/b","webrtc-camerastreamer","b")) }
        val second=Fake("http://second.local/").apply { cameras=listOf(Camera("Other","","/other","webrtc-camerastreamer","other")) }
        var saved=emptyList<PrinterProfile>()
        val model=PrinterModel(serviceFactory={if(it==first.address) first else second},clock={100_000},io=dispatcher,saveProfiles={_,p->saved=p})
        model.foreground(true);model.connect(first.address);runCurrent()
        val epoch=model.state.value.cameraGeneration
        model.selectCamera("b");assertEquals("b",model.state.value.selectedCamera()?.id);assertTrue(model.state.value.cameraGeneration>epoch)
        assertEquals("b",saved.first().cameraId)
        model.connect(second.address);assertNull(model.state.value.selectedCamera());runCurrent()
        assertEquals("other",model.state.value.selectedCamera()?.id)
        model.connect(first.address);runCurrent();assertEquals("b",model.state.value.selectedCamera()?.id)
        model.disconnect(); model.foreground(false)
    }

}
