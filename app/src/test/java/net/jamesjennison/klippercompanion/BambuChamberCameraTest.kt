package net.jamesjennison.klippercompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference

/**
 * The network-free half of Helix's BambuLiveCameraTest. Its other cases stream
 * real frames off a printer on the LAN and were not ported; this one uses the
 * same reflection seam Helix used, because streamTo is the only part of the
 * camera that can misbehave without a printer attached.
 */
class BambuChamberCameraTest {

    @Test
    fun interruptingIdleViewerIsNormalShutdown() {
        val camera = BambuChamberCamera()
        val running = BambuChamberCamera::class.java.getDeclaredField("running").apply {
            isAccessible = true
            setBoolean(camera, true)
        }
        val streamTo = BambuChamberCamera::class.java
            .getDeclaredMethod("streamTo", java.io.OutputStream::class.java)
            .apply { isAccessible = true }
        val failure = AtomicReference<Throwable?>(null)

        val viewer = Thread {
            try {
                streamTo.invoke(camera, ByteArrayOutputStream())
            } catch (error: InvocationTargetException) {
                failure.set(error.targetException)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        viewer.start()

        val deadline = System.currentTimeMillis() + 2000
        while (viewer.state != Thread.State.TIMED_WAITING && System.currentTimeMillis() < deadline) {
            Thread.yield()
        }
        viewer.interrupt()
        viewer.join(2000)
        running.setBoolean(camera, false)

        assertFalse("Interrupted camera viewer did not stop", viewer.isAlive)
        assertNull("Normal camera shutdown escaped as an exception", failure.get())
    }
}
