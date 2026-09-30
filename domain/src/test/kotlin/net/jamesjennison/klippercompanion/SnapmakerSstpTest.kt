package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

// SnapmakerSstp's pure rules (docs/upstream/PROVENANCE.md P-0037). The reply bodies are hand-written from the fields Luban
// reads (luban/channels/SstpHttpChannel.ts, "sstp:N", and luban/types.ts:39-71 MarlinStateData); none is a capture from
// a printer. Also SnapmakerModels (which profiles go with which kind) and the capability / gate wiring of both kinds.
class SnapmakerSstpTest {
    @Test fun startAndControlsAreGated() {
        assertFalse(SnapmakerSstp.START_VERIFIED)
        assertFalse(startVerifiedFor(PrinterKind.SNAPMAKER_A_SERIES))
        val e = assertThrows(ApiFailure::class.java) { SnapmakerSstp.requireStartVerified("cube.gcode") }
        assertEquals(SnapmakerSacp.startNotVerified("cube.gcode"), e.message)
        assertTrue(e.message!!.contains("isn't verified on real hardware yet"))
        val c = assertThrows(ApiFailure::class.java) { SnapmakerSstp.requireControlVerified("stopping a print") }
        assertTrue(c.message!!.startsWith("Nothing was sent: stopping a print"))
        val caps = capabilitiesFor(PrinterKind.SNAPMAKER_A_SERIES)
        assertEquals(PrinterTransport.SNAPMAKER_SSTP, caps.transport)
        assertFalse(caps.supportsPauseResumeCancel); assertFalse(caps.supportsJog); assertFalse(caps.verifiedOnRealHardware)
        assertTrue(caps.readsPrinterState); assertTrue(caps.acceptsOnDeviceSlicedGcode)
        // The A-series reads a real state, so a send still needs an idle one.
        assertFalse("unknown" in sendAllowedStates(PrinterKind.SNAPMAKER_A_SERIES))
        assertTrue("standby" in sendAllowedStates(PrinterKind.SNAPMAKER_A_SERIES))
        // The U1 kinds are untouched.
        assertTrue(startVerifiedFor(PrinterKind.SNAPMAKER_U1))
        assertEquals(PrinterTransport.MOONRAKER, capabilitiesFor(PrinterKind.SNAPMAKER_U1_PAXX).transport)
    }

    @Test fun tokenBodyIsFormEncodedAndEmptyWithoutAToken() {
        assertEquals("", SnapmakerSstp.tokenBody(""))
        assertEquals("", SnapmakerSstp.tokenBody("  "))
        assertEquals("token=3f2a-11", SnapmakerSstp.tokenBody("3f2a-11"))
        assertEquals("token=a%26b%3Dc+d", SnapmakerSstp.tokenBody("a&b=c d"))
    }

    @Test fun headTypesFollowLuban() {
        // sstp:207-250.
        assertEquals(SnapmakerSstp.Head.SINGLE_EXTRUDER, SnapmakerSstp.head(1))
        assertEquals(SnapmakerSstp.Head.DUAL_EXTRUDER, SnapmakerSstp.head(5))
        for (t in listOf(2, 8)) assertEquals(SnapmakerSstp.Head.CNC, SnapmakerSstp.head(t))
        for (t in listOf(3, 4, 6, 7, 9)) assertEquals(SnapmakerSstp.Head.LASER, SnapmakerSstp.head(t))
        assertEquals(SnapmakerSstp.Head.PRINTING_UNKNOWN, SnapmakerSstp.head(null))
        assertEquals(SnapmakerSstp.Head.PRINTING_UNKNOWN, SnapmakerSstp.head(0))
    }

    @Test fun parsesConnectReplies() {
        val ok = SnapmakerSstp.parseConnect(200, """{"token":"tok-1","series":"A350","headType":5}""", "")
        assertEquals(SnapmakerSstp.ConnectResult.Connected("tok-1", "A350", SnapmakerSstp.Head.DUAL_EXTRUDER), ok)
        assertFalse("the token never shows in toString", ok.toString().contains("tok-1"))
        // No token in the reply: the one sent is kept (sstp:177-179).
        assertEquals(SnapmakerSstp.ConnectResult.Connected("mine", "", SnapmakerSstp.Head.PRINTING_UNKNOWN),
            SnapmakerSstp.parseConnect(203, """{"series":""}""", "mine"))
        // Waiting for the touchscreen (sstp:189-199).
        assertSame(SnapmakerSstp.ConnectResult.AwaitingApproval, SnapmakerSstp.parseConnect(204, "", ""))
        assertSame(SnapmakerSstp.ConnectResult.AwaitingApproval, SnapmakerSstp.parseConnect(200, "", "t"))
        assertSame(SnapmakerSstp.ConnectResult.AwaitingApproval, SnapmakerSstp.parseConnect(200, "{}", "t"))
        assertSame(SnapmakerSstp.ConnectResult.AwaitingApproval, SnapmakerSstp.parseConnect(200, "not json", "t"))
        assertSame(SnapmakerSstp.ConnectResult.AwaitingApproval, SnapmakerSstp.parseConnect(200, """{"series":"A250"}""", ""))
        val refused = assertThrows(ApiFailure::class.java) { SnapmakerSstp.parseConnect(403, "", "secret-token") }
        assertEquals("The Snapmaker refused the connection (HTTP 403).", refused.message)
        assertFalse(refused.message!!.contains("secret-token"))
    }

    @Test fun statesMapConservatively() {
        assertEquals("standby", SnapmakerSstp.stateFor("IDLE"))
        assertEquals("printing", SnapmakerSstp.stateFor("running"))
        assertEquals("paused", SnapmakerSstp.stateFor("Paused"))
        assertEquals("unknown", SnapmakerSstp.stateFor("stopping"))
        assertEquals("unknown", SnapmakerSstp.stateFor(""))
    }

