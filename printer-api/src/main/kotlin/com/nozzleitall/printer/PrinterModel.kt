package com.nozzleitall.printer

// The shared printer model: vendor-neutral. Nothing here assumes a particular manufacturer, toolhead count or firmware.
// Every user-facing name has an entry in design/terminology/glossary.json; GlossaryConsistencyTest keeps them in step.
// Vendor-specific data travels in namespaced extensions ("snapmaker.*", "bambu.*", "prusa.*"), never in core fields.

/**
 * Which kind of printer (and so which adapter family). Open-ended: a new vendor is a new id, with no change to this
 * file or to other adapters. The constants are the families Nozzle knows today.
 */
@JvmInline
value class PrinterFamily(val id: String) {
    init { require(Regex("[a-z0-9][a-z0-9.-]{0,63}").matches(id)) { "Invalid printer family id: $id" } }
    val glossaryId: String get() = "family.$id"
    companion object {
        /** Snapmaker U1 on PAXX firmware, LAN mode: the flagship, native, offline integration. */
        val PAXX_U1 = PrinterFamily("paxx-u1")
        /** Snapmaker U1 on stock firmware: optional adapter that may use Snapmaker's cloud. */
        val STOCK_U1 = PrinterFamily("stock-u1")
        val BAMBU_LAB = PrinterFamily("bambu-lab")
        val PRUSA = PrinterFamily("prusa")
        /** Any other Klipper printer reached through Moonraker. */
        val KLIPPER = PrinterFamily("klipper")
        val OCTOPRINT = PrinterFamily("octoprint")
        /** A printer profile with no live connection: slice, save and export only. */
        val EXPORT_ONLY = PrinterFamily("export-only")
        val known = listOf(PAXX_U1, STOCK_U1, BAMBU_LAB, PRUSA, KLIPPER, OCTOPRINT, EXPORT_ONLY)
        /** Reads ids written by earlier builds ("PAXX", "STOCK_U1", "KLIPPER") as well as current ones. */
        fun parse(raw: String?): PrinterFamily = when (raw) {
            null, "" -> PAXX_U1
            "PAXX" -> PAXX_U1
            "STOCK_U1" -> STOCK_U1
            "KLIPPER" -> KLIPPER
            else -> runCatching { PrinterFamily(raw) }.getOrDefault(PAXX_U1)
        }
    }
    override fun toString() = id
}

/**
 * How Nozzle reaches a printer. [VENDOR_CLOUD] is only ever reported by an adapter that has a vendor cloud and only when
 * the user enabled it; the core never opens a cloud route itself.
 */
enum class ConnectionRoute(val glossaryId: String) {
    LAN("route.lan"),
    /** The user's own private network (Tailscale or similar) carrying LAN traffic; Nozzle relays nothing. */
    PRIVATE_NETWORK("route.private-network"),
    VENDOR_CLOUD("route.vendor-cloud"),
    /** No live connection at all (export-only targets). */
    NONE("route.none"),
}

/**
 * One state vocabulary for every platform and vendor. Raw vendor strings are mapped onto it by [fromRaw] or by
 * adapters; the UI never shows a raw vendor string as a state.
 */
enum class PrinterState(val glossaryId: String, val isActiveJob: Boolean = false) {
    OFFLINE("state.offline"),
    CONNECTING("state.connecting"),
    /** Reachable, but the firmware is still starting or reports it is not ready. */
    STARTING("state.starting"),
    READY("state.ready"),
    PRINTING("state.printing", isActiveJob = true),
    PAUSED("state.paused", isActiveJob = true),
    /** A job ended normally; the printer is otherwise ready. */
    FINISHED("state.finished"),
    CANCELLED("state.cancelled"),
    /** The firmware reports an error or shutdown that needs attention before printing. */
    ERROR("state.error"),
    /** Reachable but the answer could not be understood; never guessed into another state. */
    UNKNOWN("state.unknown");

    companion object {
        /**
         * The shared mapping from the raw state words printer protocols use (Moonraker print_stats, OctoPrint, PrusaLink,
         * Bambu gcode_state, as normalised by the transports) onto the vocabulary. Unrecognised words are UNKNOWN.
         */
        fun fromRaw(raw: String?, connected: Boolean = true): PrinterState = when {
            !connected -> OFFLINE
            raw.isNullOrBlank() -> CONNECTING
            else -> when (raw.lowercase()) {
                "standby", "ready", "idle", "operational", "finish" -> READY
                "printing", "running", "prepare" -> PRINTING
                "paused", "pausing", "pause" -> PAUSED
                "complete", "finished" -> FINISHED
                "cancelled", "canceled", "stopped" -> CANCELLED
                "error", "shutdown", "failed", "attention" -> ERROR
                "startup", "not ready" -> STARTING
                "offline", "unavailable" -> OFFLINE
                else -> UNKNOWN
            }
        }
    }
}

data class PrinterIdentity(
    /** Stable local id chosen by Nozzle when the printer is added (not a vendor serial or cloud id). */
    val id: String,
    val displayName: String,
    /** The model as the user would say it ("Snapmaker U1", "Prusa MK4S", "Bambu Lab P1S"). */
    val model: String,
    val family: PrinterFamily,
    /** The address the user gave (http://192.168.1.40, u1.tailnet.ts.net, a Bambu host); empty for export-only. */
    val address: String,
    /** Which slicing profile this printer uses; independent of the connection. */
    val profileId: String? = null,
)

