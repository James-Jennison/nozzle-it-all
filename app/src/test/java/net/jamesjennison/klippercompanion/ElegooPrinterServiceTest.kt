package net.jamesjennison.klippercompanion

import com.nozzleitall.adapter.elegoo.Canvas
import com.nozzleitall.adapter.elegoo.Sdcp
import com.nozzleitall.printer.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

// PrinterKind.ELEGOO's service over a fake adapter session whose status is built with the Elegoo adapter's own parsers
// from its own test fixtures (adapter-elegoo/src/test/resources/elegoo). No network; no printer.
class ElegooPrinterServiceTest {
    private val canvasReply = JSONObject("""{"Ack":0,"active_canvas_id":0,"active_tray_id":1,"auto_refill":false,"canvas_list":[{"canvas_id":0,"connected":1,"tray_list":[
        {"tray_id":0,"brand":"ELEGOO","filament_type":"PLA","filament_name":"PLA Matte","filament_color":"#ff0000","min_nozzle_temp":190,"max_nozzle_temp":230,"status":2},
        {"tray_id":1,"brand":" ","filament_type":"PETG","filament_name":"PETG","filament_color":"00ff00","min_nozzle_temp":230,"max_nozzle_temp":260,"status":1},
        {"tray_id":2,"brand":"","filament_type":"","filament_name":"","filament_color":"","min_nozzle_temp":0,"max_nozzle_temp":0,"status":0},
        {"tray_id":3,"brand":"ELEGOO","filament_type":"PLA","filament_name":"PLA","filament_color":"#0000FFFF","min_nozzle_temp":190,"max_nozzle_temp":230,"status":2}]}]}""")
    private val printing = JSONObject("""{"Status":{"CurrentStatus":[1],"TempOfHotbed":59.8,"TempOfNozzle":219.6,"TempTargetHotbed":60,"TempTargetNozzle":220,
        "PrintInfo":{"Status":13,"CurrentLayer":7,"TotalLayer":100,"CurrentTicks":600,"TotalTicks":3600,"Filename":"cube.gcode","Progress":42}}}""")
    private val idle = JSONObject("""{"Status":{"CurrentStatus":[0],"TempOfHotbed":25,"TempOfNozzle":26,"TempTargetHotbed":0,"TempTargetNozzle":0,"PrintInfo":{"Status":0}}}""")

    private fun status(message: JSONObject, withCanvas: Boolean = true): PrinterStatus {
        val r = Sdcp.parseStatus(message)!!
        return PrinterStatus(r.state, ConnectionRoute.LAN, r.job, Temperature(r.bed, r.bedTarget), Canvas.toolheads(if (withCanvas) Canvas.parse(canvasReply) else null, r.nozzle, r.nozzleTarget), r.message)
    }

    private class FakeSession(var current: PrinterStatus, override val capabilities: Capabilities = Capabilities(uploadJob = true, startPrint = true, pausePrint = true, resumePrint = true, cancelPrint = true)) : PrinterSession {
        override val identity = PrinterIdentity("t", "t", "Elegoo Centauri Carbon", PrinterFamily.ELEGOO, "192.168.1.2")
        var config: PrinterConfig? = null
        val uploads = mutableListOf<Pair<String, String>>()
        val performed = mutableListOf<PrinterAction>()
        var uploadResult: (String) -> UploadResult = { UploadResult.Uploaded(it) }
        var closed = false
        override fun status() = current.copy(observedAtMillis = System.currentTimeMillis())
        override fun cameras() = emptyList<CameraEndpoint>()
        override fun snapshot(camera: CameraEndpoint) = ByteArray(0)
        override fun upload(file: File, remoteName: String, progress: UploadProgress): UploadResult { uploads += remoteName to file.readText(); return uploadResult(remoteName) }
        override fun perform(action: PrinterAction): ActionOutcome {
            performed += action
            return if ((action == PrinterAction.Pause || action == PrinterAction.Resume) && !capabilities.pausePrint) ActionOutcome.Rejected("Centauri Carbon 2 doesn't support that from Nozzle It All.") else ActionOutcome.Accepted
        }
        override fun close() { closed = true }
    }

    private fun service(fake: FakeSession, accessCode: String = "", serial: String = "", model: String = "Elegoo Centauri Carbon") =
        ElegooPrinterService("http://192.168.1.2/", accessCode, serial, model) { fake.config = it; fake }

    @Test fun passesTheConnectionDetailsToTheAdapter() {
        val fake = FakeSession(status(idle))
        service(fake, "654321", "CC2A0001B2C3", "Elegoo Centauri Carbon 2").close()
        val c = fake.config!!
        assertEquals("elegoo-lan", c.adapterId); assertEquals("654321", c.secret); assertEquals("CC2A0001B2C3", c.extras["serial"])
        assertEquals(PrinterFamily.ELEGOO, c.identity.family); assertEquals("Elegoo Centauri Carbon 2", c.identity.model)
        assertTrue(fake.closed)
        // The adapter picks MQTT for a Centauri Carbon 2 and SDCP otherwise, from the model name passed here.
        assertTrue(com.nozzleitall.adapter.elegoo.ElegooLanAdapter.usesMqtt(c))
        assertFalse(com.nozzleitall.adapter.elegoo.ElegooLanAdapter.usesMqtt(c.copy(identity = c.identity.copy(model = "Elegoo Centauri Carbon"))))
    }

