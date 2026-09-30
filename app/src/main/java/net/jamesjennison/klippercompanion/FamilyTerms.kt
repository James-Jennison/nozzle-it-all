package net.jamesjennison.klippercompanion

import com.nozzleitall.printer.PrinterState
import com.nozzleitall.printer.label

// The family's shared state vocabulary (design/terminology/glossary.json via :printer-api) for what Android shows.
// Android keeps carrying the printer's raw state strings internally; only the words on screen are mapped, so every
// platform says the same thing for the same state ("Ready", "Finished", "Needs attention"), and Finished/Cancelled
// are no longer shown as "standby".
fun sharedState(raw: String?, connected: Boolean = true): PrinterState = when {
    !connected -> PrinterState.OFFLINE
    raw.isNullOrBlank() || raw == "awaiting printer" -> PrinterState.CONNECTING
    else -> when (raw.lowercase()) {
        "standby", "ready", "idle", "operational" -> PrinterState.READY
        "printing" -> PrinterState.PRINTING
        "paused", "pausing" -> PrinterState.PAUSED
        "complete", "finished" -> PrinterState.FINISHED
        "cancelled", "canceled", "stopped" -> PrinterState.CANCELLED
        "error", "shutdown" -> PrinterState.ERROR
        "startup", "not ready" -> PrinterState.STARTING
        "offline", "unavailable" -> PrinterState.OFFLINE
        else -> PrinterState.UNKNOWN
    }
}

fun familyStateLabel(raw: String?, connected: Boolean = true): String = sharedState(raw, connected).label
