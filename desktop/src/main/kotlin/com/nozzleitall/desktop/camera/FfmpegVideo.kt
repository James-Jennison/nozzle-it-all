package com.nozzleitall.desktop.camera

import com.nozzleitall.printer.MjpegReader
import java.io.Closeable
import java.io.File
import java.io.InputStream

/**
 * Plays a printer's raw H.264 camera stream through ffmpeg, which turns it into 720p JPEG frames for the screen. The
 * stream itself is fetched by the printer's adapter (same host check and API key as everything else) and piped into
 * ffmpeg; ffmpeg never touches the network. Frames are passed on as they decode; the picture is 720p for the screen.
 */
class FfmpegVideo private constructor(private val process: Process, private val source: InputStream) : Closeable {
    private val frames = MjpegReader(process.inputStream, multipart = false)
    private val pump = Thread({
        try { source.use { src -> process.outputStream.use { out -> src.copyTo(out, 64 * 1024) } } } catch (_: Exception) { }
    }, "nozzle-camera-pump").apply { isDaemon = true; start() }

    /** The next frame as JPEG bytes, or null when the stream ended. */
    fun next(): ByteArray? = frames.next()

    override fun close() {
        runCatching { source.close() }
        process.destroy()
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        /** ffmpeg from the NOZZLE_FFMPEG variable or the usual places; null when it isn't installed. */
        fun locate(env: Map<String, String> = System.getenv()): File? =
            (listOfNotNull(env["NOZZLE_FFMPEG"]) + (env["PATH"].orEmpty().split(File.pathSeparator).map { "$it/ffmpeg" }) +
                listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/homebrew/bin/ffmpeg"))
                .map(::File).firstOrNull { it.isFile && it.canExecute() }

        fun start(ffmpeg: File, h264: InputStream, width: Int = 1280): FfmpegVideo {
            val p = ProcessBuilder(ffmpeg.absolutePath, "-hide_banner", "-loglevel", "error",
                // Start from the first frames it sees. (-fflags nobuffer / -flags low_delay start faster on the U1 but make
                // ffmpeg drop every frame of ordinary encoder output, so they're left out.)
                "-probesize", "32", "-analyzeduration", "0",
                "-f", "h264", "-i", "pipe:0",
                "-vf", "scale=$width:-2:out_range=full,format=yuvj420p", "-f", "mjpeg", "-q:v", "5", "pipe:1")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            return FfmpegVideo(p, h264)
        }
    }
}
