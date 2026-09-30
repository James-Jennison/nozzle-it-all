package com.nozzleitall.printer

import com.nozzleitall.printer.external.AdapterProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class MjpegReaderTest {
    private fun jpeg(vararg body: Int) = (listOf(0xFF, 0xD8) + body.toList() + listOf(0xFF, 0xD9)).map { it.toByte() }.toByteArray()
    private fun stream(vararg parts: Pair<ByteArray, Boolean>): ByteArray = ByteArrayOutputStream().apply {
        for ((frame, withLength) in parts) {
            write("--boundarydonotcross\r\nContent-Type: image/jpeg\r\n".toByteArray())
            if (withLength) write("Content-Length: ${frame.size}\r\nX-Timestamp: 1.0\r\n".toByteArray())
            write("\r\n".toByteArray()); write(frame); write("\r\n".toByteArray())
        }
    }.toByteArray()

    @Test fun readsFramesWithAndWithoutContentLength() {
        val a = jpeg(1, 2, 0xFF, 0x00, 3) // an escaped 0xFF inside the data is not an end marker
        val b = jpeg(9)
        val r = MjpegReader(ByteArrayInputStream(stream(a to true, b to false, a to false)))
        assertArrayEquals(a, r.next()); assertArrayEquals(b, r.next()); assertArrayEquals(a, r.next()); assertNull(r.next())
    }

    @Test fun readsBackToBackJpegsWithoutHeaders() {
        val a = jpeg(1, 2); val b = jpeg(3)
        val r = MjpegReader(ByteArrayInputStream(a + b), multipart = false)
        assertArrayEquals(a, r.next()); assertArrayEquals(b, r.next()); assertNull(r.next())
    }

    @Test fun oversizedFramesEndTheStream() {
        val big = jpeg(*IntArray(2000) { 1 })
        val r = MjpegReader(ByteArrayInputStream(stream(big to false)), maxFrame = 1000)
        assertThrows(IOException::class.java) { r.next() }
    }

    @Test fun liveUrlSurvivesTheAdapterProtocol() {
        val cam = CameraEndpoint("c", "case", CameraKind.WEBRTC, "http://p/webcam/webrtc", "http://p/webcam/snapshot.jpg", "http://p/webcam/stream.mjpg", "http://p/webcam/stream.h264")
        assertEquals(cam, AdapterProtocol.decodeCamera(AdapterProtocol.encode(cam)))
    }
}
