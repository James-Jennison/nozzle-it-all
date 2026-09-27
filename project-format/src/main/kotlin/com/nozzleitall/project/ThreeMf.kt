package com.nozzleitall.project

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** A triangle mesh in millimetres: [vertices] is x,y,z triples, [triangles] is vertex-index triples. */
class Mesh(val vertices: FloatArray, val triangles: IntArray) {
    val vertexCount get() = vertices.size / 3
    val triangleCount get() = triangles.size / 3
    fun bounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in vertices.indices step 3) for (a in 0..2) { b[a] = minOf(b[a], vertices[i + a]); b[a + 3] = maxOf(b[a + 3], vertices[i + a]) }
        return b
    }
}

/** A 3MF affine transform: the 12 numbers of the `transform` attribute (3x3 rotation/scale column-major rows + translation). */
data class Transform(val m: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)) {
    init { require(m.size == 12 && m.all { it.isFinite() }) { "A 3MF transform needs 12 finite numbers." } }
    fun apply(x: Double, y: Double, z: Double) = doubleArrayOf(
        x * m[0] + y * m[3] + z * m[6] + m[9], x * m[1] + y * m[4] + z * m[7] + m[10], x * m[2] + y * m[5] + z * m[8] + m[11])
    fun then(next: Transform): Transform { // this, followed by next
        val a = m; val b = next.m
        fun r(i: Int, j: Int, t: DoubleArray) = t[i * 3 + j]
        val out = DoubleArray(12)
        for (i in 0..2) for (j in 0..2) out[i * 3 + j] = (0..2).sumOf { k -> r(i, k, a) * r(k, j, b) }
        for (j in 0..2) out[9 + j] = (0..2).sumOf { k -> a[9 + k] * r(k, j, b) } + b[9 + j]
        return Transform(out)
    }
    fun attr(): String = m.joinToString(" ") { if (it == it.toLong().toDouble()) it.toLong().toString() else "%.6f".format(java.util.Locale.US, it).trimEnd('0').trimEnd('.') }
    override fun equals(other: Any?) = other is Transform && m.contentEquals(other.m)
    override fun hashCode() = m.contentHashCode()
    companion object {
        fun translate(x: Double, y: Double, z: Double) = Transform(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, x, y, z))
        fun parse(s: String?): Transform = if (s.isNullOrBlank()) Transform() else Transform(s.trim().split(Regex("\\s+")).map { it.toDouble() }.toDoubleArray())
    }
}

/** One printable object: a name, a mesh already flattened from any components, and its placement on the plate. */
data class ModelObject(val id: Int, val name: String, val mesh: Mesh, val placement: Transform = Transform())

/**
 * A project as stored on disk: objects, the model-level 3MF metadata, the Nozzle manifest (null for a plain 3MF from
 * another app), and every archive entry Nozzle does not own, kept byte-for-byte so it can be written back.
 */
data class Project3mf(
    val objects: List<ModelObject>,
    val metadata: Map<String, String> = emptyMap(),
    val manifest: ProjectManifest? = null,
    val passthrough: Map<String, ByteArray> = emptyMap(),
    /** Why the manifest could not be used, when there was one. The project still opens from its geometry. */
    val manifestProblem: String? = null,
)

/** Resource limits for reading untrusted archives. */
data class ReadLimits(val maxEntries: Int = 10_000, val maxEntryBytes: Long = 512L * 1024 * 1024, val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
                      val maxCompressionRatio: Long = 200, val maxTriangles: Int = 20_000_000)

object ThreeMf {
    const val MODEL_PATH = "3D/3dmodel.model"
    private const val CORE_NS = "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
    private const val PROD_NS = "http://schemas.microsoft.com/3dmanufacturing/production/2015/06"
    /** Entries Nozzle regenerates on every save. Everything else is passthrough. */
    private val owned = setOf("[Content_Types].xml", "_rels/.rels", MODEL_PATH, ProjectManifest.ARCHIVE_PATH)

    fun validEntryName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 1024 && !name.startsWith("/") && !name.contains('\\') && !name.contains('\u0000') &&
            name.split('/').none { it == ".." || it == "." } && !Regex("^[A-Za-z]:").containsMatchIn(name)