/** A spool as the printer reports it. Every field is optional because printers report partial data. */
data class Material(
    val vendor: String? = null,
    val type: String? = null,
    val subType: String? = null,
    /** "#RRGGBB", upper case. */
    val colorHex: String? = null,
    /** True when the printer read the spool from a tag (RFID/NFC) rather than a manual entry. */
    val fromTag: Boolean = false,
) {
    val label: String get() = listOfNotNull(vendor, type, subType).joinToString(" ").ifBlank { "Unknown material" }
}

/**
 * A place the printer can feed material from: a toolhead on a toolchanger, or a slot on a single-nozzle printer's
 * material unit (AMS, MMU). [index] is 0-based; the UI numbers from 1.
 */
data class Toolhead(
    val index: Int,
    val nozzleTemperature: Double? = null,
    val nozzleTarget: Double? = null,
    val nozzleDiameterMm: Double? = null,
    val loaded: Boolean = false,
    val material: Material? = null,
    val active: Boolean = false,
)

enum class CameraKind { WEBRTC, MJPEG_STREAM, SNAPSHOT, RTSP }

/**
 * One printer camera. [url] is the camera's own stream address (for WebRTC, the player). [liveUrl] is a live MJPEG stream
 * (multipart/x-mixed-replace) Nozzle can show itself; screens always prefer it and fall back to [snapshotUrl] stills only
 * when a camera has no live stream.
 */
data class CameraEndpoint(val id: String, val name: String, val kind: CameraKind, val url: String, val snapshotUrl: String? = null, val liveUrl: String? = null)

data class JobProgress(
    val fileName: String,
    val fraction: Float,
    val elapsedSeconds: Double? = null,
    val currentLayer: Int? = null,
    val totalLayers: Int? = null,
)

data class Temperature(val current: Double?, val target: Double?)

data class PrinterStatus(
    val state: PrinterState,
    val route: ConnectionRoute,
    val job: JobProgress? = null,
    val bed: Temperature? = null,
    val toolheads: List<Toolhead> = emptyList(),
    /** Firmware's own words for anything unusual. Shown as detail only. */
    val message: String? = null,
    /** Wall-clock millis of the reading, so the UI can say how fresh it is instead of implying it is live. */
    val observedAtMillis: Long = System.currentTimeMillis(),
    /**
     * Vendor extensions, keyed "vendor.feature" (for example "snapmaker.full-spectrum"). Values are JSON-compatible
     * (String, Number, Boolean, List, Map) so they cross the adapter protocol, the local connector and the Web App
     * unchanged. Core code never interprets them; vendor-aware screens read them through typed helpers.
     */
    val extensions: Map<String, Any> = emptyMap(),
)

/**
 * What one connected printer can do, schema version [SCHEMA_VERSION]. Screens on every platform offer only what is
 * listed here; nothing branches on the printer's family or vendor name. A flag that is false means "absent or
 * explained", never a dead button.
 */
data class Capabilities(
    val uploadJob: Boolean = false,
    /** Upload and start happen together in one request (OctoPrint, PrusaLink, Bambu); a confirmed physical start. */
    val uploadAndStart: Boolean = false,
    val startPrint: Boolean = false,
    val pausePrint: Boolean = false,
    val resumePrint: Boolean = false,
    val cancelPrint: Boolean = false,
    /** Heater targets can be set from Nozzle. Temperature readings are shown whenever the status reports them. */
    val temperatures: Boolean = false,
    val motion: Boolean = false,
    val camera: Boolean = false,
    val materialState: Boolean = false,
    /** The printer can report or change what's loaded (edit a toolhead's material). */
    val materialEdit: Boolean = false,
    val loadUnload: Boolean = false,
    val multiMaterial: Boolean = false,
    val toolheadState: Boolean = false,
    val bedMesh: Boolean = false,
    val files: Boolean = false,
    val jobHistory: Boolean = false,
    val localConnection: Boolean = false,
    /** Works through the user's own private network (not a vendor relay). */
    val remoteConnection: Boolean = false,
    /** The adapter can use a vendor cloud (only ever optional and inside that adapter). */
    val vendorCloud: Boolean = false,
    /** True only when something this printer needs is available solely through a vendor account. */
    val requiresVendorAccount: Boolean = false,
    val firmwareUpdates: Boolean = false,
    val calibration: Boolean = false,
    /** Output the printer accepts, e.g. "gcode", "bgcode", "gcode.3mf". */
    val acceptedOutputs: Set<String> = setOf("gcode"),
    /** Vendor extensions the adapter provides ("snapmaker.full-spectrum", "bambu.ams", ...). */
    val vendorExtensions: Set<String> = emptySet(),
) {
    val anyControl get() = pausePrint || resumePrint || cancelPrint || temperatures || motion
    companion object {
        const val SCHEMA_VERSION = 2
        /** Nothing live: slicing and export only. */
        val EXPORT_ONLY = Capabilities()
    }
}
