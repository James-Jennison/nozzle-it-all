package com.nozzleitall.desktop.environment

import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/** Desktop collection follows app lifetime, not focus; never starts collection itself. */
internal class DesktopMonitoringWindowListener(private val available: (Boolean) -> Unit) : WindowAdapter() {
    override fun windowClosed(e: WindowEvent?) { available(false) }
}
