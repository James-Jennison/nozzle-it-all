package com.nozzleitall.adapter.elegoo

import com.nozzleitall.printer.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.random.Random

/** The Centauri Carbon 2 session against an in-process MQTT broker playing the printer. Never a real printer. */
class Cc2SessionTest {
    private val identity = PrinterIdentity("cc2", "Garage", "Elegoo Centauri Carbon 2", PrinterFamily.ELEGOO, "127.0.0.1")
    private val sn = "CC2A0001B2C3"

    private fun session(b: FakeBroker, code: String = "", serial: String? = sn, discoveryPort: Int = 9, httpPort: Int = 9) =
        Cc2Session(identity, ElegooLanAdapter.CC2_CAPABILITIES, ElegooHost("127.0.0.1", null), serial, code, mqttPort = b.port, httpPort = httpPort,
            discoveryPort = discoveryPort, timeouts = Fakes.FAST)

    @Test fun connectsRegistersAndReadsStatusAndSlots() = FakeBroker().use { b ->
        session(b).use { s ->
            val st = s.status()
            assertEquals(PrinterState.PRINTING, st.state)
            assertEquals("benchy.gcode", st.job!!.fileName); assertEquals(0.25f, st.job!!.fraction, 0.0001f)
            assertEquals(4, st.toolheads.size)
            assertEquals(listOf(true, false, true, false), st.toolheads.map { it.loaded })
            assertEquals("#C0C0C0", st.toolheads[0].material!!.colorHex); assertEquals("Silk", st.toolheads[0].material!!.subType)
            assertEquals(214.5, st.toolheads[0].nozzleTemperature!!, 0.0)
            // elegoo-link's connection: user "elegoo", default access code, "1_PC_nnnn" client, its topics and registration.
            assertEquals("elegoo", b.connectUser); assertEquals("123456", b.connectPassword)
            val client = b.clientId!!
            assertTrue(client, Regex("1_PC_\\d{4}").matches(client))
            assertEquals(setOf("elegoo/$sn/$client/api_response", "elegoo/$sn/api_status", "elegoo/$sn/${client}_req/register_response"), b.subscriptions.toSet())
            val reg = b.published.first { it.topic == "elegoo/$sn/api_register" }.payload
            assertTrue(JSONObject().put("client_id", client).put("request_id", client + "_req").similar(reg))
            assertEquals(1, b.requests(Cc2.METHOD_STATUS).size)
            assertEquals("slots came with the status", 0, b.requests(Cc2.METHOD_CANVAS).size)
        }
    }

    @Test fun slotsAreAskedForWhenTheStatusHasNone() = FakeBroker().use { b ->
        b.status = fixture("cc2_status.json").apply { getJSONObject("result").remove("canvas_info") }
        b.canvasReply = JSONObject().put("method", Cc2.METHOD_CANVAS).put("result", JSONObject().put("error_code", 0).put("canvas_info", fixture("cc2_status.json").getJSONObject("result").getJSONObject("canvas_info")))
        session(b).use { s -> assertEquals(4, s.status().toolheads.size) }
        assertEquals(1, b.requests(Cc2.METHOD_CANVAS).size)
    }

    @Test fun aWrongAccessCodeNeedsAttention() = FakeBroker(password = "654321").use { b ->
        session(b, code = "111111").use { s ->
            val st = s.status()
            assertEquals(PrinterState.ERROR, st.state); assertEquals("The printer rejected the access code.", st.message)
        }
        FakeBroker(password = "654321").use { b2 -> session(b2, code = "654321").use { assertEquals(PrinterState.PRINTING, it.status().state) } }
    }

    @Test fun tooManyClientsIsExplained() = FakeBroker().use { b ->
        b.registrationError = "too many clients"
        session(b).use { s -> val st = s.status(); assertEquals(PrinterState.ERROR, st.state); assertTrue(st.message!!.contains("as many connections")) }
    }

    @Test fun startSendsExactlyElegooLinksRequestWithTheSlotMap() = FakeBroker().use { b ->
        session(b).use { s ->
            s.status()
            assertEquals(ActionOutcome.Accepted, s.perform(PrinterAction.StartJob("benchy.gcode", listOf(2, 0))))
            val sent = b.requests(Cc2.METHOD_START_PRINT).single()
            assertTrue(sent.toString(), JSONObject("""{"id":${sent.getInt("id")},"method":1020,"params":{"storage_media":"local","filename":"benchy.gcode",
                "config":{"delay_video":false,"printer_check":false,"print_layout":"A","bedlevel_force":false,
                "slot_map":[{"t":0,"canvas_id":0,"tray_id":2},{"t":1,"canvas_id":0,"tray_id":0}]}}}""").similar(sent))
            assertEquals("elegoo/$sn/${b.clientId}/api_request", b.published.last { it.payload.optInt("method") == 1020 }.topic)
        }
    }

