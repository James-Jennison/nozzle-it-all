package com.nozzleitall.desktop

import com.nozzleitall.desktop.environment.DesktopMonitoringWindowListener
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopMonitoringWindowListenerTest {
    @Test fun switchingAppsAndMinimizingDoNotStopOrRestartCollection() {
        val changes = mutableListOf<Boolean>()
        val listener = DesktopMonitoringWindowListener(changes::add)
        listener.windowDeactivated(null)
        listener.windowIconified(null)
        listener.windowDeiconified(null)
        listener.windowActivated(null)
        listener.windowClosing(null) // A canceled close must not stop monitoring.
        assertEquals(emptyList<Boolean>(), changes)
        listener.windowClosed(null)
        assertEquals(listOf(false), changes)
    }
}
