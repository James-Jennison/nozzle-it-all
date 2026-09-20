// Adapted from Helix (github.com/FatBoy721/Helix), AGPL-3.0-or-later.
// Original: android/app/src/test/java/org/crabcore/u1control/bespok3d/Bespok3dSshExitStatusTest.kt
package net.jamesjennison.klippercompanion

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Bespok3dSshExitStatusTest {
    @Test fun waitsForExitStatusPacketAfterCommandEof() {
        val statuses = ArrayDeque(listOf(-1, -1, 0))
        var now = 0L
        val result = awaitSshExitStatus(readStatus = { statuses.removeFirst() }, timeoutMs = 100, nanoTime = { now }, pause = { ms -> now += ms * 1_000_000 })
        assertEquals(0, result); assertEquals(20_000_000L, now)
    }
    @Test fun preservesRealNonzeroExitStatus() { assertEquals(127, awaitSshExitStatus({ 127 }, timeoutMs = 100)) }
    @Test fun timesOutWhenServerNeverSendsExitStatus() {
        var now = 0L
        assertThrows(SocketTimeoutException::class.java) { awaitSshExitStatus(readStatus = { -1 }, timeoutMs = 20, nanoTime = { now }, pause = { ms -> now += ms * 1_000_000 }) }
    }
}