    @Test fun errorCodesAreRefusalsSilenceAndDropsAreUnknown() {
        FakeBroker().use { b ->
            b.commandCode = 1009
            session(b).use { s -> assertEquals(ActionOutcome.Rejected("The printer is busy."), s.perform(PrinterAction.Cancel)) }
        }
        FakeBroker().use { b ->
            b.commandCode = null
            session(b).use { s -> assertTrue(s.perform(PrinterAction.Cancel) is ActionOutcome.Unknown) }
            assertEquals("never retried", 1, b.requests(Cc2.METHOD_STOP).size)
        }
        FakeBroker().use { b ->
            b.commandCode = -1
            session(b).use { s -> assertTrue(s.perform(PrinterAction.Cancel) is ActionOutcome.Unknown) }
        }
    }

    @Test fun pauseAndResumeAreNotOfferedAndNeverSent() = FakeBroker().use { b ->
        assertFalse(ElegooLanAdapter.CC2_CAPABILITIES.pausePrint); assertFalse(ElegooLanAdapter.CC2_CAPABILITIES.resumePrint)
        session(b).use { s ->
            s.status()
            val before = b.published.size
            assertTrue(s.perform(PrinterAction.Pause) is ActionOutcome.Rejected)
            assertTrue(s.perform(PrinterAction.Resume) is ActionOutcome.Rejected)
            assertTrue(s.perform(PrinterAction.StartJob("benchy.gcode", listOf(1))) is ActionOutcome.Rejected) // slot 2 is empty
            assertEquals(before, b.published.size)
        }
    }

    @Test fun statusEventsKeepTheReadingCurrentWhenTheFullStatusGoesUnanswered() = FakeBroker().use { b ->
        session(b).use { s ->
            s.status()
            b.status = null
            b.publish("elegoo/$sn/api_status", JSONObject("""{"id":5,"method":6000,"result":{"extruder":{"temperature":230.0},"machine_status":{"status":2,"sub_status":2502}}}"""))
            Thread.sleep(200)
            val st = s.status()
            assertEquals(PrinterState.PAUSED, st.state)
            assertEquals(230.0, st.toolheads[0].nozzleTemperature!!, 0.0); assertEquals(215.0, st.toolheads[0].nozzleTarget!!, 0.0)
        }
    }

    @Test fun theSerialComesFromDiscoveryWhenNotSaved() = FakeBroker().use { b ->
        FakeUdpResponder(fixture("cc2_discovery.json").toString()).use { udp ->
            session(b, serial = null, discoveryPort = udp.port).use { s ->
                assertEquals(PrinterState.PRINTING, s.status().state)
                assertEquals(sn, s.serial)
                assertEquals(0, JSONObject(udp.asked.single()).getInt("id")); assertEquals(7000, JSONObject(udp.asked.single()).getInt("method"))
            }
        }
    }

    @Test fun uploadPutsRangedPiecesWithTheAccessCode() {
        val f = Files.createTempFile("cc2", ".gcode").toFile().apply { writeBytes(Random(3).nextBytes(1024 * 1024 + 500)); deleteOnExit() }
        MockWebServer().use { http ->
            repeat(2) { http.enqueue(MockResponse().setBody("""{"error_code":0}""")) }
            http.start()
            FakeBroker().use { b ->
                val r = session(b, code = "246810", httpPort = http.port).use { it.upload(f, "benchy.gcode") }
                assertEquals(UploadResult.Uploaded("benchy.gcode"), r)
            }
            val a = http.takeRequest(); val c = http.takeRequest()
            assertEquals("PUT", a.method); assertEquals("/upload", a.path)
            assertEquals("bytes 0-1048575/${f.length()}", a.getHeader("Content-Range"))
            assertEquals("bytes 1048576-${f.length() - 1}/${f.length()}", c.getHeader("Content-Range"))
            listOf(a, c).forEach { assertEquals("246810", it.getHeader("X-Token")); assertEquals(ElegooNet.md5Hex(f), it.getHeader("X-File-MD5")); assertEquals("benchy.gcode", it.getHeader("X-File-Name")) }
            assertArrayEquals(f.readBytes(), a.body.readByteArray() + c.body.readByteArray())
        }
        MockWebServer().use { http ->
            http.enqueue(MockResponse().setBody("""{"error_code":1000}"""))
            http.start()
            FakeBroker().use { b ->
                val r = session(b, httpPort = http.port).use { it.upload(f, "benchy.gcode") }
                assertEquals(UploadResult.Failed("The printer rejected the access code."), r)
            }
            assertEquals(1, http.requestCount)
        }
    }
}
