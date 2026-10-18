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
import kotlinx.coroutines.*

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

    @Test fun aHungCommandActuallyTimesOutBeforeStdoutCloses() {
        val start = System.nanoTime()
        try { AutoPaint.askCommand("sleep 8", "", 1); fail("expected timeout") }
        catch (e: AutoPaint.AutoPaintError) { assertTrue(e.message!!.contains("too long")) }
        assertTrue((System.nanoTime() - start) / 1e9 < 5)
    }
    @Test fun anExitedCommandCannotLeaveABackgroundChildInItsSession() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val pid = Files.createTempDirectory("ap-child").resolve("pid").toFile()
        try { AutoPaint.askCommand("sleep 20 & echo \$! > '${pid.absolutePath}'; sleep 0.1", "", 3) }
        catch (e: AutoPaint.AutoPaintError) { assertTrue(e.message!!.contains("output stream open")) }
        val child = pid.readText().trim().toLong()
        val end = System.nanoTime() + 2_000_000_000
        fun running(): Boolean = ProcessHandle.of(child).map { it.isAlive }.orElse(false) &&
            !runCatching { File("/proc/$child/status").readText().contains("State:\tZ") }.getOrDefault(false)
        while (running() && System.nanoTime() < end) Thread.sleep(10)
        assertFalse("provider child must no longer be running", running())
    }

    @Test fun appliedPaintsOnlyTheFacesTheAnswerNamed() {
        val m = AutoPaint.applied(cube(), IntArray(12) { if (it < 4) 2 else 0 })
        assertEquals(Paint.whole(2), m.paint!![0]); assertNull(m.paint!![4])
        assertEquals(setOf(2), MeshIO.paintedFilaments(m).toSet())
    }

    @Test fun aPlainClaudeCommandIsUpgradedSoNoSessionIsSavedOrAnnounced() {
        val f = Files.createTempDirectory("ap").resolve("auto-paint.json").toFile()
        AutoPaint.save(f, AutoPaint.Settings("command", "claude -p"))
        val c = AutoPaint.load(f).command
        assertTrue(c.contains("--no-session-persistence")); assertTrue(c.contains("{dir}"))
        assertEquals("my-tool --x", AutoPaint.Settings("command", "my-tool --x").also { AutoPaint.save(f, it) }.let { AutoPaint.load(f).command })
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

    @Test fun preparedIdentityRegionsAnd768PixelViewsRoundTripThroughTheRealEngine() {
        engineReady()
        val work = Files.createTempDirectory("ap-identity").toFile()
        val first = AutoPaint.prepare(cube(),"cube",filaments,"",work)
        val second = AutoPaint.prepare(cube(),"cube",filaments,"",work)
        try {
            assertNotEquals(first.dir,second.dir); assertTrue(first.stl.exists())
            assertEquals(12,first.faceRegions.size)
            assertTrue(first.faceRegions.all { it in 0 until first.seg.segmentCount })
            assertArrayEquals(first.faceRegions,second.faceRegions)
            assertEquals(6,first.seg.views.size)
            first.seg.views.forEach { assertEquals(768,javax.imageio.ImageIO.read(File(it.path)).width) }
            val review = AutoPaint.askReview(first,Provider.Command("cat >/dev/null; printf '%s' '$answer'"))
            assertArrayEquals(first.faceRegions,review.faceRegions)
            assertTrue(review.faces().any { it > 0 })
        } finally { first.discard(); second.discard() }
    }

    @Test fun draftPreviewCorrectionsApplyAndUndoAreNonDestructiveAndAtomic() = runBlocking {
        engineReady()
        val tmp = Files.createTempDirectory("ap-state").toFile()
        val app = AppState(AppPaths(File(tmp,"c"),File(tmp,"d"),File(tmp,"k")).ensure(),CoroutineScope(SupervisorJob()+Dispatchers.Default))
        val p = app.prepare; val mesh = cube().withPaint(paint = Array(12) { Paint.whole(1) })
        suspend fun idle() { withTimeout(60000) { while (p.toolBusy != null) delay(20) } }
        try {
            withContext(Dispatchers.Main) {
                p.items.add(PrepItem(1,"cube",mesh,100f,100f)); p.selected = 1; p.history.reset()
                p.autoPaintSettings = AutoPaint.Settings(command = "cat >/dev/null; printf '%s' '$answer'")
                p.aiPrepare("")
            }
            idle(); assertNotNull(p.aiPrepared)
            withContext(Dispatchers.Main) { p.aiSend() }; idle()
            assertNotNull(p.aiPreviewMesh); assertSame(mesh,p.items.single().mesh); assertFalse(p.aiCleanBoundaries)
            assertFalse(p.history.canUndo)
            // Deliberate fault injection in paint decoding; restore it immediately after the failed background rebuild.
            val originalPaint = mesh.paint!![0]; mesh.paint!![0] = "invalid-paint"
            withContext(Dispatchers.Main) { p.aiCorrect(0,2) }; idle()
            assertNull(p.aiPreviewMesh); assertNotNull(p.notice)
            mesh.paint!![0] = originalPaint
            withContext(Dispatchers.Main) { p.aiApply() }
            assertSame(mesh,p.items.single().mesh); assertFalse(p.history.canUndo)
            withContext(Dispatchers.Main) { p.aiRetryPreview() }; idle(); assertNotNull(p.aiPreviewMesh)
            mesh.paint!![0] = "invalid-paint"
            withContext(Dispatchers.Main) { p.aiSetCleanup(true) }; idle(); assertNull(p.aiPreviewMesh)
            mesh.paint!![0] = originalPaint
            withContext(Dispatchers.Main) { p.aiApply() }; assertSame(mesh,p.items.single().mesh)
            withContext(Dispatchers.Main) { p.aiRetryPreview() }; idle(); assertNotNull(p.aiPreviewMesh)
            withContext(Dispatchers.Main) { p.aiCorrect(0,2) }; idle()
            assertEquals(2,p.aiAnswer!!.slots[0]); assertSame(mesh,p.items.single().mesh)
            withContext(Dispatchers.Main) { p.aiApply() }
            assertTrue(p.items.single().mesh.isPainted); assertTrue(p.history.canUndo)
            withContext(Dispatchers.Main) { p.history.undo() }
            assertSame(mesh,p.items.single().mesh); assertFalse(p.history.canUndo)
            withContext(Dispatchers.Main) { p.history.redo() }
            assertTrue(p.items.single().mesh.isPainted)
            val beforeRace = p.items.single().mesh
            withContext(Dispatchers.Main) {
                p.items.add(PrepItem(2,"other",mesh,150f,100f))
                p.aiPrepare(""); p.selected = 2
            }
            idle(); assertNull(p.aiPrepared); assertNull(p.aiAnswer); assertNull(p.aiPreviewMesh)
            assertSame(beforeRace,p.items[0].mesh); assertSame(mesh,p.items[1].mesh)
            withContext(Dispatchers.Main) { p.selected = 1; p.aiPrepare("") }; idle()
            val staleDir = p.aiPrepared!!.dir
            withContext(Dispatchers.Main) {
                p.autoPaintSettings = AutoPaint.Settings(command = "sleep 1; cat >/dev/null; printf '%s' '$answer'")
                p.aiSend(); p.selected = 2
            }
            idle(); assertNull(p.aiPrepared); assertNull(p.aiAnswer); assertNull(p.aiPreviewMesh)
            assertFalse(staleDir.exists())
            withContext(Dispatchers.Main) { p.aiApply() }
            assertSame(beforeRace,p.items[0].mesh); assertSame(mesh,p.items[1].mesh)
        } finally { withContext(Dispatchers.Main) { p.aiDiscard() }; app.scope.cancel() }
    }

    @Test fun reviewRejectsASlotMissingFromTheOfferedPalette() {
        engineReady()
        val prepared = AutoPaint.prepare(cube(),"cube",listOf(filaments[0],filaments[1].copy(index=3)),"",Files.createTempDirectory("ap-gap").toFile())
        try {
            try { AutoPaint.askReview(prepared,Provider.Command("cat >/dev/null; printf '%s' '$answer'")); fail("unavailable slot accepted") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("unavailable")) }
        } finally { prepared.discard() }
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