    /** Reads every entry with traversal, count, size and zip-bomb checks. */
    fun readEntries(input: InputStream, limits: ReadLimits = ReadLimits(), compressedSize: Long? = null): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e: ZipEntry = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.removePrefix("/")
                if (!validEntryName(name)) throw ProjectFormatException("The project contains an unsafe file path and was not opened.")
                if (entries.size >= limits.maxEntries) throw ProjectFormatException("The project contains too many files.")
                if (entries.containsKey(name)) throw ProjectFormatException("The project contains a duplicate file ($name).")
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var n: Int
                var size = 0L
                while (zip.read(buf).also { n = it } > 0) {
                    size += n; total += n
                    if (size > limits.maxEntryBytes || total > limits.maxTotalBytes) throw ProjectFormatException("The project is larger than Nozzle It All can open.")
                    out.write(buf, 0, n)
                }
                entries[name] = out.toByteArray()
            }
        }
        if (entries.isEmpty()) throw ProjectFormatException("The file is not a 3MF project (it is empty or not a ZIP archive).")
        if (compressedSize != null && compressedSize > 0 && total / compressedSize > limits.maxCompressionRatio && total > 50L * 1024 * 1024)
            throw ProjectFormatException("The project expands to an implausible size and was not opened.")
        return entries
    }

    fun read(file: File, limits: ReadLimits = ReadLimits()): Project3mf = file.inputStream().buffered().use { read(it, limits, file.length()) }

    fun read(input: InputStream, limits: ReadLimits = ReadLimits(), compressedSize: Long? = null): Project3mf {
        val entries = try { readEntries(input, limits, compressedSize) }
            catch (e: java.util.zip.ZipException) { throw ProjectFormatException("The project file is damaged or incomplete (${e.message}).") }
            catch (e: java.io.EOFException) { throw ProjectFormatException("The project file is incomplete. It may have been only partly saved or copied.") }
        val root = modelPath(entries)
        val rootDoc = parseXml(entries[root] ?: throw ProjectFormatException("The 3MF has no model."))
        val metadata = LinkedHashMap<String, String>()
        children(rootDoc, "metadata").forEach { m -> m.getAttribute("name").takeIf { it.isNotBlank() }?.let { metadata[it] = m.textContent.trim() } }
        var triangles = 0
        val cache = HashMap<String, Map<Int, Element>>()
        fun objectsIn(path: String): Map<Int, Element> = cache.getOrPut(path) {
            val doc = if (path == root) rootDoc else parseXml(entries[path] ?: throw ProjectFormatException("The 3MF refers to a missing part ($path)."))
            resources(doc).associateBy { it.getAttribute("id").toIntOrNull() ?: throw ProjectFormatException("A 3MF object has no valid id.") }
        }
        fun flatten(path: String, id: Int, t: Transform, depth: Int, vs: MutableList<Float>, ts: MutableList<Int>) {
            if (depth > 16) throw ProjectFormatException("The 3MF nests components too deeply.")
            val obj = objectsIn(path)[id] ?: throw ProjectFormatException("The 3MF refers to a missing object ($id).")
            child(obj, "mesh")?.let { mesh ->
                val base = vs.size / 3
                child(mesh, "vertices")?.let { vv -> children(vv, "vertex").forEach { v ->
                    val p = t.apply(v.getAttribute("x").toDouble(), v.getAttribute("y").toDouble(), v.getAttribute("z").toDouble())
                    vs += p[0].toFloat(); vs += p[1].toFloat(); vs += p[2].toFloat() } }
                val count = vs.size / 3 - base
                child(mesh, "triangles")?.let { tt -> children(tt, "triangle").forEach { tri ->
                    val a = tri.getAttribute("v1").toInt(); val b = tri.getAttribute("v2").toInt(); val c = tri.getAttribute("v3").toInt()
                    if (a !in 0 until count || b !in 0 until count || c !in 0 until count) throw ProjectFormatException("A 3MF mesh refers to a vertex that doesn't exist.")
                    ts += base + a; ts += base + b; ts += base + c
                    if (++triangles > limits.maxTriangles) throw ProjectFormatException("The project has more triangles than Nozzle It All can open.")
                } }
            }
            child(obj, "components")?.let { comps -> children(comps, "component").forEach { c ->
                val subPath = c.getAttributeNS(PROD_NS, "path").ifBlank { c.getAttribute("p:path") }.removePrefix("/").ifBlank { path }
                if (!validEntryName(subPath)) throw ProjectFormatException("The project contains an unsafe part path.")
                flatten(subPath, c.getAttribute("objectid").toInt(), Transform.parse(c.getAttribute("transform")).then(t), depth + 1, vs, ts)
            } }
        }
        val build = child(rootDoc.documentElement, "build") ?: throw ProjectFormatException("The 3MF has no build plate contents.")
        val objects = children(build, "item").mapIndexed { index, item ->
            val id = item.getAttribute("objectid").toIntOrNull() ?: throw ProjectFormatException("A 3MF build item has no object.")
            val path = item.getAttributeNS(PROD_NS, "path").ifBlank { item.getAttribute("p:path") }.removePrefix("/").ifBlank { root }
            val vs = ArrayList<Float>(); val ts = ArrayList<Int>()
            flatten(path, id, Transform(), 0, vs, ts)
            val name = objectsIn(path)[id]?.getAttribute("name")?.ifBlank { null } ?: "Object ${index + 1}"
            ModelObject(id, name, Mesh(vs.toFloatArray(), ts.toIntArray()), Transform.parse(item.getAttribute("transform")))
        }
        var manifest: ProjectManifest? = null
        var problem: String? = null
        entries[ProjectManifest.ARCHIVE_PATH]?.let { bytes ->
            try {
                val m = ProjectManifest.parse(String(bytes, Charsets.UTF_8))
                // The model's own metadata names the project it belongs to. A manifest for a different project (for
                // example copied in from another file) must not be applied to these models.
                val owner = metadata[ProjectManifest.META_PROJECT_ID]
                if (owner != null && owner != m.projectId)
                    problem = "The project's Nozzle details belong to a different project. Materials and printer choices were not restored."
                else manifest = m
            } catch (e: IncompatibleProjectException) { throw e }
            catch (e: Exception) { problem = "The project's Nozzle details are damaged (${e.message}). The models opened; check materials and printer." }
        }
        // Geometry parts (3D/...) are flattened into the canonical model on save, so they are not carried forward.
        val passthrough = entries.filterKeys { it !in owned && it != root && !it.startsWith("3D/") }
        return Project3mf(objects, metadata, manifest, passthrough, problem)
    }

    /**
     * Writes a canonical 3MF: every object inline in 3D/3dmodel.model, one build item per object with its placement,
     * model metadata, the manifest in Auxiliaries/, and all passthrough entries unchanged.
     */
    fun write(project: Project3mf): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            val types = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="json" ContentType="application/json"/><Default Extension="config" ContentType="text/xml"/><Default Extension="gcode" ContentType="text/x.gcode"/></Types>"""
            put("[Content_Types].xml", types.toByteArray())
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/$MODEL_PATH" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>""".toByteArray())
            val meta = LinkedHashMap(project.metadata)
            project.manifest?.let { m ->
                meta[ProjectManifest.META_PROJECT_ID] = m.projectId
                meta[ProjectManifest.META_REVISION] = m.revision.toString()
                meta[ProjectManifest.META_MANIFEST_SHA256] = sha256(m.canonicalText().toByteArray())
            }
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"$CORE_NS\">\n")
            meta.forEach { (k, v) -> sb.append(" <metadata name=\"").append(xml(k)).append("\">").append(xml(v)).append("</metadata>\n") }
            sb.append(" <resources>\n")
            project.objects.forEach { o ->
                sb.append("  <object id=\"${o.id}\" name=\"${xml(o.name)}\" type=\"model\">\n   <mesh>\n    <vertices>\n")
                val v = o.mesh.vertices
                for (i in v.indices step 3) sb.append("     <vertex x=\"${num(v[i])}\" y=\"${num(v[i + 1])}\" z=\"${num(v[i + 2])}\"/>\n")
                sb.append("    </vertices>\n    <triangles>\n")
                val t = o.mesh.triangles
                for (i in t.indices step 3) sb.append("     <triangle v1=\"${t[i]}\" v2=\"${t[i + 1]}\" v3=\"${t[i + 2]}\"/>\n")
                sb.append("    </triangles>\n   </mesh>\n  </object>\n")
            }
            sb.append(" </resources>\n <build>\n")
            project.objects.forEach { o -> sb.append("  <item objectid=\"${o.id}\" transform=\"${o.placement.attr()}\" printable=\"1\"/>\n") }
            sb.append(" </build>\n</model>\n")
            put(MODEL_PATH, sb.toString().toByteArray())
            project.manifest?.let { put(ProjectManifest.ARCHIVE_PATH, it.toJson().toString(2).toByteArray()) }
            project.passthrough.forEach { (name, bytes) -> if (validEntryName(name) && name !in owned) put(name, bytes) }
        }
        return out.toByteArray()
    }

    /** Writes to a temporary file in the same folder and renames it over the target, so a crash never leaves half a project. */
    fun writeAtomically(project: Project3mf, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.part")
        try {
            tmp.outputStream().use { it.write(write(project)); it.flush(); it.fd.sync() }
            read(tmp) // never replace a good project with one we can't read back
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { tmp.delete() }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun modelPath(entries: Map<String, ByteArray>): String {
        entries["_rels/.rels"]?.let { rels ->
            val doc = parseXml(rels)
            children(doc, "Relationship").firstOrNull { it.getAttribute("Type").endsWith("/3dmodel") }?.getAttribute("Target")?.removePrefix("/")
                ?.takeIf { it in entries && validEntryName(it) }?.let { return it }
        }
        return MODEL_PATH.takeIf { it in entries } ?: throw ProjectFormatException("The 3MF has no model file.")
    }

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        // Untrusted input: no DTDs, no external entities, no XInclude.
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        f.setFeature("http://xml.org/sax/features/external-general-entities", false)
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        f.isXIncludeAware = false; f.isExpandEntityReferences = false
        return try { f.newDocumentBuilder().parse(ByteArrayInputStream(bytes)) } catch (e: Exception) { throw ProjectFormatException("Part of the 3MF is not valid XML.") }
    }
    private fun resources(doc: org.w3c.dom.Document): List<Element> = child(doc.documentElement, "resources")?.let { children(it, "object") } ?: emptyList()
    private fun children(doc: org.w3c.dom.Document, name: String) = children(doc.documentElement, name)
    private fun children(e: Element, name: String): List<Element> {
        val out = ArrayList<Element>(); var n = e.firstChild
        while (n != null) { if (n is Element && (n.localName ?: n.nodeName) == name) out += n; n = n.nextSibling }
        return out
    }
    private fun child(e: Element, name: String) = children(e, name).firstOrNull()
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun num(f: Float): String = if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()
}
