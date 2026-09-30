package com.nozzleitall.desktop

import com.nozzleitall.desktop.camera.FfmpegVideo
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class FfmpegVideoTest {
    /** A raw H.264 stream (what camera-streamer sends) comes out as a steady run of JPEG frames. */
    @Test fun turnsH264IntoFrames() {
        val ffmpeg = FfmpegVideo.locate()
        assumeTrue("ffmpeg installed", ffmpeg != null)
        val h264 = ProcessBuilder(ffmpeg!!.absolutePath, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=30",
            "-t", "2", "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-f", "h264", "pipe:1").start().inputStream.readBytes()
        assertTrue("test clip encoded", h264.size > 1000)
        FfmpegVideo.start(ffmpeg, h264.inputStream()).use { v ->
            val frames = generateSequence { v.next() }.take(80).toList()
            assertTrue("most of 60 frames decoded, got ${frames.size}", frames.size >= 50)
            frames.forEach { assertTrue(it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte()) }
        }
    }
}
