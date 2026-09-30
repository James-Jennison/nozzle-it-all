package com.nozzleitall.adapter.paxx

import com.nozzleitall.printer.*
import okhttp3.Call
import okhttp3.EventListener
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList

class PaxxLanAdapterTest {
    /** Records every host the adapter opens a connection to. */
    private class HostRecorder : EventListener() {
        val hosts = CopyOnWriteArrayList<String>()
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { hosts += inetSocketAddress.address.hostAddress }
        override fun dnsStart(call: Call, domainName: String) { hosts += "dns:$domainName" }
    }

    private fun config(address: String, family: PrinterFamily = PrinterFamily.PAXX_U1) =
        PrinterConfig(PrinterIdentity("u1", "Workshop U1", "Snapmaker U1", family, address), PaxxLanAdapter.ID)

    @Test fun probeRecognisesPaxxFromItsSettingsFile() = FakeMoonraker(paxx = true).use { fake ->
        val found = PaxxLanAdapter().probe(fake.address)!!
        assertEquals(PrinterFamily.PAXX_U1, found.suggestedFamily)
        assertEquals(PaxxLanAdapter.ID, found.adapterId)
        assertEquals("Snapmaker U1", found.model)
        assertTrue(found.evidence, found.evidence.contains("extended2.cfg"))
        // Probing is read-only.
        assertTrue(fake.calls.all { it.method == "GET" })
    }

    @Test fun probeRoutesStockFirmwareToTheOptionalAdapter() = FakeMoonraker(paxx = false).use { fake ->
        val found = PaxxLanAdapter().probe(fake.address)!!
        assertEquals(PrinterFamily.STOCK_U1, found.suggestedFamily)
        assertEquals("stock-u1", found.adapterId)
    }

    @Test fun statusMapsRecordedU1DataOntoTheSharedModel() = FakeMoonraker().use { fake ->
        PaxxLanAdapter().open(config(fake.address)).use { s ->
            val st = s.status()
            assertEquals(PrinterState.PRINTING, st.state)
            assertEquals(ConnectionRoute.LAN, st.route)
            assertEquals("Cube_TPU_27m50s.gcode", st.job!!.fileName)
            assertEquals(44, st.job!!.currentLayer); assertEquals(135, st.job!!.totalLayers)
            assertEquals(4, st.toolheads.size)
            assertEquals(2, st.toolheads.single { it.active }.index)
            val t0 = st.toolheads[0].material!!
            assertEquals("Polymaker", t0.vendor); assertEquals("PLA", t0.type); assertEquals("#BE38F3", t0.colorHex); assertTrue(t0.fromTag)
            assertEquals("TPU", st.toolheads[2].material!!.type)
            val fs = com.nozzleitall.printer.ext.FullSpectrumState.from(st)!!
            assertTrue(fs.available)
            assertEquals(listOf("#BE38F3", "#E2DEDB", "#DD0000", "#000000"), fs.palette)
            assertEquals(35.0, st.bed!!.target!!, 0.0)
            assertFalse(s.capabilities.requiresVendorAccount)
        }
    }

    @Test fun onlyThePrintersOwnAddressIsEverContacted() = FakeMoonraker().use { fake ->
        val rec = HostRecorder()
        val adapter = PaxxLanAdapter(rec)
        adapter.probe(fake.address)
        adapter.open(config(fake.address)).use { s ->
            s.status(); s.cameras(); s.snapshot(s.cameras().first())
            val g = File.createTempFile("job", ".gcode").apply { writeText("G28\n".repeat(1000)); deleteOnExit() }
            s.upload(g, "job.gcode")
            s.perform(PrinterAction.Pause)
        }
        assertTrue(rec.hosts.isNotEmpty())
        assertEquals(setOf("127.0.0.1"), rec.hosts.toSet())
    }

    @Test fun camerasResolveToThePrinterAndSkipTheScreenMirror() = FakeMoonraker().use { fake ->
        PaxxLanAdapter().open(config(fake.address)).use { s ->
            val cams = s.cameras()
            assertEquals(1, cams.size)
            assertEquals(CameraKind.WEBRTC, cams[0].kind)
            assertEquals("${fake.address}/webcam/webrtc", cams[0].url)
            assertEquals("JPEGDATA", String(s.snapshot(cams[0])))
            // The same camera is shown live as MJPEG, on the printer's own host.
            assertEquals("${fake.address}/webcam/stream.mjpg", cams[0].liveUrl)
            assertEquals("${fake.address}/webcam/stream.h264", cams[0].videoUrl)
            val frames = s.liveStream(cams[0]).use { input -> val r = MjpegReader(input); listOfNotNull(r.next(), r.next(), r.next()) }
            assertEquals(listOf(listOf(0xFF, 0xD8, 1, 2, 3, 0xFF, 0xD9), listOf(0xFF, 0xD8, 4, 5, 0xFF, 0xD9)), frames.map { f -> f.map { it.toInt() and 0xFF } })
        }
    }

    @Test fun cameraRedirectsAreFollowedOnlyOnThePrinter() = FakeMoonraker().use { fake ->
        PaxxLanAdapter().open(config(fake.address)).use { s ->
            val cam = CameraEndpoint("w", "webcam", CameraKind.MJPEG_STREAM, "${fake.address}/webcam/redirected", liveUrl = "${fake.address}/webcam/redirected")
            assertNotNull(s.liveStream(cam).use { MjpegReader(it).next() })
            val evil = cam.copy(liveUrl = "${fake.address}/webcam/offhost")
            assertTrue(runCatching { s.liveStream(evil).close() }.exceptionOrNull()?.message.orEmpty().contains("own host"))
        }
    }

