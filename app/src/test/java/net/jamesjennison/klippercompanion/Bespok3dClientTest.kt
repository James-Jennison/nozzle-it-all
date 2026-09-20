// Adapted in part from Helix (github.com/FatBoy721/Helix), AGPL-3.0-or-later, whose
// Bespok3dProtocolTest.kt exercises the same contract this file ports (case selection only;
// this file's scaffolding is this project's own JUnit convention, see HeaterControlsTest.kt).
package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Bespok3dClientTest {
    @Test fun accessRequestBodyMatchesThePublishedContractAndRejectsUnsafeInput() {
        val json = JSONObject(Bespok3dProtocol.accessRequestBody("Phone", "helix-1234_abcd", "0123456789abcdef0123456789abcdef", "PUBLIC KEY"))
        assertEquals(setOf("label", "identity", "token", "public_key"), json.keys().asSequence().toSet())
        assertEquals("helix-1234_abcd", json.getString("identity"))
        assertThrows(IllegalArgumentException::class.java) { Bespok3dProtocol.accessRequestBody("Phone", "identity with spaces", "0123456789abcdef") }
        assertThrows(IllegalArgumentException::class.java) { Bespok3dProtocol.accessRequestBody("Phone", "helix-phone", "too-short") }
        assertThrows(IllegalArgumentException::class.java) { Bespok3dProtocol.accessRequestBody("Phone\nInjected", "helix-phone", "0123456789abcdef") }
    }
    @Test fun probeRecognizesOnlyTheOfficialDaemonSource() {
        val cert = "-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----"
        val fingerprint = Bespok3dProtocol.certificateSha256("fixture".toByteArray())
        val probe = Bespok3dProtocol.parseProbe("""{"version":"0.12.24","license":"AGPL-3.0-or-later","source":"https://github.com/Bespok3d/daemon"}""", cert, fingerprint)
        assertEquals("0.12.24", probe.version); assertEquals(32, fingerprint.split(':').size)
        assertThrows(IllegalArgumentException::class.java) {
            Bespok3dProtocol.parseProbe("""{"version":"0.12.24","license":"AGPL-3.0-or-later","source":"https://example.com/lookalike"}""", cert, fingerprint)
        }
    }
    @Test fun statusRequiresOkAndBothIdentityFields() {
        assertThrows(IllegalArgumentException::class.java) { Bespok3dProtocol.parseStatus("""{"ok":false,"version":"1","printer_uuid":"a"}""") }
        assertThrows(IllegalArgumentException::class.java) { Bespok3dProtocol.parseStatus("""{"ok":true,"version":"","printer_uuid":"a"}""") }
        val status = Bespok3dProtocol.parseStatus("""{"ok":true,"version":"1.0","printer_uuid":"uuid-a"}""")
        assertEquals("1.0", status.version); assertEquals("uuid-a", status.printerUuid)
    }
    @Test fun dependencyOrderTopologicallySortsAndRejectsCycles() {
        val a = sampleFixturePlugin("a", deps = listOf("b"))
        val b = sampleFixturePlugin("b", deps = listOf("c"))
        val c = sampleFixturePlugin("c")
        val catalog = Bespok3dPluginCatalog(listOf(a, b, c), emptyMap())
        val ordered = Bespok3dProtocol.dependencyOrder(catalog, listOf("a"))
        assertEquals(listOf("c", "b", "a"), ordered.map { it.id })
        val cyclic = Bespok3dPluginCatalog(listOf(sampleFixturePlugin("x", deps = listOf("y")), sampleFixturePlugin("y", deps = listOf("x"))), emptyMap())
        assertThrows(IllegalArgumentException::class.java) { Bespok3dProtocol.dependencyOrder(cyclic, listOf("x")) }
        assertThrows(IllegalArgumentException::class.java) { Bespok3dProtocol.dependencyOrder(catalog, emptyList()) }
    }
    @Test fun dependencyOrderSkipsAlreadyInstalledTransitiveDeps() {
        val a = sampleFixturePlugin("a", deps = listOf("b"))
        val b = sampleFixturePlugin("b")
        val catalog = Bespok3dPluginCatalog(listOf(a, b), mapOf("b" to "1.0.0"))
        assertEquals(listOf("a"), Bespok3dProtocol.dependencyOrder(catalog, listOf("a")).map { it.id })
    }
    @Test fun parseInstallResultSeparatesInstalledFromFailedAndIgnoresServicesRow() {
        val result = Bespok3dProtocol.parseInstallResult("""{"ok":false,"results":[{"plugin_id":"a","ok":true},{"plugin_id":"(services)","ok":true},{"plugin_id":"b","ok":false,"reason":"boom"}]}""")
        assertFalse(result.ok); assertEquals(listOf("a"), result.installedIds); assertEquals("boom", result.failures["b"])
    }
    @Test fun helixScreenChoicesAreLimitedToTheTwoKnownSelections() {
        assertThrows(IllegalArgumentException::class.java) { Bespok3dProtocol.helixScreenReconfigureBody("unknown-ui") }
        val body = JSONObject(Bespok3dProtocol.helixScreenReconfigureBody("helixscreen"))
        assertEquals("helixscreen", body.getString("SCREEN_UI"))
    }
    @Test fun isBespok3dScreenMatchesByNameOrStreamPath() {
        assertTrue(Camera("gui", "", "http://host/screen/live").isBespok3dScreen())
        assertTrue(Camera("GUI", "", "http://host/anything").isBespok3dScreen())
        assertTrue(Camera("Touchscreen", "", "http://host/screen").isBespok3dScreen())
        assertFalse(Camera("Nozzle cam", "", "http://host/webcam/stream").isBespok3dScreen())
    }
    private fun sampleFixturePlugin(id: String, deps: List<String> = emptyList()) =
        Bespok3dPlugin(id, id, "1.0.0", "", "other", "org/repo", deps, emptyList())
}
