package com.nozzleitall.adapter.elegoo

import com.nozzleitall.printer.*
import okhttp3.MultipartReader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** The Centauri Carbon session against an in-process SDCP printer (MockWebServer's WebSocket). Never a real printer. */
class SdcpSessionTest {
    private val identity = PrinterIdentity("cc", "Centauri", "Elegoo Centauri Carbon", PrinterFamily.ELEGOO, "127.0.0.1")
    private val board = "000000000001d354"

    private fun session(p: FakeSdcpPrinter, mainboard: String? = board, discoveryPort: Int = 9) =
        SdcpSession(identity, ElegooLanAdapter.CC_CAPABILITIES, ElegooHost("127.0.0.1", null), mainboard, wsPort = p.port, httpPort = p.port,
            discoveryPort = discoveryPort, timeouts = Fakes.FAST)

    @Test fun statusAndCanvasSlotsMapOntoTheSharedModel() = FakeSdcpPrinter().use { p ->
        session(p).use { s ->
            val st = s.status()
            assertEquals(PrinterState.PRINTING, st.state)
            assertEquals("cube.gcode", st.job!!.fileName); assertEquals(0.42f, st.job!!.fraction, 0.0001f)
            assertEquals(60.0, st.bed!!.target!!, 0.0)
            assertEquals(4, st.toolheads.size)
            assertEquals(listOf("#FF0000", "#00FF00", null, "#0000FF"), st.toolheads.map { it.material?.colorHex })
            assertEquals(219.6, st.toolheads[1].nozzleTemperature!!, 0.0); assertTrue(st.toolheads[1].active)
            // Reads only: Cmd 0 and Cmd 324, addressed to the mainboard, From 1.
            assertEquals(setOf(Sdcp.CMD_STATUS, Sdcp.CMD_CANVAS), p.requests.map { it.getJSONObject("Data").getInt("Cmd") }.toSet())
            p.requests.forEach { assertEquals(board, it.getString("Id")); assertEquals(board, it.getJSONObject("Data").getString("MainboardID")); assertEquals(1, it.getJSONObject("Data").getInt("From")) }
        }
    }

    @Test fun startSendsExactlyElegooLinksCommandWithTheSlotMap() = FakeSdcpPrinter().use { p ->
        session(p).use { s ->
            s.status()
            assertEquals(ActionOutcome.Accepted, s.perform(PrinterAction.StartJob("cube.gcode", listOf(3, 0))))
            val sent = p.commands(Sdcp.CMD_START_PRINT).single()
            assertTrue(sent.getJSONObject("Data").getJSONObject("Data").toString(), JSONObject("""{"Filename":"cube.gcode","StartLayer":0,"Calibration_switch":0,"PrintPlatformType":0,"Tlp_Switch":0,
                "slot_map":[{"t":0,"canvas_id":0,"tray_id":3},{"t":1,"canvas_id":0,"tray_id":0}]}""").similar(sent.getJSONObject("Data").getJSONObject("Data")))
            assertEquals("", sent.getString("Topic")); assertEquals(board, sent.getString("Id"))
        }
    }

    @Test fun aNonZeroAckIsTheprinterRefusing() = FakeSdcpPrinter().use { p ->
        p.commandAck = 1
        session(p).use { s ->
            val r = s.perform(PrinterAction.Pause)
            assertTrue(r is ActionOutcome.Rejected); assertEquals("The printer is busy.", (r as ActionOutcome.Rejected).reason)
        }
    }

    @Test fun noReplyOrALostConnectionIsUnknownAndNotRetried() {
        FakeSdcpPrinter().use { p ->
            p.commandAck = null
            session(p).use { s -> assertTrue(s.perform(PrinterAction.Cancel) is ActionOutcome.Unknown) }
            assertEquals("sent once, never retried", 1, p.commands(Sdcp.CMD_STOP).size)
        }
        FakeSdcpPrinter().use { p ->
            p.commandAck = -1
            session(p).use { s -> assertTrue(s.perform(PrinterAction.Resume) is ActionOutcome.Unknown) }
            assertEquals(1, p.commands(Sdcp.CMD_RESUME).size)
        }
    }

    @Test fun invalidOrUnsupportedRequestsNeverLeave() = FakeSdcpPrinter().use { p ->
        session(p).use { s ->
            s.status()
            val before = p.requests.size
            assertTrue("empty slot", s.perform(PrinterAction.StartJob("cube.gcode", listOf(2))) is ActionOutcome.Rejected)
            assertTrue("no such slot", s.perform(PrinterAction.StartJob("cube.gcode", listOf(7))) is ActionOutcome.Rejected)
            assertTrue(s.perform(PrinterAction.StartJob("../x.gcode")) is ActionOutcome.Rejected)
            assertTrue(s.perform(PrinterAction.HomeAll) is ActionOutcome.Rejected)
            assertTrue(s.perform(PrinterAction.SetBedTemperature(60)) is ActionOutcome.Rejected)
            assertTrue(s.perform(PrinterAction.UploadAndStart("/tmp/x.gcode", "x.gcode")) is ActionOutcome.Rejected)
            assertEquals(before, p.requests.size)
        }
    }

    @Test fun anUnreachablePrinterIsOfflineAndCommandsAreNotSent() {
        val port = FakeSdcpPrinter().use { it.port } // closed now
        val s = SdcpSession(identity, ElegooLanAdapter.CC_CAPABILITIES, ElegooHost("127.0.0.1", null), board, wsPort = port, httpPort = port, discoveryPort = 9, timeouts = Fakes.FAST)
        s.use {
            assertEquals(PrinterState.OFFLINE, it.status().state)
            val r = it.perform(PrinterAction.Pause)
            assertTrue(r is ActionOutcome.Rejected); assertTrue((r as ActionOutcome.Rejected).reason.contains("Nothing was sent"))
        }
    }

    @Test fun theMainboardIdComesFromDiscoveryWhenNotSaved() = FakeSdcpPrinter().use { p ->
        FakeUdpResponder(fixture("sdcp_discovery.json").toString()).use { udp ->
            session(p, mainboard = null, discoveryPort = udp.port).use { s ->
                assertEquals(PrinterState.PRINTING, s.status().state)
                assertEquals(listOf("M99999"), udp.asked)
                assertEquals(board, s.mainboardId)
                assertEquals(board, p.requests.first().getString("Id"))
            }
        }
    }

    private fun parts(r: RecordedRequest): Map<String, Pair<String?, ByteArray>> {
        val boundary = r.getHeader("Content-Type")!!.substringAfter("boundary=")
        val out = LinkedHashMap<String, Pair<String?, ByteArray>>()
        MultipartReader(Buffer().write(r.body.readByteArray()), boundary).use { reader ->
            while (true) {
                val part = reader.nextPart() ?: break
                val cd = part.headers["Content-Disposition"]!!
                val name = Regex("name=\"([^\"]+)\"").find(cd)!!.groupValues[1]
                out[name] = Regex("filename=\"([^\"]+)\"").find(cd)?.groupValues?.get(1) to part.body.readByteArray()
            }
        }
        return out
    }

    private fun file(size: Int): File = Files.createTempFile("cc", ".gcode").toFile().apply { writeBytes(Random(7).nextBytes(size)); deleteOnExit() }

    @Test fun uploadSendsOneMegabytePiecesWithTheWholeFilesMd5() = FakeSdcpPrinter().use { p ->
        val f = file(2 * 1024 * 1024 + 12345)
        val seen = ArrayList<Long>()
        val r = session(p).use { it.upload(f, "cube.gcode") { sent, _ -> seen += sent } }
        assertEquals(UploadResult.Uploaded("cube.gcode"), r)
        assertEquals(3, p.uploads.size)
        val all = p.uploads.map(::parts)
        assertEquals(listOf("0", "1048576", "2097152"), all.map { String(it.getValue("Offset").second) })
        all.forEach { m ->
            assertEquals("1", String(m.getValue("Check").second))
            assertEquals(ElegooNet.md5Hex(f), String(m.getValue("S-File-MD5").second))
            assertEquals(f.length().toString(), String(m.getValue("TotalSize").second))
            assertEquals("cube.gcode", m.getValue("File").first)
        }
        assertEquals("one Uuid for the whole file", 1, all.map { String(it.getValue("Uuid").second) }.toSet().size)
        assertArrayEquals(f.readBytes(), all.map { it.getValue("File").second }.reduce { a, b -> a + b })
        assertEquals(listOf(1048576L, 2097152L, f.length()), seen)
        assertEquals("32-hex lower case", Regex("[0-9a-f]{32}").matches(ElegooNet.md5Hex(f)), true)
    }

    @Test fun uploadRefusalsAndDropsAreReportedHonestly() {
        FakeSdcpPrinter().use { p ->
            p.uploadReply = { MockResponse().setBody("""{"code":"111111","messages":[{"field":"common_field","message":-3}],"data":null,"success":false}""") }
            val r = session(p).use { it.upload(file(1000), "a.gcode") }
            assertTrue(r is UploadResult.Failed); assertTrue((r as UploadResult.Failed).reason.contains("couldn't open"))
        }
        FakeSdcpPrinter().use { p ->
            p.uploadReply = { i -> if (i == 0) MockResponse().setBody("""{"code":"000000"}""") else MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            val r = session(p).use { it.upload(file(1024 * 1024 + 10), "a.gcode") }
            assertTrue("$r", r is UploadResult.Interrupted)
            assertEquals("never retried", 2, p.uploads.size)
        }
    }
}
