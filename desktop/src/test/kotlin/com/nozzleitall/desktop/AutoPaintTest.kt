package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.printer.ext.AutoPaintFormat
import com.nozzleitall.printer.ext.AutoPaintFormat.Provider
import com.nozzleitall.project.*
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/** AI auto-paint: the engine segments and reads the answer, the provider (a command, or a local OpenAI-style server) answers. */
class AutoPaintTest {
    private fun engineReady() = assumeTrue("slicing engine available", SliceEngine.locateEngine() != null)
    private val filaments = listOf(AutoPaintFormat.Filament(1, "PLA", "#FF0000", "Red"), AutoPaintFormat.Filament(2, "PLA", "#0000FF", "Blue"))

    /** A 20 mm cube, 12 triangles, two per side. */
    private fun cube(): Mesh {
        val v = FloatArray(24); var k = 0
        for (z in listOf(0f, 20f)) for (y in listOf(0f, 20f)) for (x in listOf(0f, 20f)) { v[k++] = x; v[k++] = y; v[k++] = z }
        val q = listOf(intArrayOf(0, 2, 3, 1), intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4), intArrayOf(2, 6, 7, 3), intArrayOf(0, 4, 6, 2), intArrayOf(1, 3, 7, 5))
        val t = q.flatMap { listOf(it[0], it[1], it[2], it[0], it[2], it[3]) }.toIntArray()
        return Mesh(v, t)
    }

    private val answer = """Here you go: {"object":"cube","assignments":[{"segment":0,"filament":1},{"segment":1,"filament":2},{"segment":2,"filament":2}],"notes":"x"}"""

    @Test fun appliedPaintsOnlyTheFacesTheAnswerNamed() {
        val m = AutoPaint.applied(cube(), IntArray(12) { if (it < 4) 2 else 0 })
        assertEquals(Paint.whole(2), m.paint!![0]); assertNull(m.paint!![4])
        assertEquals(setOf(2), MeshIO.paintedFilaments(m).toSet())
    }

    @Test fun settingsSurviveARestartAndStayOwnerOnly() {
        val f = Files.createTempDirectory("ap").resolve("auto-paint.json").toFile()
        AutoPaint.save(f, AutoPaint.Settings("server", "", "http://h/v1", "m", "k"))
        val s = AutoPaint.load(f)
        assertEquals(Provider.OpenAiCompatible("http://h/v1", "m", "k"), s.provider)
        assertNull(AutoPaint.Settings("command", "").provider)
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
    }

    @Test fun aCommandProviderPaintsTheModel() {
        engineReady()
        val work = Files.createTempDirectory("ap-work").toFile()
        // The question arrives on stdin and lists the view files; the "model" here is a shell that answers with fixed JSON.
        val seen = File(work, "question.txt")
        val cmd = "cat > '${seen.absolutePath}'; printf '%s' '${answer.replace("'", "'\\''")}'"
        val painted = AutoPaint.paint(cube(), "cube", filaments, "festive", Provider.Command(cmd), work)
        assertTrue(painted.isPainted)
        assertTrue("the question names the view files and carries the hint", seen.readText().let { "view-front.png" in it && "festive" in it })
        assertEquals(setOf(1, 2), MeshIO.paintedFilaments(painted).toSet())
        assertFalse(File(work, "autopaint").exists())
    }

    @Test fun anOpenAiCompatibleServerGetsTheViewsAndPaintsTheModel() {
        engineReady()
        var body = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val reply = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)))).toString().toByteArray()
            ex.sendResponseHeaders(200, reply.size.toLong()); ex.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val work = Files.createTempDirectory("ap-work").toFile()
            val painted = AutoPaint.paint(cube(), "cube", filaments, "", Provider.OpenAiCompatible("http://127.0.0.1:${server.address.port}/v1", "vision-1"), work)
            assertTrue(painted.isPainted)
            assertEquals("vision-1", JSONObject(body).getString("model"))
            assertTrue("views go as images", body.contains("data:image/png;base64,"))
        } finally { server.stop(0) }
    }

    @Test fun anUnreadableAnswerFailsWithoutPaintingAnything() {
        engineReady()
        val work = Files.createTempDirectory("ap-work").toFile()
        try { AutoPaint.paint(cube(), "cube", filaments, "", Provider.Command("cat >/dev/null; echo sorry"), work); fail("expected an error") }
        catch (e: AutoPaint.AutoPaintError) { assertTrue(e.message!!.contains("filament")) }
    }
}
