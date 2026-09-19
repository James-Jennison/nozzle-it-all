package net.jamesjennison.klippercompanion

enum class AlertKind { COMPLETED, ERROR, CANCELLED, OFFLINE, BACK_ONLINE }
data class PrintAlert(val address: String, val kind: AlertKind, val filename: String = "", val message: String)

/**
 * Pure, testable detection of alert-worthy printer state transitions (P17: background
 * completion/error/offline alerts). This only decides *whether* a single previous-to-current
 * observation is worth surfacing — it does not poll, schedule, or deliver a notification.
 * Delivery (an Android notification, tied to a foreground service or a scheduled job) is a
 * separate, still-undecided piece with real battery/permission tradeoffs and locked-screen/
 * Doze behavior that can only be verified on a device.
 *
 * Filament-sensor alerts are not covered yet: PrinterSnapshot has no filament-sensor field to
 * detect a runout from, so there is nothing to diff here until that read is added (parallel to
 * how P15's fan/toolhead visibility was added before any control was built on top of it).
 */
object PrintAlerts {
    private val ACTIVE_STATES = setOf("printing", "paused")

    /**
     * `previous` is the last observation for this address, or null if this is the first one
     * seen this session — a null previous never produces an alert, so the app doesn't spam
     * "back online" or "print finished" the moment it starts up and reads an existing state.
     */
    fun detect(address: String, label: String, previous: PrinterConnection?, current: PrinterConnection): List<PrintAlert> {
        if (previous == null) return emptyList()
        val alerts = mutableListOf<PrintAlert>()
        if (previous.connected && !current.connected) alerts += PrintAlert(address, AlertKind.OFFLINE, message = "$label went offline.")
        else if (!previous.connected && current.connected) alerts += PrintAlert(address, AlertKind.BACK_ONLINE, message = "$label is back online.")
        val prevState = previous.snapshot?.state
        val curState = current.snapshot?.state
        if (current.connected && prevState in ACTIVE_STATES && prevState != curState) {
            // Raw print_stats.filename, not PrinterSnapshot.activeFilename: the latter blanks
            // out as soon as the state leaves "printing"/"paused", i.e. exactly when we need it.
            val filename = current.snapshot?.filename.orEmpty().ifBlank { "the print" }
            when (curState) {
                "complete" -> alerts += PrintAlert(address, AlertKind.COMPLETED, current.snapshot?.filename.orEmpty(), "$label finished $filename.")
                "error" -> alerts += PrintAlert(address, AlertKind.ERROR, current.snapshot?.filename.orEmpty(), "$label reported an error on $filename.")
                "cancelled" -> alerts += PrintAlert(address, AlertKind.CANCELLED, current.snapshot?.filename.orEmpty(), "$label cancelled $filename.")
            }
        }
        return alerts
    }
}
