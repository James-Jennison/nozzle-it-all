package com.nozzleitall.printer

// The shared printer model. Every name here has an entry in design/terminology/glossary.json (the family's single
// source of truth for user-facing terms); GlossaryConsistencyTest keeps the two in step, so Desktop, Mobile and Web
// cannot drift into different names for the same state.

/** Which firmware a printer runs. It picks the adapter; it is never inferred from a vendor cloud. */
enum class FirmwareFamily(val glossaryId: String) {
    /** Snapmaker U1 running PAXX extended firmware in LAN mode: the primary, native platform. */
    PAXX("firmware.paxx"),
    /** Snapmaker U1 on stock firmware. Served only by the optional Stock U1 adapter. */
    STOCK_U1("firmware.stock-u1"),
    /** Any other Klipper printer reached through Moonraker. */
    KLIPPER("firmware.klipper"),
}

/**
 * How Nozzle reaches a printer. [VENDOR_CLOUD] is only ever reported by an optional, out-of-process adapter; the
 * core never opens a cloud route itself.
 */
enum class ConnectionRoute(val glossaryId: String) {
    LAN("route.lan"),
    /** The user's own private network (Tailscale or similar) carrying LAN traffic; Nozzle relays nothing. */
    PRIVATE_NETWORK("route.private-network"),
    VENDOR_CLOUD("route.vendor-cloud"),
}

/**
 * One state vocabulary for every platform. Raw firmware strings are mapped onto it by adapters; the UI never shows a
 * raw firmware string as a state.
 */
enum class PrinterState(val glossaryId: String, val isActiveJob: Boolean = false) {
    OFFLINE("state.offline"),
    CONNECTING("state.connecting"),
    /** Reachable, but the firmware is still starting or reports it is not ready (Klippy not ready, shutdown). */
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
    UNKNOWN("state.unknown"),
}

data class PrinterIdentity(
    /** Stable local id chosen by Nozzle when the printer is added (not a vendor serial or cloud id). */
    val id: String,
    val displayName: String,
    val model: String,
    val firmware: FirmwareFamily,
    /** The address the user gave (for example http://192.168.1.40 or http://u1.tailnet.ts.net). */
    val address: String,
)

/** A spool as the printer reports it. Every field is optional because firmware reports partial data. */
data class Material(
    val vendor: String? = null,
    val type: String? = null,
    val subType: String? = null,
    /** "#RRGGBB", upper case. */
    val colorHex: String? = null,
    /** True when the printer read the spool from an RFID tag rather than a manual entry. */
    val fromTag: Boolean = false,
) {
    val label: String get() = listOfNotNull(vendor, type, subType).joinToString(" ").ifBlank { "Unknown material" }
}

data class Toolhead(
    /** 0-based physical index. Displayed as index + 1 ("Toolhead 1"). */
    val index: Int,
    val nozzleTemperature: Double? = null,
    val nozzleTarget: Double? = null,
    val nozzleDiameterMm: Double? = null,
    val loaded: Boolean = false,
    val material: Material? = null,
    val active: Boolean = false,
)

/**
 * Full Spectrum is Snapmaker's multi-toolhead colour mixing for the U1. The printer side is the set of loaded colours
 * a plate can draw from; the slicing side lives in the Advanced Workspace. [available] is false (with a reason) when
 * the printer cannot currently offer it, rather than hiding the feature.
 */
data class FullSpectrumState(
    val available: Boolean,
    val palette: List<String> = emptyList(),
    val unavailableReason: String? = null,
)

enum class CameraKind { WEBRTC, MJPEG_STREAM, SNAPSHOT }

data class CameraEndpoint(val id: String, val name: String, val kind: CameraKind, val url: String, val snapshotUrl: String? = null)

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
    val fullSpectrum: FullSpectrumState = FullSpectrumState(false, unavailableReason = "Not reported by this printer."),
    /** Firmware's own words for anything unusual (for example Klipper's shutdown message). Shown as detail only. */
    val message: String? = null,
    /** Wall-clock millis of the reading, so the UI can say how fresh it is instead of implying it is live. */
    val observedAtMillis: Long = System.currentTimeMillis(),
)

/** What an adapter can do for one connected printer. The UI offers only what is listed here. */
data class Capabilities(
    val camera: Boolean = false,
    val upload: Boolean = false,
    val startJob: Boolean = false,
    val pauseResumeCancel: Boolean = false,
    val temperatures: Boolean = false,
    val motion: Boolean = false,
    val materials: Boolean = false,
    val materialEdit: Boolean = false,
    val fullSpectrum: Boolean = false,
    /** True only for adapters that need a vendor account. The PAXX adapter must always report false. */
    val requiresVendorAccount: Boolean = false,
)
