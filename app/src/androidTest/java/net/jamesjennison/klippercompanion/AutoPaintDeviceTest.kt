package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nozzleitall.printer.ext.AutoPaintFormat
import com.nozzleitall.project.MeshIO
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

// AI auto-paint end to end on the device: the engine segments a cube and renders its views, a local OpenAI-compatible server (a socket
// in this test, standing in for any provider) answers, the answer becomes a 3MF with per-face paint, and a two-tool slice of that file
// really changes tool.
@RunWith(AndroidJUnit4::class)
class AutoPaintDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun cube(): File = File(ctx.cacheDir, "autopaint-cube.stl").also { f -> InstrumentationRegistry.getInstrumentation().context.assets.open("cube.stl").use { it.copyTo(f.outputStream()) } }

    /** Answers one chat-completions request with [answer]; the request body is kept in [seen]. */
    private fun server(answer: String, seen: MutableList<String>): Pair<ServerSocket, Thread> {
        val s = ServerSocket(0)
        val t = thread {
            runCatching {
                s.accept().use { c ->
                    val input = c.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                    val len = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(head)!!.groupValues[1].toInt()
                    val body = ByteArray(len); var n = 0
                    while (n < len) n += input.read(body, n, len - n)
                    seen += String(body)
                    val payload = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", answer)))).toString().toByteArray()
                    c.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(payload); flush() }
                }
            }
        }
        return s to t
    }

    @Test fun anAnswerFromAServerBecomesPaintThatSlicesWithAToolChange() = runBlocking {
        val seen = mutableListOf<String>()
        val answer = """{"assignments":[""" + (0..2).joinToString(",") { """{"segment":$it,"filament":2}""" } + "]}"
        val (socket, thread) = server(answer, seen)
        val target = File(ctx.cacheDir, "autopaint-out.3mf").apply { delete() }
        val painted = try {
            AutoPaint.paint(cube(), target, "cube", listOf(AutoPaintFormat.Filament(1, "PLA", "#FFFFFF"), AutoPaintFormat.Filament(2, "PLA", "#CC0000")), "a cube",
                AutoPaintFormat.Provider.OpenAiCompatible("http://127.0.0.1:${socket.localPort}/v1", "test-model"), ctx.cacheDir)
        } finally { socket.close(); thread.join(2000) }
        assertTrue("some faces painted", painted > 0)
        assertTrue("the request went to the chat endpoint with images", seen.single().contains("image_url") && seen.single().contains("test-model"))
        val mesh = MeshIO.read(target)
        assertTrue("the file carries per-face paint", mesh.isPainted)
        assertTrue("filament 2 is the painted slot", MeshIO.paintedFilaments(mesh).contains(2))

        val profile = PrinterProfile("http://192.168.1.110/", "U1", slicingModel = SlicingPrinterModel.SNAPMAKER_U1)
        val pla = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }
        val outcome = SlicingCoordinator.sliceProject(ctx, listOf(target to ModelTransform()), profile, toolSlotIndices = listOf(1), slotMaterials = listOf(pla, pla, null, null))
        assertTrue("expected Success, got $outcome", outcome is SliceOutcome.Success)
        val gcode = (outcome as SliceOutcome.Success).gcode.readLines()
        assertTrue("expected a change to tool 2 in the painted print", gcode.any { it.trim() == "T1" })
    }

    @Test fun aServerErrorIsReportedNotSwallowed() {
        val (socket, thread) = ServerSocket(0).let { s -> s to thread { runCatching { s.accept().use { c -> c.getInputStream().read(ByteArray(8192)); c.getOutputStream().apply { write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 2\r\nConnection: close\r\n\r\nno".toByteArray()); flush() } } } } }
        val e = try {
            AutoPaint.paint(cube(), File(ctx.cacheDir, "autopaint-err.3mf"), "cube", listOf(AutoPaintFormat.Filament(1, "PLA", "#FFFFFF"), AutoPaintFormat.Filament(2, "PLA", "#CC0000")), "",
                AutoPaintFormat.Provider.OpenAiCompatible("http://127.0.0.1:${socket.localPort}/v1", "m"), ctx.cacheDir); null
        } catch (e: AutoPaint.AutoPaintError) { e } finally { socket.close(); thread.join(2000) }
        assertNotNull(e); assertTrue(e!!.message!!.contains("401"))
    }
}
