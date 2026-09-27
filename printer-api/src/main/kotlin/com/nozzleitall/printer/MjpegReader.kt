package com.nozzleitall.printer

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads JPEG frames from an MJPEG stream (multipart/x-mixed-replace, as mjpeg-streamer and camera-streamer serve it).
 * Uses each part's Content-Length when present and otherwise finds the frame by its JPEG start and end markers, so the
 * boundary string doesn't need to be known. A frame larger than [maxFrame] ends the stream with an error.
 */
class MjpegReader(input: InputStream, private val maxFrame: Int = 8 * 1024 * 1024) {
    private val input = input as? BufferedInputStream ?: BufferedInputStream(input, 64 * 1024)

    /** The next complete JPEG frame, or null at the end of the stream. */
    fun next(): ByteArray? {
        var contentLength: Int? = null
        var sawHeader = false
        while (true) { // the boundary line and the part's headers, up to the blank line that ends them
            val line = readLine() ?: return null
            if (line.isEmpty()) { if (sawHeader) break else continue }
            sawHeader = true
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) contentLength = line.substring(colon + 1).trim().toIntOrNull()
        }
        val n = contentLength
        return if (n != null && n in 1..maxFrame) readExactly(n) else scanFrame()
    }

    private fun readLine(): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) return out.toString().trimEnd('\r')
            out.append(b.toChar())
            if (out.length > 4096) throw IOException("Camera stream header line too long.")
        }
    }

    private fun readExactly(n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) { val r = input.read(buf, off, n - off); if (r < 0) return null; off += r }
        return buf
    }

    /** Reads from the JPEG start marker (FFD8) through the end marker (FFD9). */
    private fun scanFrame(): ByteArray? {
        val out = ByteArrayOutputStream(256 * 1024)
        var prev = -1
        var started = false
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (!started) {
                if (prev == 0xFF && b == 0xD8) { started = true; out.write(0xFF); out.write(0xD8) }
                prev = b; continue
            }
            out.write(b)
            if (out.size() > maxFrame) throw IOException("Camera frame too large.")
            if (prev == 0xFF && b == 0xD9) return out.toByteArray()
            prev = b
        }
    }
}
