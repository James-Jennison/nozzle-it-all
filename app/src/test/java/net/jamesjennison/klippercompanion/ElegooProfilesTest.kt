package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Test
import java.io.File

// The Elegoo Centauri Carbon profiles on Android: which firmware each pack is for, the pairing rules that keep a stock
// pack (M729 start G-code) away from a COSMOS printer and a COSMOS pack away from an Elegoo-firmware printer, and the
// four CANVAS slots. Runs on the JVM against the real bundled asset files.
class ElegooProfilesTest {
    private val assets: File = listOf("src/main/assets/slicer_profiles", "app/src/main/assets/slicer_profiles").map(::File).first { it.isDirectory }
    private fun machine(model: SlicingPrinterModel) = File(assets, SlicingModelCatalog.info(model).assetDir + "/machine.json").readText()
    private val cosmosAfc = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_COSMOS_CANVAS
    private val stockCc = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_CANVAS
    private val stockCc2 = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_2_CANVAS

    @Test fun theThreeCanvasPacksAreOfferedWithTheirOwnFolders() {
        assertEquals("elegoo_centauri_carbon_cosmos_afc", SlicingModelCatalog.info(cosmosAfc).assetDir)
        assertEquals("elegoo_centauri_carbon_canvas", SlicingModelCatalog.info(stockCc).assetDir)
        assertEquals("elegoo_centauri_carbon_2_canvas", SlicingModelCatalog.info(stockCc2).assetDir)
        listOf(cosmosAfc, stockCc, stockCc2).forEach { m ->
            assertTrue(m.name, SlicingEngineSupport.isSupported(m))
            assertTrue(m.name, matchingSlicingModels("canvas").any { it.model == m })
            assertFalse("no slicer names in the label", Regex("(?i)slicer|orca").containsMatchIn(SlicingModelCatalog.info(m).label))
        }
    }

    @Test fun firmwareOfEachPackMatchesItsStartGcode() {
        assertEquals(ElegooProfileFirmware.COSMOS, ElegooProfiles.firmwareFor(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON))
        assertEquals(ElegooProfileFirmware.COSMOS, ElegooProfiles.firmwareFor(cosmosAfc))
        assertEquals(ElegooProfileFirmware.ELEGOO_STOCK, ElegooProfiles.firmwareFor(stockCc))
        assertEquals(ElegooProfileFirmware.ELEGOO_STOCK, ElegooProfiles.firmwareFor(stockCc2))
        assertNull(ElegooProfiles.firmwareFor(SlicingPrinterModel.SNAPMAKER_U1)); assertNull(ElegooProfiles.firmwareFor(null))
        // The rule's premise, checked on the real packs: Elegoo's own start G-code loads with M6211 (and the Centauri Carbon's
        // calls M729); COSMOS's calls neither.
        fun start(m: SlicingPrinterModel) = JSONObject(machine(m)).optString("machine_start_gcode").lineSequence()
        assertEquals("M729", ElegooProfiles.stockElegooCommand(start(stockCc)))
        for (m in listOf(stockCc, stockCc2)) assertTrue(m.name, start(m).any { it.startsWith("M6211 ") })
        for (m in listOf(cosmosAfc, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON)) assertTrue(m.name, start(m).none { it.startsWith("M6211") })
        assertNull(ElegooProfiles.stockElegooCommand(start(cosmosAfc)))
        assertNull(ElegooProfiles.stockElegooCommand(start(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON)))
    }

    @Test fun stockPacksOnlyForElegooConnectionsCosmosPacksOnlyForMoonraker() {
        val moonraker = listOf(PrinterKind.GENERIC_KLIPPER, PrinterKind.SNAPMAKER_U1, PrinterKind.SNAPMAKER_U1_PAXX)
        for (kind in PrinterKind.entries) {
            for (m in listOf(stockCc, stockCc2)) assertEquals("$m on $kind", kind == PrinterKind.ELEGOO, ElegooProfiles.connectionProblem(m, kind) == null)
            for (m in listOf(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, cosmosAfc)) assertEquals("$m on $kind", kind in moonraker, ElegooProfiles.connectionProblem(m, kind) == null)
            assertNull(ElegooProfiles.connectionProblem(SlicingPrinterModel.GENERIC_KLIPPER, kind))
            assertNull(ElegooProfiles.connectionProblem(null, kind))
        }
        assertTrue(ElegooProfiles.connectionProblem(stockCc2, PrinterKind.GENERIC_KLIPPER)!!.contains("M729"))
    }

