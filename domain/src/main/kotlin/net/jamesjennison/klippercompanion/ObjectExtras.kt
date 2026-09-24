package net.jamesjennison.klippercompanion

// Phase 9d: per-object paint strokes and modifier/blocker volumes. Both live in the object's own frame: MeshGeometry
// coordinates minus MeshGeometry.origin ([objectFrame]). That frame does not depend on the bed the model is centred
// on (the preview loads with a default bed, the real slice with the printer's own), and moving, rotating or scaling
// the object never invalidates it. Editing
// the mesh itself (mirror, lay flat, cut) does, so those edits clear them.
enum class PaintKind(val code: Int, val label: String) {
    SUPPORT_ENFORCER(0, "Add support"), SUPPORT_BLOCKER(1, "Block support"), SEAM_ENFORCER(2, "Seam here"), SEAM_BLOCKER(3, "Avoid seam"),
    // Multi-material: paints a region to print with another tool. Stored as 10 + the 1-based tool number.
    MATERIAL(10, "Material");
    companion object {
        const val MATERIAL_BASE = 10
        const val MAX_TOOL = 16
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: if (code in MATERIAL_BASE + 1..MATERIAL_BASE + MAX_TOOL) MATERIAL else null
    }
}

/** One brush dab: a ray into the mesh (origin/direction) and a radius in mesh units. */
data class PaintStroke(val kind: PaintKind, val origin: FloatArray, val dir: FloatArray, val radius: Float, val tool: Int = 0) {
    init { require(kind != PaintKind.MATERIAL || tool in 1..PaintKind.MAX_TOOL) { "A material stroke needs a tool from 1 to ${PaintKind.MAX_TOOL}." } }
    override fun equals(other: Any?) = other is PaintStroke && kind == other.kind && tool == other.tool && origin.contentEquals(other.origin) && dir.contentEquals(other.dir) && radius == other.radius
    override fun hashCode() = kind.hashCode() * 31 + origin.contentHashCode()
}

object PaintCodec {
    const val MAX_STROKES = 6000
    // "kind,ox,oy,oz,dx,dy,dz,r;kind,..." - the same text goes to the native engine.
    fun encode(strokes: List<PaintStroke>): String = strokes.joinToString(";") { s ->
        (listOf((if (s.kind == PaintKind.MATERIAL) PaintKind.MATERIAL_BASE + s.tool else s.kind.code).toString()) + s.origin.map { fmt(it) } + s.dir.map { fmt(it) } + fmt(s.radius)).joinToString(",")
    }
    fun decode(text: String?): List<PaintStroke> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(';').take(MAX_STROKES).mapNotNull { rec ->
            val p = rec.split(',')
            if (p.size != 8) return@mapNotNull null
            val code = p[0].toIntOrNull() ?: return@mapNotNull null
            val kind = PaintKind.fromCode(code) ?: return@mapNotNull null
            val f = p.drop(1).map { it.toFloatOrNull()?.takeIf { v -> v.isFinite() } ?: return@mapNotNull null }
            val radius = f[6]; if (radius <= 0f) return@mapNotNull null
            if (kind == PaintKind.MATERIAL && code == PaintKind.MATERIAL_BASE) return@mapNotNull null // bare 10 has no tool
            PaintStroke(kind, floatArrayOf(f[0], f[1], f[2]), floatArrayOf(f[3], f[4], f[5]), radius, if (kind == PaintKind.MATERIAL) code - PaintKind.MATERIAL_BASE else 0)
        }
    }
    private fun fmt(v: Float) = java.lang.Float.toString(v)
}

enum class VolumeKind(val code: String, val label: String) {
    MODIFIER("modifier", "Modifier"), SUPPORT_BLOCKER("blocker", "Support blocker"), SUPPORT_ENFORCER("enforcer", "Support enforcer");
    companion object { fun fromCode(code: String) = entries.firstOrNull { it.code == code } }
}
enum class VolumeShape(val code: String, val label: String) {
    BOX("box", "Box"), CYLINDER("cylinder", "Cylinder"), SPHERE("sphere", "Sphere");
    companion object { fun fromCode(code: String) = entries.firstOrNull { it.code == code } }
}

/** A region attached to an object: [center] in the mesh frame, [size] = full extents (x, y, z) in mm of the region. */
data class ShapeVolume(val kind: VolumeKind, val shape: VolumeShape, val center: FloatArray, val size: FloatArray, val overrides: Map<String, String> = emptyMap()) {
    override fun equals(other: Any?) = other is ShapeVolume && kind == other.kind && shape == other.shape && center.contentEquals(other.center) && size.contentEquals(other.size) && overrides == other.overrides
    override fun hashCode() = kind.hashCode() * 31 + center.contentHashCode()
}

object VolumeCodec {
    const val MAX_VOLUMES = 32
    private val KEY = Regex("[a-z_0-9]{1,64}")
    // "kind:shape:cx,cy,cz:sx,sy,sz:key=value|key=value" per volume, ";" between volumes. Modifier overrides are
    // restricted to the catalog's validated process keys, so nothing arbitrary reaches the engine.
    fun encode(volumes: List<ShapeVolume>): String = volumes.joinToString(";") { v ->
        listOf(v.kind.code, v.shape.code, v.center.joinToString(",") { it.toString() }, v.size.joinToString(",") { it.toString() },
            v.overrides.entries.joinToString("|") { "${it.key}=${it.value}" }).joinToString(":")
    }
    /** Catalog keys plus infill density (the most common modifier use), each validated. */
    fun sanitizeModifier(raw: Map<String, String>): Map<String, String> {
        val infill = raw["sparse_infill_density"]?.let { v -> v.trim().removeSuffix("%").toIntOrNull()?.takeIf { it in 0..100 } }?.let { "sparse_infill_density" to "$it%" }
        return SettingsCatalog.sanitize(raw) + listOfNotNull(infill)
    }
    fun decode(text: String?): List<ShapeVolume> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(';').take(MAX_VOLUMES).mapNotNull { rec ->
            val p = rec.split(':', limit = 5)
            if (p.size < 4) return@mapNotNull null
            val kind = VolumeKind.fromCode(p[0]) ?: return@mapNotNull null
            val shape = VolumeShape.fromCode(p[1]) ?: return@mapNotNull null
            val c = p[2].split(',').mapNotNull { it.toFloatOrNull()?.takeIf { v -> v.isFinite() } }
            val s = p[3].split(',').mapNotNull { it.toFloatOrNull()?.takeIf { v -> v.isFinite() && v > 0.1f && v < 10_000f } }
            if (c.size != 3 || s.size != 3) return@mapNotNull null
            val raw = if (p.size == 5 && p[4].isNotEmpty()) p[4].split('|').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 && KEY.matches(it[0]) }?.let { it[0] to it[1] } }.toMap() else emptyMap()
            ShapeVolume(kind, shape, c.toFloatArray(), s.toFloatArray(), if (kind == VolumeKind.MODIFIER) sanitizeModifier(raw) else emptyMap())
        }
    }
}

/** MeshGeometry (preview-frame) point -> the object frame strokes and volumes are stored in. */
fun MeshGeometry.objectFrame(p: FloatArray): FloatArray = floatArrayOf(p[0] - origin[0], p[1] - origin[1], p[2] - origin[2])