    @Test fun parsesAPrintingDualStatus() {
        val s = SnapmakerSstp.parseStatus("""{"status":"RUNNING","nozzleTemperature":205.5,"nozzleTargetTemperature":210,
            "nozzleRightTemperature":30,"nozzleRightTargetTemperature":0,"heatedBedTemperature":59.8,"heatedBedTargetTemperature":60,
            "currentWorkNozzle":1,"fileName":"cube.gcode","currentLine":250,"totalLines":1000,"estimatedTime":3600,"elapsedTime":900}""")
        assertEquals("printing", s.state)
        assertEquals(0.25f, s.progress); assertEquals("cube.gcode", s.fileName); assertEquals(900.0, s.elapsedSeconds!!, 1e-9)
        val snap = s.snapshot()
        assertTrue(snap.ready); assertEquals("cube.gcode", snap.activeFilename); assertEquals(0.25f, snap.activeProgress)
        assertEquals(205.5, snap.nozzle!!, 1e-9); assertEquals(60.0, snap.bedTarget!!, 1e-9); assertEquals("right nozzle", snap.activeExtruder)
        assertEquals(listOf(ToolheadTemperature("Left nozzle", 205.5, 210.0), ToolheadTemperature("Right nozzle", 30.0, 0.0)), s.toolheads())
    }

    @Test fun anIdleSingleStatusHasNoJob() {
        // Luban only reports a job when currentLine, totalLines and estimatedTime are all non-zero (sstp:650-677).
        val s = SnapmakerSstp.parseStatus("""{"status":"IDLE","nozzleTemperature":24,"nozzleTargetTemperature":0,
            "heatedBedTemperature":23,"heatedBedTargetTemperature":0,"currentWorkNozzle":0,"fileName":"old.gcode","currentLine":0,"totalLines":1000}""")
        assertEquals("standby", s.state); assertEquals("", s.fileName); assertEquals(0f, s.progress); assertNull(s.elapsedSeconds)
        assertEquals("nozzle", s.snapshot().activeExtruder)
        assertEquals(listOf(ToolheadTemperature("Nozzle", 24.0, 0.0)), s.toolheads())
        assertNull(s.rightNozzle)
    }

    @Test fun emptyStatusMeansConnectFirst() {
        for (body in listOf("", "{}", "[]", "garbage", """{"nozzleTemperature":20}""")) {
            val e = assertThrows(body, ApiFailure::class.java) { SnapmakerSstp.parseStatus(body) }
            assertEquals(SnapmakerSstp.NEEDS_CONNECT, e.message)
        }
    }

    @Test fun uploadProblemsNameTheFile() {
        assertNull(SnapmakerSstp.uploadProblem(200, "a.gcode"))
        assertNull(SnapmakerSstp.uploadProblem(204, "a.gcode"))
        assertEquals("The Snapmaker refused the upload of a.gcode (HTTP 401). Tap Connect in Edit printer and accept on the touchscreen.",
            SnapmakerSstp.uploadProblem(401, "a.gcode"))
        assertEquals("Could not upload a.gcode to the Snapmaker (HTTP 500).", SnapmakerSstp.uploadProblem(500, "a.gcode"))
        assertEquals("a.gcode", SnapmakerSstp.safeFileName("dir/a.gcode"))
    }

    @Test fun eachKindIsGatedToItsCatalogueModels() {
        assertEquals(16, SnapmakerModels.A_SERIES.size)
        assertEquals(setOf(SlicingPrinterModel.SNAPMAKER_J1, SlicingPrinterModel.SNAPMAKER_ARTISAN), SnapmakerModels.SACP)
        assertTrue(SnapmakerModels.A_SERIES.intersect(SnapmakerModels.SACP).isEmpty())
        for (m in SnapmakerModels.A_SERIES) {
            assertNull(SnapmakerModels.connectionProblem(m, PrinterKind.SNAPMAKER_A_SERIES))
            assertTrue(SnapmakerModels.connectionProblem(m, PrinterKind.SNAPMAKER_SACP)!!.startsWith("This is a Snapmaker 2.0 profile."))
        }
        for (m in SnapmakerModels.SACP) {
            assertNull(SnapmakerModels.connectionProblem(m, PrinterKind.SNAPMAKER_SACP))
            assertTrue(SnapmakerModels.connectionProblem(m, PrinterKind.SNAPMAKER_A_SERIES)!!.startsWith("This is a Snapmaker J1 / Artisan profile."))
        }
        assertNull(SnapmakerModels.connectionProblem(null, PrinterKind.SNAPMAKER_SACP))
        assertNull(SnapmakerModels.connectionProblem(null, PrinterKind.SNAPMAKER_A_SERIES))
        val other = SlicingPrinterModel.PRUSA_MK4S
        assertEquals("A Snapmaker J1 or Artisan connection needs the Snapmaker J1 or Snapmaker Artisan slicing profile.",
            SnapmakerModels.connectionProblem(other, PrinterKind.SNAPMAKER_SACP))
        assertEquals("A Snapmaker 2.0 connection needs a Snapmaker A250 or A350 slicing profile (any kit variant).",
            SnapmakerModels.connectionProblem(other, PrinterKind.SNAPMAKER_A_SERIES))
        assertNull(SnapmakerModels.modelsFor(PrinterKind.SNAPMAKER_U1))
        // ElegooProfiles.connectionProblem delegates to SnapmakerModels for these kinds.
        assertEquals(SnapmakerModels.connectionProblem(other, PrinterKind.SNAPMAKER_SACP), ElegooProfiles.connectionProblem(other, PrinterKind.SNAPMAKER_SACP))
    }
}