    @Test fun cosmosCanvasKeepsTheLiveFirmwareGuard() {
        // Declared generation, the live-firmware check and the pack all behave exactly as for the plain COSMOS pack.
        val p = PrinterProfile("http://cc.local/", slicingModel = cosmosAfc, declaredFirmwareVersion = "Release - 26.08.0")
        assertEquals(CosmosProfileGeneration.CURRENT, p.declaredCosmosProfileGeneration)
        assertNull(p.copy(declaredFirmwareVersion = "").declaredCosmosProfileGeneration)
        assertNull(PrinterProfile("http://cc.local/", slicingModel = stockCc, declaredFirmwareVersion = "Release - 26.08.0").declaredCosmosProfileGeneration)
        assertEquals(FirmwareMatchResult.Match, checkCentauriCarbonFirmwareMatch(FirmwareIdentity("OpenCentauri Cosmos", "Release - 26.08.0"), p.declaredCosmosProfileGeneration))
        assertTrue(checkCentauriCarbonFirmwareMatch(FirmwareIdentity("", "1.6.0.267_20260815150420"), p.declaredCosmosProfileGeneration) is FirmwareMatchResult.Unknown)
        assertNotNull(slicingProfilePack(cosmosAfc, CosmosProfileGeneration.CURRENT))
        assertNull(slicingProfilePack(cosmosAfc, CosmosProfileGeneration.LEGACY))
        assertNull(slicingProfilePack(cosmosAfc, null))
    }