    @Test fun printingStatusBecomesASnapshot() {
        val s = service(FakeSession(status(printing))).snapshot()
        assertTrue(s.ready); assertEquals("printing", s.state); assertEquals("cube.gcode", s.activeFilename)
        assertEquals(0.42f, s.progress, 0.001f); assertEquals(7, s.currentLayer); assertEquals(100, s.totalLayers)
        assertEquals(59.8, s.bed!!, 0.01); assertEquals(220.0, s.nozzleTarget!!, 0.01); assertEquals(600.0, s.printDuration!!, 0.01)
        assertEquals(PrinterState.PRINTING, sharedState(s.state))
        assertEquals("standby", service(FakeSession(status(idle))).snapshot().state)
        assertEquals(PrinterState.STARTING, sharedState(ElegooPrinterService.stateName(PrinterState.STARTING)))
        assertEquals(PrinterState.FINISHED, sharedState(ElegooPrinterService.stateName(PrinterState.FINISHED)))
    }

    @Test fun anOfflinePrinterIsAFailedReadNotAState() {
        try { service(FakeSession(PrinterStatus(PrinterState.OFFLINE, ConnectionRoute.LAN, message = "The printer did not answer."))).snapshot(); fail() }
        catch (e: ApiFailure) { assertEquals("The printer did not answer.", e.message) }
    }

    @Test fun canvasTraysAreFilamentSlots() {
        val slots = service(FakeSession(status(idle))).filamentSlots()
        assertEquals("CANVAS", slots.source)
        assertEquals(listOf(0, 1, 2, 3), slots.slots.map { it.tool })
        assertEquals(listOf("PLA Matte", "PETG", null, "PLA"), slots.slots.map { it.material })
        assertEquals(listOf("#FF0000", "#00FF00", null, "#0000FF"), slots.slots.map { it.colorHex })
        assertEquals(listOf(false, true, false, false), slots.slots.map { it.active })
        assertEquals("ELEGOO", slots.slots[0].vendor)
        assertTrue("no CANVAS: no slots", service(FakeSession(status(idle, withCanvas = false))).filamentSlots().slots.isEmpty())
    }

    @Test fun sendAndStartUploadsThenStartsWithTheSlotsTheFileUses() {
        val fake = FakeSession(status(idle))
        val file = File.createTempFile("four", ".gcode").apply { deleteOnExit(); writeText("M729\nM6211 A1 L200 T0 Q220 R230 S220\nT0\nG1 X1\nM6211 T1 L30 M1 N1 Q1 R1 S1\nT1\nM6211 T3 L30 M1 N1 Q1 R1 S1\nT3\n") }
        service(fake).command(PrinterCommand("Print four.gcode", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "four.gcode")))
        assertEquals(listOf("four.gcode"), fake.uploads.map { it.first })
        assertEquals(listOf(PrinterAction.StartJob("four.gcode", listOf(0, 1, -1, 3))), fake.performed)
        // Only the slots the file selects are mapped, so the empty third tray doesn't block the print.
        assertEquals(3, Canvas.slotMap(listOf(0, 1, -1, 3), Canvas.parse(canvasReply)).length())
    }

    @Test fun aSingleColourFileOrNoCanvasStartsWithAnEmptyMap() {
        val file = File.createTempFile("one", ".gcode").apply { deleteOnExit(); writeText("T0\nG1 X1\n") }
        val fake = FakeSession(status(idle, withCanvas = false))
        service(fake).command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "one.gcode")))
        assertEquals(listOf(PrinterAction.StartJob("one.gcode", emptyList())), fake.performed)
    }

    @Test fun nothingIsSentWhileBusyAndAFailedUploadNeverStarts() {
        val file = File.createTempFile("one", ".gcode").apply { deleteOnExit(); writeText("T0\n") }
        val busy = FakeSession(status(printing))
        try { service(busy).command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "one.gcode"))); fail() } catch (_: ApiFailure) {}
        assertTrue(busy.uploads.isEmpty()); assertTrue(busy.performed.isEmpty())
        val failing = FakeSession(status(idle)).apply { uploadResult = { UploadResult.Failed("The file failed the printer's MD5 check. Send it again.") } }
        try { service(failing).command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "one.gcode"))); fail() }
        catch (e: ApiFailure) { assertTrue(e.message!!.contains("MD5")) }
        assertTrue(failing.performed.isEmpty())
    }

    @Test fun pauseResumeCancelGoThroughTheGuard() {
        val fake = FakeSession(status(printing))
        val s = service(fake)
        s.command(PrinterCommand("Pause", "printer/print/pause", allowedStates = setOf("printing")))
        s.command(PrinterCommand("Cancel", "printer/print/cancel"))
        assertEquals(listOf(PrinterAction.Pause, PrinterAction.Cancel), fake.performed)
        // The guard refuses an action the printer's state doesn't allow, without reaching the printer.
        try { s.command(PrinterCommand("Resume", "printer/print/resume")); fail() } catch (_: ApiFailure) {}
        assertEquals(2, fake.performed.size)
        try { s.command(PrinterCommand("Home", "printer/gcode/script", mapOf("script" to "G28"))); fail() } catch (_: ApiFailure) {}
        // A Centauri Carbon 2 session refuses pause (no LAN resume); the refusal reaches the user.
        val cc2 = FakeSession(status(printing), Capabilities(uploadJob = true, startPrint = true, cancelPrint = true))
        try { service(cc2).command(PrinterCommand("Pause", "printer/print/pause")); fail() } catch (e: ApiFailure) { assertTrue(e.message!!.contains("doesn't support")) }
    }

    @Test fun printerServiceForPicksTheElegooServiceAndProtocol() {
        val cc = printerServiceFor(PrinterProfile("http://192.168.1.2/", kind = PrinterKind.ELEGOO, slicingModel = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_CANVAS), "http://192.168.1.2/")
        assertTrue(cc is ElegooPrinterService); assertTrue(cc is FilamentSlotReader); cc.close()
        assertTrue(printerServiceFor(PrinterProfile("http://192.168.1.2/"), "http://192.168.1.2/") is Moonraker)
    }
}
