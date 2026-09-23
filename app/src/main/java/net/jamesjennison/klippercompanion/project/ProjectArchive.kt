package net.jamesjennison.klippercompanion.project

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// Phase 9b: a self-contained project file (.nozzleproj, a zip): project.json + models/<file>. Importing never
// trusts the archive: entry names are matched against a strict pattern (no path components, so no zip-slip),
// extensions are whitelisted, sizes and counts are capped, and numbers must be finite.
data class ArchivePlate(val id: String, val name: String, val position: Int)
data class ArchiveObject(
    val id: String, val file: String, val plateId: String?, val offsetXMm: Float, val offsetYMm: Float, val rotationZDeg: Float, val scale: Float,
    val materialId: String?, val materialDisplayName: String?, val materialTempNozzleC: Int?, val materialTempBedC: Int?, val toolSlotIndex: Int?,
    val paintJson: String? = null, val volumesJson: String? = null,
)
data class ParsedArchive(val name: String, val plates: List<ArchivePlate>, val objects: List<ArchiveObject>)

class InvalidProjectArchive(message: String) : IllegalArgumentException(message)

object ProjectArchive {
    const val FORMAT = 1
    const val MAX_MODEL_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    const val MAX_OBJECTS = 500
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    private val SAFE_MODEL = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.(stl|3mf|obj)", RegexOption.IGNORE_CASE)

    /** [fileFor] resolves an object to its model file on disk. */
    fun write(name: String, plates: List<Plate>, objects: List<ProjectObject>, fileFor: (ProjectObject) -> File, out: OutputStream) {
        val manifest = JSONObject().put("format", FORMAT).put("name", name)
            .put("plates", JSONArray(plates.map { JSONObject().put("id", it.id).put("name", it.name).put("position", it.position) }))
            .put("objects", JSONArray(objects.map { o ->
                JSONObject().put("id", o.id).put("file", fileFor(o).name).put("plateId", o.plateId ?: JSONObject.NULL)
                    .put("offsetXMm", o.offsetXMm.toDouble()).put("offsetYMm", o.offsetYMm.toDouble()).put("rotationZDeg", o.rotationZDeg.toDouble()).put("scale", o.scale.toDouble())
                    .put("materialId", o.materialId ?: JSONObject.NULL).put("materialDisplayName", o.materialDisplayName ?: JSONObject.NULL)
                    .put("materialTempNozzleC", o.materialTempNozzleC ?: JSONObject.NULL).put("materialTempBedC", o.materialTempBedC ?: JSONObject.NULL)
                    .put("toolSlotIndex", o.toolSlotIndex ?: JSONObject.NULL)
                    .put("paintJson", o.paintJson ?: JSONObject.NULL).put("volumesJson", o.volumesJson ?: JSONObject.NULL)
            }))
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("project.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            val written = mutableSetOf<String>()
            objects.forEach { o ->
                val file = fileFor(o)
                if (written.add(file.name)) { zip.putNextEntry(ZipEntry("models/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
        }
    }

    /** Extracts model files into [modelDir] and returns the validated manifest. Throws [InvalidProjectArchive] on anything unexpected. */
    fun read(input: InputStream, modelDir: File): ParsedArchive {
        var manifest: ByteArray? = null
        val models = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (name == "project.json") {
                    manifest = zip.readBounded(MAX_MANIFEST_BYTES.toLong()) ?: throw InvalidProjectArchive("project.json is too large.")
                } else if (name.startsWith("models/") && SAFE_MODEL.matches(name.removePrefix("models/"))) {
                    if (models.size >= MAX_OBJECTS) throw InvalidProjectArchive("Too many model files.")
                    val target = File(modelDir, name.removePrefix("models/"))
                    if (!models.add(target.name)) throw InvalidProjectArchive("Duplicate model file ${target.name}.")
                    var size = 0L
                    target.outputStream().use { out ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            size += n; total += n
                            if (size > MAX_MODEL_BYTES || total > MAX_TOTAL_BYTES) throw InvalidProjectArchive("Project is too large to import.")
                            out.write(buffer, 0, n)
                        }
                    }
                } else throw InvalidProjectArchive("Unexpected entry in project file.")
            }
        }
        val json = try { JSONObject(String(manifest ?: throw InvalidProjectArchive("Missing project.json."))) } catch (e: org.json.JSONException) { throw InvalidProjectArchive("Unreadable project.json.") }
        if (json.optInt("format", -1) != FORMAT) throw InvalidProjectArchive("Unsupported project format.")
        val name = json.optString("name").trim().take(120).ifEmpty { "Imported project" }
        val plates = (json.optJSONArray("plates") ?: JSONArray()).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { ArchivePlate(it.getString("id"), it.getString("name").take(60), it.getInt("position")) } } }
        val objects = (json.optJSONArray("objects") ?: throw InvalidProjectArchive("Missing objects.")).let { a ->
            if (a.length() > MAX_OBJECTS) throw InvalidProjectArchive("Too many objects.")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val file = o.getString("file")
                if (file !in models) throw InvalidProjectArchive("Object refers to a missing model file.")
                fun f(k: String) = o.getDouble(k).also { if (!it.isFinite() || Math.abs(it) > 1_000_000) throw InvalidProjectArchive("Invalid number for $k.") }.toFloat()
                fun s(k: String) = if (o.isNull(k)) null else o.getString(k).take(200)
                fun n(k: String) = if (o.isNull(k)) null else o.getInt(k)
                val scale = f("scale"); if (scale <= 0f) throw InvalidProjectArchive("Invalid scale.")
                ArchiveObject(o.getString("id"), file, s("plateId"), f("offsetXMm"), f("offsetYMm"), f("rotationZDeg"), scale, s("materialId"), s("materialDisplayName"), n("materialTempNozzleC"), n("materialTempBedC"), n("toolSlotIndex"),
                    sanitizedPaint(o), sanitizedVolumes(o))
            }
        }
        if (plates.map { it.id }.toSet().let { ids -> objects.any { it.plateId != null && it.plateId !in ids } }) throw InvalidProjectArchive("Object refers to a missing plate.")
        return ParsedArchive(name, plates, objects)
    }

    // Re-encoded through the codecs, so a tampered archive can only ever carry well-formed, bounded, validated data.
    private fun sanitizedPaint(o: JSONObject): String? = if (o.isNull("paintJson") || !o.has("paintJson")) null else net.jamesjennison.klippercompanion.PaintCodec.decode(o.getString("paintJson")).takeIf { it.isNotEmpty() }?.let(net.jamesjennison.klippercompanion.PaintCodec::encode)
    private fun sanitizedVolumes(o: JSONObject): String? = if (o.isNull("volumesJson") || !o.has("volumesJson")) null else net.jamesjennison.klippercompanion.VolumeCodec.decode(o.getString("volumesJson")).takeIf { it.isNotEmpty() }?.let(net.jamesjennison.klippercompanion.VolumeCodec::encode)

    private fun ZipInputStream.readBounded(limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0L
        while (true) { val n = read(buffer); if (n < 0) break; total += n; if (total > limit) return null; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
}
