package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

// Known-answer vectors, and where each comes from (docs/upstream/PROVENANCE.md P-0037). None is a capture from a printer.
// - Packets A-F and the three standalone checksum values were computed with a line-by-line Python transliteration of the
//   SACP SDK 0.1.1's own code (dist/helper.js calcCRC8 / calcChecksum, dist/communication/Header.js toBuffer and
//   dist/communication/Packet.js toBuffer), not with the SDK itself and not with the Kotlin under test:
//   A = Luban's wifiConnectionClose (0x01/0x06 to the screen, seq 1, luban/SacpClient.ts:1320),
//   B = the heartbeat subscribe (0x01/0x00 to the controller, seq 2, [0x01, 0xa0, 1000 u16], sdk/communication/Dispatcher.js:213-219),
//   C = the hello (0x01/0x05 to the screen, seq 1, strings "phone", "Nozzle It All", "", sdk/models/WifiConnectionInfo.js:13-19),
//   D = the ACK (result 0) to a BYE the screen sent with seq 7 (sdk/communication/Dispatcher.js:175-191),
//   E = getMachineInfo (0x01/0x21 to the controller, seq 3, luban/SacpClient.ts:599-603),
//   F = the hello heartbeat (0xb0/0x0b to the screen, seq 5, luban/SacpClient.ts:1314-1318).
// - The payload fixtures (extruder, bed, machine info, file info, chunk request, upload start) were packed with Python's
//   struct module from the field layouts in the SDK's models (cited at each test), little-endian as sdk/helper.js.
class SnapmakerSacpTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    // ---- gate ---------------------------------------------------------------------------------------------------------

    @Test fun startAndControlsAreGatedWithTheSharedWording() {
        assertFalse(SnapmakerSacp.START_VERIFIED)
        assertFalse(startVerifiedFor(PrinterKind.SNAPMAKER_SACP))
        val start = assertThrows(ApiFailure::class.java) { SnapmakerSacp.requireStartVerified("cube.gcode") }.message!!
        assertEquals("Uploaded cube.gcode to the printer but did not start it: starting a print on a Snapmaker printer from Nozzle It All isn't verified on real hardware yet. Start it from the printer's screen.", start)
        assertTrue(SnapmakerSacp.startNotVerified("a.gcode", uploaded = false).startsWith("Did not start a.gcode: "))
        val control = assertThrows(ApiFailure::class.java) { SnapmakerSacp.requireControlVerified("pausing a print") }.message!!
        assertEquals("Nothing was sent: pausing a print on a Snapmaker printer from Nozzle It All isn't verified on real hardware yet. Use the printer's screen.", control)
        SnapmakerSacp.requireStartVerified("x", verified = true); SnapmakerSacp.requireControlVerified("x", verified = true)
        val c = capabilitiesFor(PrinterKind.SNAPMAKER_SACP)
        assertEquals(PrinterTransport.SNAPMAKER_SACP, c.transport)
        assertFalse(c.supportsPauseResumeCancel); assertFalse(c.verifiedOnRealHardware); assertFalse(c.readsPrinterState)
        assertTrue(c.acceptsOnDeviceSlicedGcode)
        assertTrue("upload-only while gated", "unknown" in sendAllowedStates(PrinterKind.SNAPMAKER_SACP))
    }

    // ---- checksums ----------------------------------------------------------------------------------------------------

    @Test fun checksumsMatchTheSdkTransliteration() {
        assertEquals(0x76, SnapmakerSacp.crc8(hex("aa5508000102"), 0, 6))
        assertEquals(0xFEFA, SnapmakerSacp.checksum(hex("010203"), 0, 3)) // the odd last byte is added as the low byte
        assertEquals(0xFFFF, SnapmakerSacp.checksum(ByteArray(0), 0, 0))
    }

    // ---- encode -------------------------------------------------------------------------------------------------------

    @Test fun encodesTheKnownAnswerPackets() {
        val s = SnapmakerSacp
        assertEquals("aa550800010276000001000106f9fd", s.encode(s.request(s.BYE, s.PEER_SCREEN, 1)).hex())
        assertEquals("aa550c0001012700000200010001a0e8035c13", s.encode(s.request(s.SUBSCRIBE, s.PEER_CONTROLLER, 2, s.subscribePayload(s.HEARTBEAT))).hex())
        assertEquals("aa552000010208000001000105050070686f6e650d004e6f7a7a6c6520497420416c6c00009f8e",
            s.encode(s.request(s.HELLO, s.PEER_SCREEN, 1, s.helloPayload("phone", "Nozzle It All", ""))).hex())
        val bye = SnapmakerSacp.Packet(s.PEER_LUBAN, s.PEER_SCREEN, s.ATTR_REQUEST, 7, 0x01, 0x06)
        assertEquals("aa55090001026000010700010600f8f7", s.encode(s.ackTo(bye, s.responsePayload(0))).hex())
        assertEquals("aa55080001017f000003000121defb", s.encode(s.request(s.MACHINE_INFO, s.PEER_CONTROLLER, 3)).hex())
        assertEquals("aa55080001027600000500b00bf44a", s.encode(s.request(s.HELLO_HEARTBEAT, s.PEER_SCREEN, 5)).hex())
    }

    @Test fun sequenceWrapsBeforeFfff() {
        assertEquals(1, SnapmakerSacp.nextSequence(0))
        assertEquals(0xFFFE, SnapmakerSacp.nextSequence(0xFFFD))
        assertEquals(0, SnapmakerSacp.nextSequence(0xFFFE)) // sdk/communication/Communication.js:69-73
    }

    // ---- decode -------------------------------------------------------------------------------------------------------

    @Test fun decodesSplitAndBackToBackPackets() {
        val a = hex("aa550800010276000001000106f9fd"); val d = hex("aa55090001026000010700010600f8f7")
        val stream = a + d
        val decoder = SnapmakerSacp.Decoder()
        assertTrue(decoder.feed(stream, 0, 10).isEmpty())
        assertEquals(10, decoder.pending)
        val out = decoder.feed(stream, 10, stream.size - 10)
        assertEquals(2, out.size)
        assertEquals(SnapmakerSacp.BYE, out[0].command); assertFalse(out[0].isAck); assertEquals(1, out[0].sequence); assertEquals(2, out[0].receiver)
        assertTrue(out[1].isAck); assertEquals(7, out[1].sequence); assertArrayEquals(byteArrayOf(0), out[1].payload)
        assertEquals(0, decoder.pending)
    }

    @Test fun decoderSkipsNoiseBadHeadersAndBadChecksums() {
        val good = hex("aa55080001017f000003000121defb")
        val badSum = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val badCrc = good.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        val out = SnapmakerSacp.Decoder().feed(hex("0011aa") + badCrc + badSum + good)
        assertEquals(1, out.size)
        assertEquals(SnapmakerSacp.MACHINE_INFO, out[0].command)
        assertEquals(3, out[0].sequence)
    }

    @Test fun roundTripsARequestWithPayload() {
        val p = SnapmakerSacp.request(SnapmakerSacp.UPLOAD_START, SnapmakerSacp.PEER_SCREEN, 300, SnapmakerSacp.uploadStartPayload("a.gcode", 10, "00"))
        assertEquals(listOf(p), SnapmakerSacp.Decoder().feed(SnapmakerSacp.encode(p)))
    }

    // ---- payloads -----------------------------------------------------------------------------------------------------

    @Test fun uploadPayloadsFollowLuban() {
        val md5 = "0123456789abcdef0123456789abcdef"
        // luban/SacpClient.ts:988-993: name, u32 length, u16 chunks (ceil(61441 / 61440) = 2), md5.
        assertEquals("0a00637562652e67636f646501f00000020020003031323334353637383961626364656630313233343536373839616263646566",
            SnapmakerSacp.uploadStartPayload("cube.gcode", 61441, md5).hex())
        assertEquals(0, SnapmakerSacp.chunkCount(0)); assertEquals(1, SnapmakerSacp.chunkCount(61440)); assertEquals(2, SnapmakerSacp.chunkCount(61441))
        assertThrows(IllegalArgumentException::class.java) { SnapmakerSacp.uploadStartPayload("big.gcode", 0xFFFFL * 61440 + 1, md5) }
        // luban/SacpClient.ts:936-937.
        assertEquals(SnapmakerSacp.ChunkRequest(md5, 3), SnapmakerSacp.parseChunkRequest(hex("200030313233343536373839616263646566303132333435363738396162636465660300")))
        // luban/SacpClient.ts:947-955: [0, md5, index, chunk as a length-prefixed string].
        assertEquals("00" + "2000" + md5.toByteArray().hex() + "0300" + "0200" + "abcd", SnapmakerSacp.chunkPayload(md5, 3, hex("abcd")).hex())
        assertEquals("c8", SnapmakerSacp.chunkErrorPayload().hex())
        assertEquals(0, SnapmakerSacp.parseUploadResult(byteArrayOf(0)))
        assertEquals("5d41402abc4b2a76b9719d911017c592", SnapmakerSacp.md5Hex("hello".byteInputStream()))
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("cube.gcode", SnapmakerSacp.safeFileName("/sdcard/x/cube.gcode"))
        assertEquals("cube.gcode", SnapmakerSacp.safeFileName("..\\cube\u0001.gcode"))
        assertThrows(ApiFailure::class.java) { SnapmakerSacp.safeFileName("..") }
        assertThrows(ApiFailure::class.java) { SnapmakerSacp.safeFileName("a/") }
        assertThrows(ApiFailure::class.java) { SnapmakerSacp.safeFileName("x".repeat(129)) }
    }

    // ---- parsers ------------------------------------------------------------------------------------------------------

    @Test fun parsesMachineInfoAndFileInfo() {
        // sdk/models/MachineInfo.js:22-28: type u8, hardware u8, serial u32, firmware string.
        assertEquals(SnapmakerSacp.MachineInfo(4, 2, 12345678L, "1.2.3"), SnapmakerSacp.parseMachineInfo(hex("04024e61bc000500312e322e33")))
        assertEquals("J1", SnapmakerSacp.machineTypeName(4)); assertEquals("type 9", SnapmakerSacp.machineTypeName(9))
        // luban/SacpClient.ts:212-217.
        assertEquals(SnapmakerSacp.FileInfo("cube.gcode", 2000, 3600), SnapmakerSacp.parseFileInfo(hex("0a00637562652e67636f6465d0070000100e0000")))
    }

    @Test fun appliesNotificationsToTheStatus() {
        val s = SnapmakerSacp
        var st = s.Status()
        assertTrue(s.heartbeatStale(st, 1_000))
        // sdk/models/ExtruderInfo.js:12-39: key 0, one extruder, status 1, 0.4 mm, 210.5 / 215.0 C.
        st = s.apply(st, s.NOZZLES, hex("00" + "000100010001009001000044360300d8470300"), 10)
        assertEquals(210.5, st.left!!.temperature, 1e-9); assertEquals(215.0, st.left!!.target, 1e-9); assertEquals(0.4, st.left!!.diameter, 1e-9)
        assertNull(st.right); assertEquals(0, st.workNozzle)
        // sdk/models/GetHotBed.js:19-28: zone 0, 60.0 C, target int16 60.
        st = s.apply(st, s.BED, hex("00" + "00010060ea00003c00"), 20)
        assertEquals(60.0, st.bedTemperature!!, 1e-9); assertEquals(60.0, st.bedTarget!!, 1e-9)
        st = s.apply(st, s.HEARTBEAT, hex("0001"), 30)
        assertEquals(1, st.heartbeatKey); assertEquals(30L, st.lastHeartbeatAt)
        assertFalse(s.heartbeatStale(st, 30 + s.HEARTBEAT_TIMEOUT_MS)); assertTrue(s.heartbeatStale(st, 31 + s.HEARTBEAT_TIMEOUT_MS))
        st = s.apply(st, s.CURRENT_LINE, hex("00e8030000"), 40)
        st = s.apply(st, s.PRINTING_TIME, hex("003c000000"), 50)
        assertEquals(1000L, st.currentLine); assertEquals(60L, st.printingSeconds)
        assertEquals(0f, st.progress) // no total yet
        st = st.copy(fileInfo = s.FileInfo("cube.gcode", 2000, 3600))
        assertEquals(0.5f, st.progress)
        val snap = st.snapshot(ready = true)
        assertEquals("unknown", snap.state); assertEquals("", snap.activeFilename) // never shown as printing without a state
        assertEquals(210.5, snap.nozzle!!, 1e-9); assertEquals(60.0, snap.bed!!, 1e-9); assertEquals(60.0, snap.printDuration!!, 1e-9)
        assertEquals("left nozzle", snap.activeExtruder)
        assertEquals(listOf(ToolheadTemperature("Left nozzle", 210.5, 215.0)), st.toolheads())
        // Short or unrelated data changes nothing.
        assertEquals(st, s.apply(st, s.BED, hex("00"), 60))
        assertEquals(st, s.apply(st, s.MACHINE_INFO, hex("00"), 60))
    }

    @Test fun twoExtrudersPickTheWorkingNozzle() {
        val s = SnapmakerSacp
        fun e(i: Int, status: Int) = "%02x".format(i) + "01" + "00" + "%02x".format(status) + "00" + "90010000" + "44360300" + "d8470300"
        val st = s.apply(s.Status(), s.NOZZLES, hex("00" + "0002" + e(0, 0) + e(1, 1)), 1)
        assertEquals(0, st.left!!.index); assertEquals(1, st.right!!.index); assertEquals(1, st.workNozzle)
        assertEquals("right nozzle", st.snapshot(true).activeExtruder)
        assertEquals(2, st.toolheads().size)
    }

    @Test fun negativeValuesAreSigned() {
        val st = SnapmakerSacp.apply(SnapmakerSacp.Status(), SnapmakerSacp.BED, hex("00" + "00010078ecffffffff"), 1)
        assertEquals(-5.0, st.bedTemperature!!, 1e-9); assertEquals(-1.0, st.bedTarget!!, 1e-9)
    }
}
