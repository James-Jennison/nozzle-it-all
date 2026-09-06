package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.runtime.mutableStateOf
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class MjpegCameraTest {
    @get:Rule val compose=createComposeRule()
    @Test fun multipartFixtureProducesRenderedFrames() {
        val server=ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        val sent=AtomicInteger(0)
        val frames=listOf(android.graphics.Color.GREEN,android.graphics.Color.RED).map { color ->
            val bitmap=android.graphics.Bitmap.createBitmap(64,48,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(color)}
            val buffer=java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,buffer);bitmap.recycle()
            buffer.toByteArray()
        }
        val worker=Thread {
            runCatching {server.accept().use { socket ->
                socket.soTimeout=5000
                val input=socket.getInputStream().bufferedReader();while(!input.readLine().isNullOrEmpty()) {}
                val out=socket.getOutputStream();out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n".toByteArray())
                repeat(300) {
                    val frame=frames[(it/30)%frames.size]
                    out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray());out.write(frame);out.write("\r\n".toByteArray());out.flush();sent.incrementAndGet();Thread.sleep(16)
                }
            }}
        }.apply {isDaemon=true;start()}
        try {
            val visible=mutableStateOf(true)
            val address="http://127.0.0.1:${server.localPort}/"
            compose.setContent {CompanionTheme {Column {
                Text("Test camera — alternating red and green frames")
                if(visible.value) MjpegCamera(address,Camera("Fixture","","/stream","mjpegstreamer"))
            }}}
            compose.waitUntil(10000) {compose.onAllNodesWithText("Live MJPEG",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("Live printer camera").assertIsDisplayed()
            fun renderedRed(): Float {
                val pixels=compose.onNodeWithContentDescription("Live printer camera").captureToImage().toPixelMap()
                return pixels[pixels.width/2,pixels.height/2].red
            }
            val firstRed=renderedRed()
            compose.waitUntil(3000) {kotlin.math.abs(renderedRed()-firstRed)>0.5f}
            assertTrue(sent.get()>1)
            compose.runOnIdle {visible.value=false}
            compose.waitUntil(2000) {!worker.isAlive}
            assertTrue("Hidden player must close its stream before fixture exhaustion",sent.get()<300)
        } finally {server.close();worker.join(5000)}
    }
}
