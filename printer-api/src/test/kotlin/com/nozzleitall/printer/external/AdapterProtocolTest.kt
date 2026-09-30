package com.nozzleitall.printer.external

import com.nozzleitall.printer.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdapterProtocolTest {
    @Test fun statusRoundTrips() {
        val s = PrinterStatus(PrinterState.PRINTING, ConnectionRoute.PRIVATE_NETWORK, JobProgress("cube.gcode", 0.25f, 60.0, 3, 100),
            Temperature(60.0, 60.0), listOf(Toolhead(0, 210.0, 210.0, 0.4, true, Material("Polymaker", "PLA", "Basic", "#BE38F3", true), true)),
            "hello", 42, mapOf("snapmaker.full-spectrum" to mapOf("available" to true, "palette" to listOf("#BE38F3")), "bambu.ams" to listOf(1, 2)))
        assertEquals(s, AdapterProtocol.decodeStatus(JSONObject(AdapterProtocol.encode(s).toString())))
    }

    @Test fun unknownFieldsAndStatesDecodeSafely() {
        val o = JSONObject("""{"state":"LEVITATING","route":"LAN","futureField":{"x":1},"toolheads":[{"index":0,"newThing":true}]}""")
        val s = AdapterProtocol.decodeStatus(o)
        assertEquals(PrinterState.UNKNOWN, s.state)
        assertEquals(1, s.toolheads.size)
    }

    @Test fun everyActionRoundTrips() {
        val actions = listOf(PrinterAction.StartJob("a/b.gcode", listOf(2, 0)), PrinterAction.Pause, PrinterAction.Resume, PrinterAction.Cancel,
            PrinterAction.SetNozzleTemperature(1, 210), PrinterAction.SetBedTemperature(60), PrinterAction.HomeAll, PrinterAction.Jog('X', -10.0),
            PrinterAction.LoadMaterial(2), PrinterAction.UnloadMaterial(3), PrinterAction.SetMaterialInfo(0, Material("X", "PLA", colorHex = "#FFFFFF")),
            PrinterAction.SelectToolhead(1))
        actions.forEach { assertEquals(it, AdapterProtocol.decodeAction(AdapterProtocol.encode(it))) }
        assertNull(AdapterProtocol.decodeAction(JSONObject().put("kind", "selfDestruct")))
    }

    @Test fun unrecognisedOutcomeIsTreatedAsUnknown() {
        assertTrue(AdapterProtocol.decodeOutcome(JSONObject().put("outcome", "probably")) is ActionOutcome.Unknown)
    }

    @Test fun majorVersionMismatchIsRefused() {
        val future = JSONObject().put("type", "hello").put("protocol", AdapterProtocol.NAME).put("version", org.json.JSONArray().put(2).put(0))
        assertNotNull(AdapterProtocol.incompatibility(future))
        val newerMinor = JSONObject().put("type", "hello").put("protocol", AdapterProtocol.NAME).put("version", org.json.JSONArray().put(1).put(7))
        assertNull(AdapterProtocol.incompatibility(newerMinor))
    }

    private fun helper(mode: String) = ExternalAdapterClient(listOf(File(System.getProperty("java.home"), "bin/java").path,
        "-cp", System.getProperty("java.class.path"), FakeAdapterMain::class.java.name, mode), requestTimeoutMillis = 10_000)

    private val config = PrinterConfig(PrinterIdentity("s1", "Stock", "Snapmaker U1", PrinterFamily.STOCK_U1, "http://192.168.1.41"), "fake")

    @Test fun realHelperProcessServesStatusAndActions() {
        helper("ok").use { client ->
            assertFalse(client.isRunning) // not started until used
            assertEquals("fake", client.id)
            val session = client.open(config)
            assertEquals(PrinterState.READY, session.status().state)
            assertEquals(ActionOutcome.Accepted, session.perform(PrinterAction.HomeAll))
            assertEquals(AdapterAccountState.SIGNED_OUT, client.account().state)
        }
    }

    @Test fun helperCrashOnlyMakesItsOwnPrintersOffline() {
        helper("crash-on-status").use { client ->
            val session = client.open(config)
            val status = session.status()
            assertEquals(PrinterState.OFFLINE, status.state)
            assertNotNull(status.message)
            // An action after the crash is reported as not sent/unknown, never as accepted.
            assertFalse(session.perform(PrinterAction.HomeAll) is ActionOutcome.Accepted)
        }
    }

    @Test fun incompatibleHelperIsRefused() {
        helper("v2").use { client -> assertThrows(AdapterUnavailable::class.java) { client.open(config) } }
    }
}

/** A tiny helper process for the tests above. */
object FakeAdapterMain {
    @JvmStatic fun main(args: Array<String>) {
        val mode = args.firstOrNull() ?: "ok"
        if (mode == "v2") {
            println("""{"type":"hello","protocol":"nozzle-adapter","version":[2,0],"adapter":{"id":"fake"}}"""); System.out.flush()
            Thread.sleep(5000); return
        }
        val adapter = object : DeviceAdapter {
            override val id = "fake"; override val displayName = "Fake"; override val families = setOf(PrinterFamily.STOCK_U1)
            override val mayUseVendorCloud = true
            override fun probe(address: String): DiscoveredPrinter? = null
            override fun open(config: PrinterConfig): PrinterSession = object : PrinterSession {
                override val identity = config.identity
                override val capabilities = Capabilities(requiresVendorAccount = true)
                override fun status(): PrinterStatus { if (mode == "crash-on-status") Runtime.getRuntime().halt(3); return PrinterStatus(PrinterState.READY, ConnectionRoute.LAN) }
                override fun cameras() = emptyList<CameraEndpoint>()
                override fun snapshot(camera: CameraEndpoint) = ByteArray(0)
                override fun upload(file: File, remoteName: String, progress: UploadProgress) = UploadResult.Failed("n/a")
                override fun perform(action: PrinterAction) = ActionOutcome.Accepted
                override fun close() {}
            }
        }
        val account = object : AccountAdapter {
            override fun account() = AdapterAccount(AdapterAccountState.SIGNED_OUT)
            override fun beginSignIn() = AdapterAccount(AdapterAccountState.SIGNING_IN)
            override fun completeSignIn(response: String) = AdapterAccount(AdapterAccountState.SIGNED_IN)
            override fun signOut() = AdapterAccount(AdapterAccountState.SIGNED_OUT)
        }
        AdapterServer(adapter, account).serve(System.`in`, System.out)
    }
}
