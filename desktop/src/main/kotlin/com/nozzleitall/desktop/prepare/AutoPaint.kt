package com.nozzleitall.desktop.prepare

import com.nozzleitall.printer.ext.AutoPaintFormat
import com.nozzleitall.printer.ext.AutoPaintFormat.Provider
import com.nozzleitall.project.Mesh
import com.nozzleitall.project.Paint
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * AI auto-paint on Desktop. The engine (`nozzle-engine --semantic-paint`) segments the model, renders the views and reads the
 * answer back into per-face paint; this file only carries the question to whichever provider the user chose ([Provider]: a
 * command that reads stdin, or an OpenAI-compatible server) and applies the answer to the mesh. Nothing here names a vendor.
 */
object AutoPaint {
    class AutoPaintError(message: String) : Exception(message)

    /** The provider the user configured, as saved in config/auto-paint.json (the key is kept with the rest of the app's secrets: owner-only). */
    data class Settings(val kind: String = "command", val command: String = "", val baseUrl: String = "", val model: String = "", val apiKey: String = "") {
        val provider: Provider? get() = when (kind) {
            "command" -> command.takeIf { it.isNotBlank() }?.let { Provider.Command(it) }
            else -> if (baseUrl.isNotBlank() && model.isNotBlank()) Provider.OpenAiCompatible(baseUrl.trim(), model.trim(), apiKey.trim()) else null
        }
        fun toJson(): JSONObject = JSONObject().put("kind", kind).put("command", command).put("baseUrl", baseUrl).put("model", model).put("apiKey", apiKey)
    }

    fun load(file: File): Settings = runCatching {
        val j = JSONObject(file.readText())
        Settings(j.optString("kind", "command"), j.optString("command"), j.optString("baseUrl"), j.optString("model"), j.optString("apiKey"))
    }.getOrDefault(Settings())

    fun save(file: File, s: Settings) {
        file.parentFile?.mkdirs(); file.writeText(s.toJson().toString())
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }

    private fun engineRun(request: JSONObject, timeoutSeconds: Long = 120): JSONObject {
        val engine = SliceEngine.locateEngine() ?: throw AutoPaintError("The slicing engine isn't installed with this copy of Nozzle It All.")
        val req = File.createTempFile("nozzle-autopaint", ".json").apply { deleteOnExit(); writeText(request.toString()) }
        try {
            val p = ProcessBuilder(engine.absolutePath, "--semantic-paint", req.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); throw AutoPaintError("Preparing the model took too long.") }
            val json = runCatching { JSONObject(out) }.getOrElse { throw AutoPaintError("This engine doesn't have AI auto-paint yet.") }
            AutoPaintFormat.error(json)?.let { throw AutoPaintError(it) }
            return json
        } finally { req.delete() }
    }

    /** Runs a command line through the shell with [prompt] on stdin; its stdout is the answer. */
    internal fun askCommand(commandLine: String, prompt: String, timeoutSeconds: Long = 300): String {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val p = ProcessBuilder(if (windows) listOf("cmd", "/c", commandLine) else listOf("sh", "-c", commandLine)).redirectErrorStream(false).start()
        val errText = StringBuilder()
        val drain = Thread { runCatching { errText.append(p.errorStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
        p.outputStream.use { it.write(prompt.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); throw AutoPaintError("The command took too long to answer.") }
        drain.join(1000)
        if (p.exitValue() != 0) throw AutoPaintError("The command failed (exit ${p.exitValue()}): ${errText.toString().trim().lineSequence().lastOrNull().orEmpty()}")
        return out
    }

    /** POSTs [body] to an OpenAI-compatible chat-completions endpoint and returns the response text. */
    internal fun askServer(p: Provider.OpenAiCompatible, body: String, timeoutSeconds: Long = 300): String {
        val req = HttpRequest.newBuilder(URI.create(AutoPaintFormat.chatUrl(p.baseUrl))).timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json").apply { if (p.apiKey.isNotBlank()) header("Authorization", "Bearer ${p.apiKey}") }
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() !in 200..299) throw AutoPaintError("The server answered ${res.statusCode()}: ${res.body().take(200)}")
        return res.body()
    }

    /**
     * Paints [mesh] (an object called [name]) with the slots in [filaments]: the engine builds the question, [provider] answers it, and
     * the engine reads the answer into per-face slots. Faces the model left alone keep whatever paint they had.
     */
    fun paint(mesh: Mesh, name: String, filaments: List<AutoPaintFormat.Filament>, hint: String, provider: Provider, work: File): Mesh {
        val dir = File(work, "autopaint").apply { deleteRecursively(); mkdirs() }
        try {
            val stl = File(dir, "model.stl").apply { writeBytes(SliceEngine.stlBytes(mesh)) }
            val seg = AutoPaintFormat.parseSegmentation(engineRun(AutoPaintFormat.segmentRequest(stl.absolutePath, dir.absolutePath, filaments, name, hint)))
            val raw = when (provider) {
                is Provider.Command -> askCommand(provider.commandLine, seg.commandPrompt)
                is Provider.OpenAiCompatible -> {
                    val body = engineRun(AutoPaintFormat.openAiRequest(provider.model, seg.prompt, seg.views.map { it.path })).getString("body")
                    askServer(provider, body)
                }
            }
            val answer = engineRun(AutoPaintFormat.replyTextRequest(provider, raw)).getString("text")
            val result = AutoPaintFormat.parseAssignment(engineRun(AutoPaintFormat.assignRequest(stl.absolutePath, answer, filaments.maxOf { it.index })))
            if (result.paintedFaces == 0) throw AutoPaintError("The answer left every part unpainted.")
            return applied(mesh, result.faceFilaments)
        } finally { dir.deleteRecursively() }
    }

    /** [mesh] with each face in [faceSlots] (1-based slot, 0 = leave) painted that slot; the mesh's other paint stays. */
    fun applied(mesh: Mesh, faceSlots: IntArray): Mesh = Paint.applyFaceSlots(mesh, faceSlots)
}
