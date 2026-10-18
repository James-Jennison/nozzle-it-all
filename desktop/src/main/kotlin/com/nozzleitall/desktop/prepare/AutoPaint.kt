package com.nozzleitall.desktop.prepare

import com.nozzleitall.printer.ext.AutoPaintFormat
import com.nozzleitall.printer.ext.AutoPaintFormat.Provider
import com.nozzleitall.project.Mesh
import com.nozzleitall.project.Paint
import org.json.JSONObject
import org.json.JSONArray
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
    /**
     * Claude Code as the command: print mode, only the Read tool, allowed to read the folder with the views ({dir}), and no saved
     * session, so a run leaves no conversation behind and the Claude app doesn't announce a new session each time.
     */
    const val DEFAULT_COMMAND = "claude -p --output-format json --allowedTools Read --add-dir {dir} --no-session-persistence"

    data class Settings(val kind: String = "command", val command: String = DEFAULT_COMMAND, val baseUrl: String = "", val model: String = "", val apiKey: String = "") {
        val provider: Provider? get() = when (kind) {
            "command" -> command.takeIf { it.isNotBlank() }?.let { Provider.Command(it) }
            else -> if (baseUrl.isNotBlank() && model.isNotBlank()) Provider.OpenAiCompatible(baseUrl.trim(), model.trim(), apiKey.trim()) else null
        }
        fun toJson(): JSONObject = JSONObject().put("kind", kind).put("command", command).put("baseUrl", baseUrl).put("model", model).put("apiKey", apiKey)
    }

    fun load(file: File): Settings = runCatching {
        val j = JSONObject(file.readText())
        Settings(j.optString("kind", "command"), j.optString("command").let { if (it.trim() == "claude -p") DEFAULT_COMMAND else it }, j.optString("baseUrl"), j.optString("model"), j.optString("apiKey"))
    }.getOrDefault(Settings())

    fun save(file: File, s: Settings) {
        file.parentFile?.mkdirs(); file.writeText(s.toJson().toString())
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }

    private fun engineRun(request: JSONObject, timeoutSeconds: Long = 120): JSONObject {
        val engine = SliceEngine.locateEngine() ?: throw AutoPaintError("The slicing engine isn't installed with this copy of Nozzle It All.")
        val req = File.createTempFile("nozzle-autopaint", ".json").apply { deleteOnExit(); writeText(request.toString()) }
        try {
            val process = start(ProcessBuilder(engine.absolutePath, "--semantic-paint", req.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD))
            val out = output(process, timeoutSeconds, "Preparing the model took too long.")
            val json = runCatching { JSONObject(out) }.getOrElse { throw AutoPaintError("This engine doesn't have AI auto-paint yet.") }
            AutoPaintFormat.error(json)?.let { throw AutoPaintError(it) }
            return json
        } finally { req.delete() }
    }

    private class OwnedProcess(val process: Process, val groupKill: File?)

    /** Linux session ownership lets failure cleanup reach children even if their parent has already exited. */
    private fun start(builder: ProcessBuilder): OwnedProcess {
        val linux = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)
        val kill = if (linux) File("/bin/kill") else null
        if (linux) {
            val setsid = File("/usr/bin/setsid")
            if (!setsid.canExecute() || kill?.canExecute() != true) throw AutoPaintError("AI auto-paint needs util-linux (setsid) and /bin/kill installed.")
            builder.command(listOf(setsid.absolutePath) + builder.command())
        }
        return OwnedProcess(builder.start(), kill)
    }

    /** Drain while waiting: reading to EOF first would make the advertised timeout ineffective for a hung provider. */
    private fun output(owned: OwnedProcess, timeoutSeconds: Long, timeoutMessage: String): String {
        val p = owned.process
        var out = ""
        val reader = Thread { runCatching { out = p.inputStream.bufferedReader().readText() } }.apply { isDaemon = true; start() }
        fun killGroup() {
            owned.groupKill?.let { kill -> runCatching { ProcessBuilder(kill.absolutePath, "-KILL", "--", "-${p.pid()}").redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor(2, TimeUnit.SECONDS) } }
        }
        fun terminate() {
            // This negative PID names only the session created for this invocation, never the application's process group.
            killGroup()
            p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly()
            reader.join(1000)
            if (!reader.isAlive) runCatching { p.inputStream.close() }
            runCatching { p.outputStream.close() }; runCatching { p.errorStream.close() }
        }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            terminate()
            throw AutoPaintError(timeoutMessage)
        }
        reader.join(1000)
        if (reader.isAlive) { terminate(); throw AutoPaintError("The command left its output stream open.") }
        killGroup() // A provider invocation does not own persistent background services, even when they close stdout.
        return out
    }

    /** Runs a command line through the shell with [prompt] on stdin; its stdout is the answer. */
    internal fun askCommand(commandLine: String, prompt: String, timeoutSeconds: Long = 300): String {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val process = start(ProcessBuilder(if (windows) listOf("cmd", "/c", commandLine) else listOf("sh", "-c", commandLine)).redirectErrorStream(false))
        val p = process.process
        val errText = StringBuilder()
        val drain = Thread { runCatching { errText.append(p.errorStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
        var inputFailure: Exception? = null
        val input = Thread {
            try { p.outputStream.use { it.write(prompt.toByteArray()) } }
            catch (e: Exception) { inputFailure = e }
        }.apply { isDaemon = true; start() }
        val out = output(process, timeoutSeconds, "The command took too long to answer.")
        input.join(1000)
        if (input.isAlive || inputFailure != null) throw AutoPaintError("The command did not read the complete question.")
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

    /** The question, ready to send: the model written out for the engine, its parts, and the views the provider will see. */
    class Prepared(val dir: File, val stl: File, val seg: AutoPaintFormat.Segmentation, val filaments: List<AutoPaintFormat.Filament>, val faceRegions: IntArray) {
        fun discard() { dir.deleteRecursively() }
    }

    /** Segments [mesh] and renders the views, without asking anyone: what [ask] will send. */
    fun prepare(mesh: Mesh, name: String, filaments: List<AutoPaintFormat.Filament>, hint: String, work: File): Prepared {
        work.mkdirs()
        val dir = Files.createTempDirectory(work.toPath(), "autopaint-").toFile()
        try {
            val stl = File(dir, "model.stl").apply { writeBytes(SliceEngine.stlBytes(mesh)) }
            val guidance = "Prefer a coherent, natural palette. Segment tints are identification labels, not suggested paint colours. " +
                "Do not use every available filament merely because it is available. Use accent colours only when the views clearly support them; " +
                "do not invent red eyes or decorative patches. A geometric segment may cross a semantic feature: when uncertain keep the dominant body colour. " +
                "Keep matching features consistent across all views. The user's requested style takes precedence.\n" + hint
            val seg = AutoPaintFormat.parseSegmentation(engineRun(AutoPaintFormat.segmentRequest(stl.absolutePath, dir.absolutePath, filaments, name, guidance, size = 768)))
            // The existing engine assigns deterministic regions; an identity assignment exposes their per-face IDs without a new engine ABI.
            val identity = JSONObject().put("assignments", JSONArray((0 until seg.segmentCount).map { JSONObject().put("segment", it).put("filament", it + 1) }))
            val regions = AutoPaintFormat.parseAssignment(engineRun(AutoPaintFormat.assignRequest(stl.absolutePath, identity.toString(), seg.segmentCount))).faceFilaments
            require(regions.size == mesh.triangleCount && regions.all { it in 1..seg.segmentCount }) { "The engine returned an incomplete region map." }
            return Prepared(dir, stl, seg, filaments, IntArray(regions.size) { regions[it] - 1 })
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }

    /** Sends [prepared] to [provider] and reads the answer: the 1-based slot for each face (0 = leave it). */
    fun ask(prepared: Prepared, provider: Provider): IntArray {
        return askReview(prepared, provider).faces()
    }

    fun askReview(prepared: Prepared, provider: Provider): AutoPaintReview {
        val seg = prepared.seg
        val raw = when (provider) {
            is Provider.Command -> askCommand(provider.commandLine.replace("{dir}", prepared.dir.absolutePath), seg.commandPrompt)
            is Provider.OpenAiCompatible -> {
                val body = engineRun(AutoPaintFormat.openAiRequest(provider.model, seg.prompt, seg.views.map { it.path })).getString("body")
                askServer(provider, body)
            }
        }
        val answer = engineRun(AutoPaintFormat.replyTextRequest(provider, raw)).getString("text")
        val result = AutoPaintFormat.parseAssignment(engineRun(AutoPaintFormat.assignRequest(prepared.stl.absolutePath, answer, prepared.filaments.maxOf { it.index })))
        // Zero is a valid keep-original assignment, including an entirely unchanged draft.
        require(result.segmentToFilament.size == seg.segmentCount) { "The answer has the wrong number of regions." }
        val allowed = prepared.filaments.map { it.index }.toSet() + 0
        require(result.segmentToFilament.all { it in allowed }) { "The answer uses an unavailable filament." }
        return AutoPaintReview(prepared.faceRegions, result.segmentToFilament)
    }

    /**
     * Paints [mesh] (an object called [name]) with the slots in [filaments]: the engine builds the question, [provider] answers it, and
     * the engine reads the answer into per-face slots. Faces the model left alone keep whatever paint they had.
     */
    fun paint(mesh: Mesh, name: String, filaments: List<AutoPaintFormat.Filament>, hint: String, provider: Provider, work: File): Mesh {
        val prepared = prepare(mesh, name, filaments, hint, work)
        try { return applied(mesh, ask(prepared, provider)) } finally { prepared.discard() }
    }

    /** [mesh] with each face in [faceSlots] (1-based slot, 0 = leave) painted that slot; the mesh's other paint stays. */
    fun applied(mesh: Mesh, faceSlots: IntArray): Mesh = Paint.applyFaceSlots(mesh, faceSlots)
}
