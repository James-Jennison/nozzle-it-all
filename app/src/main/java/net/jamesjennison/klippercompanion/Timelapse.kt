package net.jamesjennison.klippercompanion

import org.json.JSONArray

interface TimelapseReader : AutoCloseable {
    fun timelapses(): List<FileInfo>
}
object Timelapses {
    // moonraker-timelapse (the Mainsail/Fluidd-compatible component) renders into this
    // extension set; anything else under the "timelapse" file root isn't a finished video.
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "webm", "mkv")
    fun parse(list: JSONArray): List<FileInfo> =
        (0 until list.length()).map { list.getJSONObject(it) }
            .map { FileInfo(it.getString("path"), it.finiteNonnegative("size")?.toLong(), it.finiteNonnegative("modified")) }
            .filter { it.path.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS }
            .distinctBy { it.path }.sortedByDescending { it.modified ?: 0.0 }
}
