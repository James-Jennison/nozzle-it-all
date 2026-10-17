package com.nozzleitall.printer.ext

import org.json.JSONArray
import org.json.JSONObject

/**
 * AI auto-paint: the engine splits a single-colour model into numbered surface segments, renders views of them, and turns a
 * vision-language model's "segment -> filament" answer into per-face paint (`nozzle-engine --semantic-paint`, JNI
 * `nativeSemanticPaint`; nozzle-engine's nozzle/bridge/native/semantic_paint_op.cpp over libslic3r's SemanticPaint). The engine
 * never talks to a provider, so the feature is provider-neutral: [Provider] says where the app sends the prompt and views.
 * Only JSON building and parsing live here, shared by Desktop (engine subprocess) and Android (JNI).
 */
object AutoPaintFormat {
    /** The feature a printer profile offers when it can print painted filaments (any printer with two or more filament slots). */
    const val FEATURE = "orca.auto-paint"

    /** Where the question goes. Nothing here names a vendor: any program that reads stdin, or any OpenAI-compatible server. */
    sealed interface Provider {
        /** A program run through the shell: it reads the prompt on stdin and prints the answer (`claude -p`, `ollama run m`, `llm`...).
         *  It cannot take inline images, so the prompt lists the view files and the program opens them itself. */
        data class Command(val commandLine: String) : Provider
        /** An OpenAI-compatible chat-completions server (OpenAI, Ollama /v1, LM Studio, vLLM, OpenRouter...) that accepts images.
         *  [baseUrl] is up to and including "/v1"; [apiKey] may be empty for a local server. */
        data class OpenAiCompatible(val baseUrl: String, val model: String, val apiKey: String = "") : Provider
    }

    /** One filament slot offered to the model. */
    data class Filament(val index: Int, val type: String, val colorHex: String, val name: String = "") {
        fun toJson(): JSONObject = JSONObject().put("index", index).put("type", type).put("color", colorHex).put("name", name)
    }

    data class View(val name: String, val path: String)

    /** What the engine made of the model: [prompt] (for a server, with the [views] sent as images), [commandPrompt] (the same
     *  text listing the view files, for a command), and the number of surface segments. */
    data class Segmentation(val segmentCount: Int, val views: List<View>, val prompt: String, val commandPrompt: String)

    /** The answer, per face: [faceFilaments] is the 1-based slot for each triangle of the model, 0 = leave unpainted. */
    data class Assignment(val segmentToFilament: List<Int>, val faceFilaments: IntArray) {
        val paintedFaces: Int get() = faceFilaments.count { it > 0 }
    }

    fun segmentRequest(modelStl: String, outDir: String, filaments: List<Filament>, objectName: String, hint: String = "", size: Int = 384): JSONObject =
        JSONObject().put("op", "segment").put("model", modelStl).put("out_dir", outDir).put("filaments", JSONArray(filaments.map { it.toJson() }))
            .put("object_name", objectName).put("hint", hint).put("size", size)

    fun openAiRequest(modelName: String, prompt: String, pngPaths: List<String>): JSONObject =
        JSONObject().put("op", "openai_request").put("model_name", modelName).put("prompt", prompt).put("png_paths", JSONArray(pngPaths))

    fun replyTextRequest(provider: Provider, providerOutput: String): JSONObject =
        JSONObject().put("op", "reply_text").put("kind", if (provider is Provider.Command) "command" else "openai").put("text", providerOutput)

    fun assignRequest(modelStl: String, reply: String, filamentCount: Int): JSONObject =
        JSONObject().put("op", "assign").put("model", modelStl).put("reply", reply).put("filament_count", filamentCount)

    fun parseSegmentation(r: JSONObject): Segmentation {
        val views = r.getJSONArray("views").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { View(it.getString("name"), it.getString("path")) } }
        return Segmentation(r.getJSONArray("segments").length(), views, r.getString("prompt"), r.getString("command_prompt"))
    }

    fun parseAssignment(r: JSONObject): Assignment {
        val faces = IntArray(r.getInt("faces"))
        var at = 0
        r.getJSONArray("runs").let { runs -> for (i in 0 until runs.length()) { val run = runs.getJSONArray(i); val n = run.getInt(0); val f = run.getInt(1)
            for (k in 0 until n) faces[at + k] = f; at += n } }
        require(at == faces.size) { "The engine's paint covers $at of ${faces.size} faces." }
        val seg = r.getJSONArray("segment_to_filament").let { a -> (0 until a.length()).map { a.getInt(it) } }
        return Assignment(seg, faces)
    }

    /** The error an engine response carries, or null when it succeeded. */
    fun error(r: JSONObject): String? = r.optString("error").takeIf { it.isNotBlank() }

    /** The chat-completions endpoint for a [Provider.OpenAiCompatible.baseUrl]. */
    fun chatUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/chat/completions"
}