    @Test fun canvasPacksHaveFourSlotsAndNeverTakeACustomMachine() {
        val custom = CustomMachine(200.0, 200.0, 200.0, false, "G28", "M84")
        val index = JSONObject(File(assets, "index.json").readText()).getJSONArray("profiles").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("id") } }
        for ((m, gen) in listOf(cosmosAfc to CosmosProfileGeneration.CURRENT, stockCc to null, stockCc2 to null)) {
            val pack = slicingProfilePack(m, gen, custom)!!
            assertNull("$m: firmware-specific packs are never overridden", pack.custom)
            assertEquals(1, parseToolCount(machine(m))) // one nozzle...
            assertEquals(4, pack.toolCountOf(machine(m))) // ...fed by four CANVAS lanes
            assertEquals("the desktop's count", index.getValue(SlicingModelCatalog.info(m).assetDir).getInt("tools"), pack.toolCountOf(machine(m)))
            assertEquals(MultiToolFamily.FILAMENT_SWAP, multiToolFamily(m, 4))
        }
        // Unchanged: the plain COSMOS pack has one slot and other models still take a custom machine.
        assertEquals(1, slicingProfilePack(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, CosmosProfileGeneration.CURRENT)!!.toolCountOf(machine(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON)))
        assertEquals(custom, slicingProfilePack(SlicingPrinterModel.GENERIC_KLIPPER, null, custom)!!.custom)
        assertEquals(4, slicingProfilePack(SlicingPrinterModel.SNAPMAKER_U1, null)!!.toolCountOf(machine(SlicingPrinterModel.SNAPMAKER_U1)))
    }

    @Test fun multiColourSliceInputsCoverTheFourSlots() {
        val (tools, materials) = multiToolSliceInputsFor(emptyList(), 4)
        assertTrue(tools.isEmpty()); assertEquals(4, materials.size)
    }

    @Test fun findsStockFirmwareCommandsAsTheDesktopDoes() {
        // The desktop's StockElegooGuardTest cases.
        assertEquals("M729", ElegooProfiles.stockElegooCommand(sequenceOf("G28", "  m729 ; clean nozzle", "M8213")))
        assertNull(ElegooProfiles.stockElegooCommand(sequenceOf("PRINT_START EXTRUDER=220", "; M729 in a comment", "M7290", "T1 PURGE_LENGTH=30")))
        assertEquals("M8213", ElegooProfiles.stockElegooCommand(sequenceOf("M8213 ; old OpenCentauri profile")))
    }

    @Test fun slotMapForAStartOnAnElegooPrinter() {
        val fourColour = sequenceOf("M6211 A1 L200 T0 Q220 R230 S220", "T0", "G1 X1", "M6211 T2 L30 M1 N1 Q1 R1 S1", "T2", "T1 ; comment", "T3")
        assertEquals(listOf(0, 1, 2, 3), ElegooProfiles.toolheadMap(fourColour, 4))
        assertEquals(listOf(0, -1, 2), ElegooProfiles.toolheadMap(sequenceOf("T0", "T2"), 4))
        assertEquals(listOf(-1, 1), ElegooProfiles.toolheadMap(sequenceOf("T1"), 4)) // one colour from slot 2
        assertEquals(emptyList<Int>(), ElegooProfiles.toolheadMap(sequenceOf("T0", "G1 X1"), 4)) // single colour: print from what's loaded
        assertEquals(emptyList<Int>(), ElegooProfiles.toolheadMap(fourColour, 1)) // no CANVAS
        assertEquals(emptyList<Int>(), ElegooProfiles.toolheadMap(sequenceOf("T[next_extruder]", "M6211 T1 L2", "TX"), 4))
    }

    @Test fun discoverySuggestsTheCanvasPackOnCosmosWithAfc() {
        assertEquals(cosmosAfc, PrinterDiscovery.classifyMoonraker("cc", "OpenCentauri Cosmos", "Release - 26.08.0", "192.168.1.9", hasAfc = true).slicingModel)
        assertEquals(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, PrinterDiscovery.classifyMoonraker("cc", "OpenCentauri Cosmos", "Release - 26.08.0", "192.168.1.9").slicingModel)
        assertEquals(SlicingPrinterModel.GENERIC_KLIPPER, PrinterDiscovery.classifyMoonraker("v", "", "v0.12", "192.168.1.9", hasAfc = true).slicingModel)
        assertTrue(PrinterDiscovery.hasAfcObject(JSONObject("""{"objects":["webhooks","AFC","AFC_stepper CANVAS_1"]}""")))
        assertFalse(PrinterDiscovery.hasAfcObject(JSONObject("""{"objects":["webhooks","AFC_stepper lane1"]}""")))
        assertFalse(PrinterDiscovery.hasAfcObject(null))
    }

    @Test fun elegooDiscoveryRepliesBecomeElegooPrinters() {
        // Adapter test fixtures (adapter-elegoo/src/test/resources/elegoo/*_discovery.json).
        val cc = PrinterScanner.elegooReply("192.168.1.2", """{"Id":"979d4C788A4a78bC777A870F1A02867A","Data":{"Name":"Workshop CC","MachineName":"Centauri Carbon","BrandName":"ELEGOO","MainboardIP":"192.168.1.2","MainboardID":"000000000001d354","ProtocolVersion":"V3.0.0","FirmwareVersion":"V1.4.44"}}""")!!
        assertEquals(PrinterKind.ELEGOO, cc.kind); assertEquals("Workshop CC", cc.name); assertEquals(stockCc, cc.slicingModel)
        assertEquals("000000000001d354", cc.serial); assertTrue(cc.detail.contains("1.4.44"))
        val cc2 = PrinterScanner.elegooReply("192.168.1.3", """{"id":0,"result":{"host_name":"Garage CC2","machine_model":"Centauri Carbon 2","sn":"CC2A0001B2C3","token_status":1,"lan_status":1}}""")!!
        assertEquals(PrinterKind.ELEGOO, cc2.kind); assertEquals(stockCc2, cc2.slicingModel); assertEquals("CC2A0001B2C3", cc2.serial)
        assertTrue(cc2.detail.contains("access code"))
        assertNull(PrinterScanner.elegooReply("192.168.1.4", """{"result":{"klippy_state":"ready"}}"""))
        assertNull(PrinterScanner.elegooReply("192.168.1.4", "not json"))
    }

    @Test fun elegooKindCapabilities() {
        val c = capabilitiesFor(PrinterKind.ELEGOO)
        assertEquals(PrinterTransport.ELEGOO, c.transport)
        assertTrue(c.acceptsOnDeviceSlicedGcode); assertTrue(c.supportsPauseResumeCancel)
        assertFalse(c.supportsKlipperExtras); assertFalse(c.verifiedOnRealHardware); assertFalse(c.supportsJog)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ElegooPrinterProfileSaveTest {
    private val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    @org.junit.Before fun before() { kotlinx.coroutines.Dispatchers.setMain(dispatcher) }
    @org.junit.After fun after() { kotlinx.coroutines.Dispatchers.resetMain() }

    @Test fun savingAPrinterRefusesAMismatchedElegooProfile() {
        val model = PrinterModel(io = dispatcher)
        assertNotNull(model.addProfile(PrinterProfile("http://192.168.1.20/", kind = PrinterKind.GENERIC_KLIPPER, slicingModel = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_CANVAS)))
        assertNotNull(model.addProfile(PrinterProfile("http://192.168.1.21/", kind = PrinterKind.ELEGOO, slicingModel = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_COSMOS_CANVAS)))
        assertNull(model.addProfile(PrinterProfile("http://192.168.1.22/", kind = PrinterKind.ELEGOO, slicingModel = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_2_CANVAS)))
        assertNull(model.addProfile(PrinterProfile("http://192.168.1.23/", kind = PrinterKind.GENERIC_KLIPPER, slicingModel = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_COSMOS_CANVAS)))
        // Switching an existing COSMOS printer to the stock pack is refused too.
        assertNotNull(model.updateProfile("http://192.168.1.23/", "http://192.168.1.23/", "cc", "", PrinterKind.GENERIC_KLIPPER, "", SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_CANVAS))
        assertEquals(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_COSMOS_CANVAS, model.state.value.profiles.first { it.address == "http://192.168.1.23/" }.slicingModel)
    }
}
