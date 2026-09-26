package net.jamesjennison.klippercompanion

import org.json.JSONObject
import java.util.Locale

// Ordinal-independent persistence: PrinterPreferences stores/reads this by name(), not ordinal.
// SNAPMAKER_U1 = stock firmware (Bespok3d-capable); SNAPMAKER_U1_PAXX = PAXX/extended firmware (multiACE, no Bespok3d).
enum class PrinterKind { GENERIC_KLIPPER, SNAPMAKER_U1_PAXX, BAMBU_LAB, PRUSA_LINK, OCTOPRINT, SNAPMAKER_U1 }
// WO-13: which OrcaSlicer profile family a printer needs - a hardware-model distinction, not a
// protocol one (unlike PrinterKind - both SNAPMAKER_U1 and ELEGOO_CENTAURI_CARBON speak
// GENERIC_KLIPPER-shaped Moonraker, but need different slicer profiles). Null means "no slicing
// profile declared for this printer yet"; also ordinal-independent persistence, by name().
// SlicingPrinterModel itself is generated: see SlicingModelCatalog.kt (scripts/bundle_vendor_profiles.py).
// serial identifies a BAMBU_LAB printer to its own MQTT/FTPS/camera transports and is unused by
// every other kind. It is not a credential (apiKey is), so it persists alongside name/cameraId.
// slicingModel/declaredFirmwareVersion are WO-13's firmware-identity fields: slicingModel picks
// the profile family; declaredFirmwareVersion is the last *confirmed* (not just guessed) firmware
// version string for ELEGOO_CENTAURI_CARBON profiles specifically, set only by a live read (see
// PrinterModel's detectFirmware) - print-generation still re-reads live and never trusts this
// alone for the actual go/no-go decision (FirmwareIdentity.kt's checkCentauriCarbonFirmwareMatch
// takes a live reading), but it drives the UI ("this printer was last confirmed as COSMOS
// 26.08.0 - revalidate?") and lets profile selection happen before a printer is even reachable.
data class PrinterProfile(val address: String, val name: String = "", val favorite: Boolean = false, val cameraId: String = "", val apiKey: String = "", val kind: PrinterKind = PrinterKind.GENERIC_KLIPPER, val serial: String = "", val slicingModel: SlicingPrinterModel? = null, val declaredFirmwareVersion: String = "") {
    val label: String get() = name.ifBlank { address }
    // Null for every slicingModel except ELEGOO_CENTAURI_CARBON, and null there too until a firmware
    // version has actually been confirmed (declaredFirmwareVersion blank, or unparseable - see
    // FirmwareIdentity.kt) - an unconfirmed/unparseable declaration must never resolve to a generation.
    val declaredCosmosProfileGeneration: CosmosProfileGeneration? get() =
        if (slicingModel != SlicingPrinterModel.ELEGOO_CENTAURI_CARBON) null
        else cosmosRequiresCurrentProfile(declaredFirmwareVersion)?.let { if (it) CosmosProfileGeneration.CURRENT else CosmosProfileGeneration.LEGACY }
}
data class FileInfo(val path: String, val size: Long? = null, val modified: Double? = null)
data class FileMetadata(val filename: String, val estimatedSeconds: Double? = null, val layers: Int? = null,
    val filamentMm: Double? = null, val filamentGrams: Double? = null, val slicer: String = "", val thumbnail: String? = null)
data class PrintJob(val id: String, val filename: String, val status: String, val started: Double?, val duration: Double?, val filamentMm: Double?)
data class HistoryPage(val jobs: List<PrintJob>, val pageSize: Int)
fun JSONObject.finiteNonnegative(key: String): Double? = optDouble(key).takeIf { it.isFinite() && it >= 0 }
fun formatDuration(seconds: Double?): String {
    if(seconds == null || !seconds.isFinite() || seconds < 0 || seconds > 315360000) return "Unknown"
    val minutes = (seconds / 60).toLong()
    return if(minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
fun formatMaterial(mm: Double?): String = mm?.let { String.format(Locale.US, "%.2f m", it / 1000) } ?: "Unknown"
fun estimatedRemaining(snapshot: PrinterSnapshot?, metadata: FileMetadata?): Double? {
    if(snapshot?.state != "printing" || metadata?.filename != snapshot.filename) return null
    val total = metadata.estimatedSeconds ?: return null
    val elapsed = snapshot.printDuration ?: return null
    // A slicer estimate is not a deadline. Once exceeded, don't claim completion is imminent.
    return (total - elapsed).takeIf { total > 0 && it > 0 }
}
/** "now + remaining" as a 12-hour clock time (e.g. "3:45 PM") - the app and the widget both show
 * this alongside the remaining duration, since "2h 15m left" doesn't answer "will it be done
 * before I go to bed" nearly as directly as a clock time does. */
fun estimatedFinishClockTime(remainingSeconds: Double?): String? {
    if(remainingSeconds == null || !remainingSeconds.isFinite() || remainingSeconds <= 0) return null
    val finish = java.time.LocalTime.now().plusSeconds(remainingSeconds.toLong())
    return finish.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US))
}
