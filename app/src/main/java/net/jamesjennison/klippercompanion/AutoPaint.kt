package net.jamesjennison.klippercompanion

import android.content.SharedPreferences
import com.nozzleitall.printer.ext.AutoPaintFormat
import com.nozzleitall.printer.ext.AutoPaintFormat.Provider
import com.nozzleitall.project.MeshIO
import com.nozzleitall.project.ModelObject
import com.nozzleitall.project.Paint
import com.nozzleitall.project.Project3mf
import com.nozzleitall.project.ThreeMf
import org.json.JSONObject
import org.orcaslicer.engine.NativeEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI auto-paint on Android. The engine (NativeEngine.nativeSemanticPaint, the same code as Desktop's `--semantic-paint`) segments the
 * model, renders the views and reads the answer into per-face paint. This file carries the question to an OpenAI-compatible server the
 * user chose (OpenAI, Ollama, LM Studio, OpenRouter... nothing names a vendor; a command-line provider needs a shell, which Android
 * doesn't give an app) and writes the answer as a 3MF with per-face paint, which the slicing engine reads as ordinary multi-material paint.
 */
object AutoPaint {
    class AutoPaintError(message: String) : Exception(message)

    /** The server the user configured. The key lives in the app's encrypted credential store, never in the plain preferences. */
    data class Settings(val baseUrl: String = "", val model: String = "", val apiKey: String = "") {
        val provider: Provider.OpenAiCompatible? get() = if (baseUrl.isNotBlank() && model.isNotBlank()) Provider.OpenAiCompatible(baseUrl.trim(), model.trim(), apiKey.trim()) else null
    }

    private const val KEY = "autopaint:server"

    fun load(secrets: SharedPreferences): Settings = runCatching {
        val j = JSONObject(secrets.getString(KEY, null) ?: return Settings())
        Settings(j.optString("baseUrl"), j.optString("model"), j.optString("apiKey"))
    }.getOrDefault(Settings())

    fun save(secrets: SharedPreferences, s: Settings) {
        secrets.edit().putString(KEY, JSONObject().put("baseUrl", s.baseUrl).put("model", s.model).put("apiKey", s.apiKey).toString()).apply()
    }

    private fun engine(request: JSONObject): JSONObject {
        val out = runCatching { JSONObject(NativeEngine.nativeSemanticPaint(request.toString())) }.getOrElse { throw AutoPaintError("The slicing engine didn't answer the auto-paint request.") }
        AutoPaintFormat.error(out)?.let { throw AutoPaintError(it) }
        return out
    }

    /** POSTs [body] to the server's chat-completions endpoint and returns the response text. */
    internal fun askServer(p: Provider.OpenAiCompatible, body: String, timeoutMs: Int = 300_000): String {
        val c = URL(AutoPaintFormat.chatUrl(p.baseUrl)).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = timeoutMs; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (p.apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw AutoPaintError("The server answered $code: ${text.take(200)}")
            return text
        } catch (e: java.io.IOException) { throw AutoPaintError("Couldn't reach the server: ${e.message}") }
        finally { c.disconnect() }
    }

    /**
     * Paints the model in [source] with the slots in [filaments] and writes the result, a 3MF with per-face paint in the model's own
     * frame, to [target]. Throws [AutoPaintError] with a message fit to show when it can't. Paint the model already had stays where
     * the answer left a face alone.
     */
    fun paint(source: File, target: File, name: String, filaments: List<AutoPaintFormat.Filament>, hint: String, provider: Provider.OpenAiCompatible, work: File): Int {
        val dir = File(work, "autopaint").apply { deleteRecursively(); mkdirs() }
        try {
            val mesh = try { MeshIO.read(source) } catch (e: Exception) { throw AutoPaintError("Couldn't read the model: ${e.message}") }
            val stl = File(dir, "model.stl").apply { writeBytes(MeshIO.stlBytes(mesh)) }
            val seg = AutoPaintFormat.parseSegmentation(engine(AutoPaintFormat.segmentRequest(stl.absolutePath, dir.absolutePath, filaments, name, hint)))
            val body = engine(AutoPaintFormat.openAiRequest(provider.model, seg.prompt, seg.views.map { it.path })).getString("body")
            val answer = engine(AutoPaintFormat.replyTextRequest(provider, askServer(provider, body))).getString("text")
            val result = AutoPaintFormat.parseAssignment(engine(AutoPaintFormat.assignRequest(stl.absolutePath, answer, filaments.maxOf { it.index })))
            if (result.paintedFaces == 0) throw AutoPaintError("The answer left every part unpainted.")
            val painted = Paint.applyFaceSlots(mesh, result.faceFilaments)
            ThreeMf.writeAtomically(Project3mf(listOf(ModelObject(1, name.ifBlank { "model" }, painted))), target)
            return result.paintedFaces
        } finally { dir.deleteRecursively() }
    }
}
