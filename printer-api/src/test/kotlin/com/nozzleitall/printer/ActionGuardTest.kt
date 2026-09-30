package com.nozzleitall.printer

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ActionGuardTest {
    private class FakeSession(var state: PrinterState) : PrinterSession {
        var performed = 0
        var next: () -> ActionOutcome = { ActionOutcome.Accepted }
        var clock = 1000L
        override val identity = PrinterIdentity("p1", "U1", "Snapmaker U1", PrinterFamily.PAXX_U1, "http://192.168.1.40")
        override val capabilities = Capabilities(pausePrint = true, resumePrint = true, cancelPrint = true)
        override fun status() = PrinterStatus(state, ConnectionRoute.LAN, observedAtMillis = clock)
        override fun cameras() = emptyList<CameraEndpoint>()
        override fun snapshot(camera: CameraEndpoint) = ByteArray(0)
        override fun upload(file: File, remoteName: String, progress: UploadProgress) = UploadResult.Failed("n/a")
        override fun perform(action: PrinterAction): ActionOutcome { performed++; return next() }
        override fun close() {}
    }

    @Test fun actionNotOfferedInWrongState() {
        val s = FakeSession(PrinterState.READY)
        val guard = ActionGuard(s) { s.clock }
        assertThrows(ActionRefused::class.java) { guard.prepare(PrinterAction.Pause, s.status()) }
    }

    @Test fun confirmedActionRunsExactlyOnce() {
        val s = FakeSession(PrinterState.PRINTING)
        val guard = ActionGuard(s) { s.clock }
        val prepared = guard.prepare(PrinterAction.Pause, s.status())
        val confirmation = guard.confirm(prepared)
        assertEquals(ActionOutcome.Accepted, guard.execute(prepared, confirmation))
        assertEquals(1, s.performed)
        // The same confirmation cannot be replayed.
        assertThrows(ActionRefused::class.java) { guard.execute(prepared, confirmation) }
        assertEquals(1, s.performed)
    }

    @Test fun stateChangeAfterReviewSendsNothing() {
        val s = FakeSession(PrinterState.PRINTING)
        val guard = ActionGuard(s) { s.clock }
        val prepared = guard.prepare(PrinterAction.Cancel, s.status())
        val confirmation = guard.confirm(prepared)
        s.state = PrinterState.FINISHED
        val outcome = guard.execute(prepared, confirmation)
        assertTrue(outcome is ActionOutcome.Rejected)
        assertEquals(0, s.performed)
    }

    @Test fun unknownOutcomeBlocksFurtherCommandsUntilReconciled() {
        val s = FakeSession(PrinterState.PRINTING)
        val guard = ActionGuard(s) { s.clock }
        s.next = { throw java.net.SocketTimeoutException("timeout") }
        val p = guard.prepare(PrinterAction.Pause, s.status())
        assertTrue(guard.execute(p, guard.confirm(p)) is ActionOutcome.Unknown)
        assertTrue(guard.needsReconcile)
        assertThrows(ActionRefused::class.java) { guard.prepare(PrinterAction.Pause, s.status()) }
        // An offline reading does not count as reconciliation.
        s.clock = 2000; s.state = PrinterState.OFFLINE
        guard.reconcile()
        assertTrue(guard.needsReconcile)
        // A real reading after the failure clears it; the user then sees the actual state (already paused).
        s.clock = 3000; s.state = PrinterState.PAUSED
        assertEquals(PrinterState.PAUSED, guard.reconcile().state)
        assertFalse(guard.needsReconcile)
        assertThrows(ActionRefused::class.java) { guard.prepare(PrinterAction.Pause, s.status()) }
        assertEquals(1, s.performed)
    }

    @Test fun confirmationFromAnotherGuardIsRejected() {
        val s = FakeSession(PrinterState.PRINTING)
        val a = ActionGuard(s) { s.clock }
        val b = ActionGuard(s) { s.clock }
        val pa = a.prepare(PrinterAction.Pause, s.status())
        val pb = b.prepare(PrinterAction.Pause, s.status())
        assertThrows(ActionRefused::class.java) { a.execute(pa, b.confirm(pb)) }
        assertEquals(0, s.performed)
    }

    @Test fun registryRefusesCloudAdaptersInProcess() {
        val cloudy = object : DeviceAdapter {
            override val id = "cloudy"; override val displayName = "Cloudy"; override val families = setOf(PrinterFamily.STOCK_U1)
            override val mayUseVendorCloud = true
            override fun probe(address: String): DiscoveredPrinter? = null
            override fun open(config: PrinterConfig): PrinterSession = throw UnsupportedOperationException()
        }
        assertThrows(IllegalArgumentException::class.java) { AdapterRegistry().registerBuiltIn(cloudy) }
    }

    @Test fun optionalAdapterIsNotStartedByScanningOrByBuiltInUse() {
        var started = 0
        val registry = AdapterRegistry()
        registry.registerOptional("stock-u1") { started++; throw AdapterUnavailable("not installed") }
        assertTrue(registry.probeBuiltIn("http://192.168.1.40").isEmpty())
        assertEquals(0, started)
        assertTrue(registry.startedOptionalIds.isEmpty())
        assertThrows(AdapterUnavailable::class.java) { registry.adapter("stock-u1") }
        assertEquals(1, started)
    }

    @Test fun routeClassification() {
        assertEquals(ConnectionRoute.LAN, routeFor("192.168.1.40"))
        assertEquals(ConnectionRoute.LAN, routeFor("u1.local"))
        assertEquals(ConnectionRoute.PRIVATE_NETWORK, routeFor("100.101.5.6"))
        assertEquals(ConnectionRoute.PRIVATE_NETWORK, routeFor("u1.tail1234.ts.net"))
        assertEquals(ConnectionRoute.PRIVATE_NETWORK, routeFor("[fd7a:115c:a1e0::1]"))
        assertEquals(ConnectionRoute.LAN, routeFor("100.200.1.1"))
    }
}
