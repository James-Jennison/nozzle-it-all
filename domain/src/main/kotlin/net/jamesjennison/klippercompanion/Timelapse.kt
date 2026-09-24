package net.jamesjennison.klippercompanion

import org.json.JSONArray
import java.util.Locale

interface TimelapseReader : AutoCloseable {
    fun timelapses(): List<TimelapseClip>
    fun timelapseThumbnail(path: String): ByteArray
    fun timelapseVideoUrl(path: String): TimelapseVideoUrl
    // Phase 7 (Consumer Slicer Plan §16): a real manual render trigger - moonraker-timelapse's
    // own documented POST /machine/timelapse/render (mainsail-crew/moonraker-timelapse's
    // component source, read directly, not assumed). Distinct from every other mutating command
    // in this app (which all go through the generic PrinterCommand/execute() pipeline and expect
    // a bare {"result": "ok"}) because this endpoint returns a real, richer JSON object -
    // {"action":"render","status":...,"msg":...,...} - so it's called directly against this
    // reader instead, the same way timelapses()/meshStatus() already are for reads.
    fun renderTimelapse(): TimelapseRenderResult = throw ApiFailure("Timelapse rendering is not supported for this printer.")
}
data class TimelapseClip(val path: String, val size: Long?, val modified: Double?, val posterPath: String?)
// status mirrors moonraker-timelapse's own real values (timelapse.py's render()): "started" is
// the only real success case; "skipped" (no frames captured), "running" (already rendering) and
// "error" (e.g. ffmpeg missing) are all real, non-exceptional outcomes worth showing as-is rather
// than folding into a generic ApiFailure.
data class TimelapseRenderResult(val status: String, val message: String) {
    val succeeded: Boolean get() = status == "started"
}
object Timelapses {
    // moonraker-timelapse (the Mainsail/Fluidd-compatible component) renders into this
    // extension set; anything else under the "timelapse" file root isn't a finished video.
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "webm", "mkv")
    private val POSTER_EXTENSIONS = setOf("jpg", "jpeg")
    private fun ext(path: String) = path.substringAfterLast('.', "").lowercase()
    private fun stem(path: String) = path.substringBeforeLast('.')
    fun parse(list: JSONArray): List<TimelapseClip> {
        val files = (0 until list.length()).map { list.getJSONObject(it) }
            .map { FileInfo(it.getString("path"), it.finiteNonnegative("size")?.toLong(), it.finiteNonnegative("modified")) }
            .distinctBy { it.path }
        // moonraker-timelapse writes <name>.jpg alongside <name>.mp4 in the same root as its
        // last-rendered-frame poster; pairing by identical stem is the only link between them.
        val posters = files.filter { ext(it.path) in POSTER_EXTENSIONS }.associateBy { stem(it.path) }
        return files.filter { ext(it.path) in VIDEO_EXTENSIONS }
            .map { TimelapseClip(it.path, it.size, it.modified, posters[stem(it.path)]?.path) }
            .sortedByDescending { it.modified ?: 0.0 }
    }
}
data class TimelapseVideoUrl(val url: String, val headers: Map<String, String> = emptyMap())
fun formatFileSize(bytes: Long?): String = when {
    bytes == null || bytes < 0 -> "Unknown size"
    bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
/** Today/Yesterday/an absolute date, in the device's local timezone - matches how history reads. */
fun dayLabel(epochSeconds: Double, now: java.util.Date = java.util.Date()): String {
    val calendar = java.util.Calendar.getInstance()
    fun dayStart(date: java.util.Date): Long { calendar.time = date; calendar.set(java.util.Calendar.HOUR_OF_DAY, 0); calendar.set(java.util.Calendar.MINUTE, 0); calendar.set(java.util.Calendar.SECOND, 0); calendar.set(java.util.Calendar.MILLISECOND, 0); return calendar.timeInMillis }
    val today = dayStart(now)
    val day = dayStart(java.util.Date((epochSeconds * 1000).toLong()))
    val diffDays = (today - day) / 86_400_000L
    return when (diffDays) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> java.text.SimpleDateFormat("EEEE, MMM d", Locale.US).format(java.util.Date(day))
    }
}
