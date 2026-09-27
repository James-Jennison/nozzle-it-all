package com.nozzleitall.adapter.octoprint

import com.nozzleitall.printer.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OctoPrintAdapterTest {
    private fun server(jobState: String = "Printing", commandPolicy: SocketPolicy? = null) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/printer") -> MockResponse().setBody("""{"state":{"flags":{"operational":true,"printing":${jobState == "Printing"},"paused":${jobState == "Paused"}}},"temperature":{"tool0":{"actual":210.2,"target":210},"bed":{"actual":60,"target":60}}}""")
                request.path == "/api/job" && request.method == "GET" -> MockResponse().setBody("""{"job":{"file":{"name":"cube.gcode","display":"cube.gcode"}},"progress":{"completion":42.0,"printTime":600}}""")
                request.path == "/api/job" && request.method == "POST" -> commandPolicy?.let { MockResponse().setSocketPolicy(it) } ?: MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(404)
            }
        }
        start()
    }

    private fun open(s: MockWebServer) = OctoPrintAdapter().open(PrinterConfig(PrinterIdentity("o", "Octo", "Ender 3", PrinterFamily.OCTOPRINT, s.url("/").toString()), OctoPrintAdapter.ID, "key"))

    @Test fun statusMapsOntoTheSharedModel() = server().use { s ->
        val st = open(s).status()
        assertEquals(PrinterState.PRINTING, st.state)
        assertEquals("cube.gcode", st.job!!.fileName)
        assertEquals(0.42f, st.job!!.fraction, 0.001f)
        assertEquals(60.0, st.bed!!.target!!, 0.0)
        assertEquals("key", s.takeRequest().getHeader("X-Api-Key"))
    }

    @Test fun capabilitiesAreHonestAboutWhatTheClientCanDo() {
        val c = OctoPrintAdapter.CAPABILITIES
        assertTrue(c.uploadAndStart && c.pausePrint && c.cancelPrint)
        assertFalse("no camera in this client", c.camera)
        assertFalse(c.uploadJob); assertFalse(c.vendorCloud); assertFalse(c.motion)
    }

    @Test fun supportedActionIsSentOnceAndUnsupportedOnesNeverLeave() = server().use { s ->
        val session = open(s)
        assertEquals(ActionOutcome.Accepted, session.perform(PrinterAction.Pause))
        val before = s.requestCount
        assertTrue(session.perform(PrinterAction.HomeAll) is ActionOutcome.Rejected)
        assertTrue(session.perform(PrinterAction.SetBedTemperature(60)) is ActionOutcome.Rejected)
        assertEquals(before, s.requestCount)
        assertTrue(session.upload(File("x"), "x.gcode") is UploadResult.Failed)
    }

    @Test fun aLostReplyIsUnknownNotSuccess() = server(commandPolicy = SocketPolicy.DISCONNECT_AFTER_REQUEST).use { s ->
        assertTrue(open(s).perform(PrinterAction.Pause) is ActionOutcome.Unknown)
    }
}
