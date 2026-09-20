package net.jamesjennison.klippercompanion

import org.json.JSONObject
import java.util.Locale

// Ordinal-independent persistence: PrinterPreferences stores/reads this by name(), not ordinal.
enum class PrinterKind { GENERIC_KLIPPER, SNAPMAKER_U1_PAXX, BAMBU_LAB }
data class PrinterProfile(val address: String, val name: String = "", val favorite: Boolean = false, val cameraId: String = "", val apiKey: String = "", val kind: PrinterKind = PrinterKind.GENERIC_KLIPPER) {
    val label: String get() = name.ifBlank { address }
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