    @Test fun mjpegStreamerCamerasAreLiveToo() {
        val cams = U1Protocol.cameras(org.json.JSONArray().put(JSONObject().put("name", "webcam").put("service", "mjpegstreamer-adaptive")
            .put("stream_url", "/webcam/?action=stream").put("snapshot_url", "/webcam/?action=snapshot")))
        assertEquals(CameraKind.MJPEG_STREAM, cams.single().kind)
        assertEquals("/webcam/?action=stream", cams.single().liveUrl)
    }

    @Test fun uploadSucceedsAndReportsProgress() = FakeMoonraker().use { fake ->
        PaxxLanAdapter().open(config(fake.address)).use { s ->
            val g = File.createTempFile("job", ".gcode").apply { writeBytes(ByteArray(300_000) { 'G'.code.toByte() }); deleteOnExit() }
            var last = 0L
            val r = s.upload(g, "nozzle/cube.gcode") { sent, _ -> last = sent }
            assertEquals(UploadResult.Uploaded("cube.gcode"), r)
            assertEquals(300_000L, last)
            assertTrue("multipart body carries the whole file", fake.uploaded["cube.gcode"]!! > 300_000)
        }
    }

    @Test fun droppedUploadIsReportedAsInterruptedNotSuccess() = FakeMoonraker().use { fake ->
        fake.dropUpload = true
        PaxxLanAdapter().open(config(fake.address)).use { s ->
            val g = File.createTempFile("job", ".gcode").apply { writeBytes(ByteArray(2_000_000)); deleteOnExit() }
            val r = s.upload(g, "big.gcode")
            assertTrue("got $r", r is UploadResult.Interrupted || r is UploadResult.Failed)
            assertFalse(r is UploadResult.Uploaded)
        }
    }

    @Test fun actionsSendExactlyTheExpectedCommands() = FakeMoonraker().use { fake ->
        PaxxLanAdapter().open(config(fake.address)).use { s ->
            assertEquals(ActionOutcome.Accepted, s.perform(PrinterAction.SetNozzleTemperature(2, 220)))
            assertEquals("SET_HEATER_TEMPERATURE HEATER=extruder2 TARGET=220", fake.calls.last().query["script"])
            s.perform(PrinterAction.SetMaterialInfo(1, Material("Polymaker", "PLA", "Matte", "#112233")))
            assertEquals("SET_PRINT_FILAMENT_CONFIG CONFIG_EXTRUDER=1 VENDOR=\"Polymaker\" FILAMENT_TYPE=\"PLA\" FILAMENT_SUBTYPE=\"Matte\" FILAMENT_COLOR_RGBA=112233FF FORCE=1", fake.calls.last().query["script"])
            s.perform(PrinterAction.LoadMaterial(3))
            assertEquals("T3\nLOAD_FILAMENT", fake.calls.last().query["script"])
        }
    }

    @Test fun invalidCommandsAreRejectedWithoutContactingThePrinter() = FakeMoonraker().use { fake ->
        PaxxLanAdapter().open(config(fake.address)).use { s ->
            val before = fake.calls.size
            assertTrue(s.perform(PrinterAction.SetNozzleTemperature(4, 200)) is ActionOutcome.Rejected)
            assertTrue(s.perform(PrinterAction.SetNozzleTemperature(0, 400)) is ActionOutcome.Rejected)
            assertTrue(s.perform(PrinterAction.Jog('X', 500.0)) is ActionOutcome.Rejected)
            assertTrue(s.perform(PrinterAction.StartJob("../etc/passwd")) is ActionOutcome.Rejected)
            assertEquals(before, fake.calls.size)
        }
    }

    @Test fun missingMacroIsRejectedNotGuessed() = FakeMoonraker().use { fake ->
        fake.macros = emptyList()
        PaxxLanAdapter().open(config(fake.address)).use { s ->
            assertTrue(s.perform(PrinterAction.UnloadMaterial(0)) is ActionOutcome.Rejected)
            assertTrue(fake.calls.none { it.path == "/printer/gcode/script" })
        }
    }

    @Test fun firmwareErrorIsARejectionAndLostReplyIsUnknown() = FakeMoonraker().use { fake ->
        PaxxLanAdapter().open(config(fake.address)).use { s ->
            fake.gcodeReply = { 400 to """{"error":{"code":400,"message":"Must home axis first"}}""" }
            val r = s.perform(PrinterAction.Jog('X', 10.0))
            assertEquals(ActionOutcome.Rejected("Must home axis first"), r)
        }
        // A printer that vanishes mid-command gives Unknown, which forces a reconcile in ActionGuard.
        val dead = FakeMoonraker(); val address = dead.address; dead.close()
        PaxxLanAdapter().open(config(address)).use { s ->
            assertTrue(s.perform(PrinterAction.HomeAll) is ActionOutcome.Unknown)
            assertEquals(PrinterState.OFFLINE, s.status().state)
        }
    }

    @Test fun klipperNotReadyIsStartingOrError() = FakeMoonraker().use { fake ->
        fake.klippyState = "startup"
        PaxxLanAdapter().open(config(fake.address)).use { assertEquals(PrinterState.STARTING, it.status().state) }
        fake.klippyState = "shutdown"
        PaxxLanAdapter().open(config(fake.address)).use { assertEquals(PrinterState.ERROR, it.status().state) }
    }

    @Test fun publicAddressesMustUseHttps() {
        assertThrows(IllegalArgumentException::class.java) { MoonrakerLan("http://example.com") }
        MoonrakerLan("http://u1.tail1234.ts.net").close()
        MoonrakerLan("192.168.1.40").close()
    }
}
