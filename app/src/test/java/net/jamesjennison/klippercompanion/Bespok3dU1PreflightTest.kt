// Adapted from Helix (github.com/FatBoy721/Helix), AGPL-3.0-or-later; case selection only,
// ported to this project's JUnit conventions (see HeaterControlsTest.kt).
package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class Bespok3dU1PreflightTest {
    @Test fun parsesSystemStateFlagsAndRejectsUnknownFirmware() {
        val state = Bespok3dU1PreflightProtocol.parseSystemState("firmware=stock\nmodel=RK3562 U1\noverlay=no\nworkspace=yes\ndaemon=no\n")
        assertEquals("stock", state.firmware); assertTrue(state.workspacePresent); assertFalse(state.overlayActive)
        assertThrows(IllegalArgumentException::class.java) { Bespok3dU1PreflightProtocol.parseSystemState("firmware=weird\nmodel=RK3562\noverlay=no\nworkspace=no\ndaemon=no\n") }
        assertThrows(IllegalArgumentException::class.java) { Bespok3dU1PreflightProtocol.parseSystemState("model=RK3562\noverlay=no\nworkspace=no\ndaemon=no\n") }
    }
    @Test fun eligibilityRejectsNonU1ExtendedFirmwareAndActivePrints() {
        val stockIdle = Bespok3dU1PreflightProtocol.parseSystemState("firmware=stock\nmodel=RK3562 U1\noverlay=no\nworkspace=no\ndaemon=no\n")
        assertTrue(Bespok3dU1PreflightProtocol.result(stockIdle, "complete", "SHA256:abc").eligible)
        assertFalse(Bespok3dU1PreflightProtocol.result(stockIdle, "printing", "SHA256:abc").eligible)
        assertFalse(Bespok3dU1PreflightProtocol.result(stockIdle, "paused", "SHA256:abc").eligible)
        val extended = Bespok3dU1PreflightProtocol.parseSystemState("firmware=extended\nmodel=RK3562 U1\noverlay=no\nworkspace=no\ndaemon=no\n")
        assertFalse(Bespok3dU1PreflightProtocol.result(extended, "complete", "SHA256:abc").eligible)
        val notU1 = Bespok3dU1PreflightProtocol.parseSystemState("firmware=stock\nmodel=SomeOtherBoard\noverlay=no\nworkspace=no\ndaemon=no\n")
        assertFalse(Bespok3dU1PreflightProtocol.result(notU1, "complete", "SHA256:abc").eligible)
    }
    @Test fun parsePrintStateReadsMoonrakerStatusOrFails() {
        assertEquals("printing", Bespok3dU1PreflightProtocol.parsePrintState("""{"result":{"status":{"print_stats":{"state":"Printing"}}}}"""))
        assertThrows(IllegalArgumentException::class.java) { Bespok3dU1PreflightProtocol.parsePrintState("""{"result":{}}""") }
    }
    @Test fun s90lmdPatchIsIdempotentAndRequiresAShebang() {
        val patched = Bespok3dU1EnrollmentProtocol.patchS90lmd("#!/bin/sh\necho hi\n")
        assertTrue(patched.lines()[1].contains("S99bespok3d"))
        assertEquals(patched, Bespok3dU1EnrollmentProtocol.patchS90lmd(patched))
        assertThrows(IllegalArgumentException::class.java) { Bespok3dU1EnrollmentProtocol.patchS90lmd("echo hi\n") }
    }
    @Test fun nginxPatchAppendsIncludeBeforeClosingBraceAndIsIdempotent() {
        val patched = Bespok3dU1EnrollmentProtocol.patchNginx("server {\n  listen 80;\n}\n")
        assertTrue(patched.contains("bespok3d/etc/nginx/locations"))
        assertTrue(patched.trimEnd().endsWith("}"))
        assertEquals(patched, Bespok3dU1EnrollmentProtocol.patchNginx(patched))
        assertThrows(IllegalArgumentException::class.java) { Bespok3dU1EnrollmentProtocol.patchNginx("server {\n  listen 80;\n") }
    }
    @Test fun mergeAclGrantsAdminOnlyToTheFirstIdentityAndIsIdempotent() {
        val first = Bespok3dU1EnrollmentProtocol.mergeAcl(null, "helix-a", "token-a", "Phone A")
        val firstJson = org.json.JSONObject(first)
        assertEquals("admin", firstJson.getJSONObject("roles").getString("helix-a"))
        val second = Bespok3dU1EnrollmentProtocol.mergeAcl(first, "helix-b", "token-b", "Phone B")
        val secondJson = org.json.JSONObject(second)
        assertEquals("user", secondJson.getJSONObject("roles").getString("helix-b"))
        assertEquals(2, secondJson.getJSONArray("keys").length())
        // Re-merging the same identity/token must not duplicate entries.
        val replay = Bespok3dU1EnrollmentProtocol.mergeAcl(second, "helix-b", "token-b", "Phone B")
        assertEquals(2, org.json.JSONObject(replay).getJSONArray("keys").length())
    }
}
