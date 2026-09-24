package net.jamesjennison.klippercompanion

// Shared with PrintMonitorService's own consecutive-failure debounce (ConnectionDebounce.kt) -
// same tolerance for the same reason: a single blip (mobile network handoff, VPN re-handshake)
// should not read as a real disconnect in either the foreground dashboard or a background alert.
const val CONSECUTIVE_FAILURE_TOLERANCE = 2

enum class AlertKind { COMPLETED, ERROR, CANCELLED, OFFLINE, BACK_ONLINE, PAUSED, STARTED, NEARLY_DONE }
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
/**
 * Suppresses a transient run of failed polls from being reported to [PrintAlerts] as an actual
 * disconnect, so a brief blip (a mobile-network handoff, a VPN/Tailscale re-handshake, a printer's
 * own WiFi radio dropping and reassociating within a poll cycle or two) doesn't fire a false
 * OFFLINE/BACK_ONLINE notification pair. Shares [CONSECUTIVE_FAILURE_TOLERANCE] with
 * [PrinterModel]'s own foreground-loop debounce (PrinterModel.kt) - same tolerance, same reason,
 * applied here to what actually reaches the alert/notification path rather than to what's shown
 * live on the dashboard.
 */
object ConnectionDebounce {
    fun debounce(raw: PrinterConnection, previous: PrinterConnection?, consecutiveFailures: Int): PrinterConnection =
        if (!raw.connected && previous?.connected == true && consecutiveFailures < CONSECUTIVE_FAILURE_TOLERANCE) previous else raw
}
object PrintAlerts {
    private val ACTIVE_STATES = setOf("printing", "paused")

    /**
     * `previous` is the last observation for this address, or null if this is the first one
     * seen this session — a null previous never produces an alert, so the app doesn't spam
     * "back online" or "print finished" the moment it starts up and reads an existing state.
     */
    /** [extended] adds the optional, chattier alerts: a print starting, and a print passing [NEARLY_DONE_AT] of its progress. */
    const val NEARLY_DONE_AT = 0.9f

    fun detect(address: String, label: String, previous: PrinterConnection?, current: PrinterConnection, extended: Boolean = false): List<PrintAlert> {
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
                // Only reachable from "printing" (paused is itself in ACTIVE_STATES, so
                // paused->paused never enters this block, and paused->printing has no branch
                // here at all - a resume was never alert-worthy and still isn't). The one new
                // kind this app didn't detect at all before: filament runout, a manual pause, an
                // M600 all used to be silent, and it's the one transition where a live Resume/
                // Cancel notification action (see PrintMonitorService) is actually meaningful.
                "paused" -> alerts += PrintAlert(address, AlertKind.PAUSED, current.snapshot?.filename.orEmpty(), "$label paused $filename.")
            }
        }
        if (extended && current.connected && previous.connected) {
            val name = current.snapshot?.filename.orEmpty().ifBlank { "a print" }
            if (curState == "printing" && prevState != "printing" && prevState != "paused") alerts += PrintAlert(address, AlertKind.STARTED, current.snapshot?.filename.orEmpty(), "$label started $name.")
            val was = previous.snapshot?.progress ?: 0f; val now = current.snapshot?.progress ?: 0f
            if (curState == "printing" && prevState == "printing" && was < NEARLY_DONE_AT && now >= NEARLY_DONE_AT) alerts += PrintAlert(address, AlertKind.NEARLY_DONE, current.snapshot?.filename.orEmpty(), "$label is nearly done with $name.")
        }
        return alerts
    }
}
