package net.jamesjennison.klippercompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * The network-free half of Helix's BambuLiveConnectionTest. Helix's remaining
 * cases there (a real handshake, a mistyped serial, a bad access code, driving
 * the chamber light) need a printer in LAN Only Mode on the same network and
 * were not ported: they would be skipped in every run this project can make.
 */
class BambuMqttConnectionTest {

    @Test
    fun replacedSessionCannotOwnNewConnectionCallbacks() {
        val sessions = BambuConnectionSessionGate()
        val first = sessions.begin()
        assertTrue(sessions.owns(first))

        val replacement = sessions.begin()
        assertFalse(sessions.owns(first))
        assertTrue(sessions.owns(replacement))

        sessions.invalidate()
        assertFalse(sessions.owns(replacement))
    }

    /** Nothing may be published down a connection that was never established. */
    @Test
    fun publishingBeforeConnectingFailsWithoutTouchingTheNetwork() {
        val connection = BambuMqttConnection(object : BambuMqttConnection.Listener {
            override fun onReport(payload: String) = Unit
            override fun onStateChange(state: String, message: String?) = Unit
        })

        val error = runCatching {
            connection.publish("""{"pushing":{"sequence_id":"0","command":"pushall"}}""")
                .get(1, TimeUnit.SECONDS)
        }.exceptionOrNull()

        assertTrue(error is ExecutionException)
        assertEquals("not-connected", (error?.cause as BambuConnectException).code)
    }

    /** close() on a connection that never opened is a no-op, not a crash. */
    @Test
    fun closingAnUnopenedConnectionIsSafe() {
        val connection = BambuMqttConnection(object : BambuMqttConnection.Listener {
            override fun onReport(payload: String) = Unit
            override fun onStateChange(state: String, message: String?) = Unit
        })

        connection.close()
        connection.close()
    }
}
