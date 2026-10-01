package net.jamesjennison.klippercompanion

// Phase 9a (Consumer Slicer Plan §4/§16): the advanced slicing controls. Every entry maps to one real
// OrcaSlicer process-config key (verified against the bundled profile packs' process.json and exercised
// through the real engine by SettingsCatalogDeviceTest) - no raw key dumping: each is grouped, explained,
// range/enum-validated, and assigned a disclosure tier.
enum class SettingTier(val label: String) { BASIC("Basic"), ADVANCED("Advanced"), EXPERT("Expert") }

sealed class SettingType {
    data class IntRange(val min: Int, val max: Int, val unit: String = "") : SettingType()
    data class Decimal(val min: Double, val max: Double, val unit: String = "") : SettingType()
    data class Percent(val min: Int = 0, val max: Int = 100) : SettingType()
    object Toggle : SettingType()
    // value shown to the user -> exact OrcaSlicer config string
    data class Choice(val options: List<Pair<String, String>>) : SettingType()
}

data class SettingDef(
    val key: String, val label: String, val group: String, val tier: SettingTier, val type: SettingType, val help: String,
    // Set for controls that only do something on one kind of multi-tool machine (verified against the engine). FILAMENT_SWAP
    // controls (flushing) also show for MIXED machines, whose spools share a nozzle too.
    val onlyFor: MultiToolFamily? = null,
    // Set for controls that do something on any multi-tool machine and nothing on a single-material one (the prime tower:
    // spools sharing a nozzle flush into it, and toolchangers prime each tool on it after a change).
    val multiToolOnly: Boolean = false,
)

object SettingsCatalog {
    val all: List<SettingDef> = listOf(
        SettingDef("initial_layer_print_height", "First layer height", "Quality", SettingTier.ADVANCED, SettingType.Decimal(0.04, 0.4, "mm"), "Height of the first layer; cannot exceed the nozzle diameter (limit assumes 0.4 mm). Thicker first layers tolerate an uneven bed."),
        SettingDef("outer_wall_line_width", "Outer wall line width", "Quality", SettingTier.EXPERT, SettingType.Decimal(0.3, 1.2, "mm"), "Extrusion width of the visible outer walls (limits assume a 0.4 mm nozzle)."),
        SettingDef("seam_position", "Seam position", "Quality", SettingTier.ADVANCED, SettingType.Choice(listOf("Nearest" to "nearest", "Aligned" to "aligned", "Back" to "back", "Random" to "random")), "Where each layer starts and ends its outer wall."),
        SettingDef("ironing_type", "Ironing", "Quality", SettingTier.EXPERT, SettingType.Choice(listOf("Off" to "no ironing", "Top surfaces" to "top", "Topmost surface" to "topmost", "All solid" to "solid")), "Smooths flat top surfaces with a second pass."),
        SettingDef("detect_thin_wall", "Detect thin walls", "Quality", SettingTier.EXPERT, SettingType.Toggle, "Prints walls thinner than a nozzle line instead of skipping them."),
        SettingDef("enable_arc_fitting", "Arc fitting", "Quality", SettingTier.EXPERT, SettingType.Toggle, "Replaces many short segments with true arcs (smaller files; the printer must support G2/G3)."),

        SettingDef("wall_loops", "Wall count", "Strength", SettingTier.BASIC, SettingType.IntRange(1, 20), "Number of perimeter walls. More walls make stronger parts."),
        SettingDef("top_shell_layers", "Top layers", "Strength", SettingTier.ADVANCED, SettingType.IntRange(0, 50), "Solid layers on top surfaces."),
        SettingDef("bottom_shell_layers", "Bottom layers", "Strength", SettingTier.ADVANCED, SettingType.IntRange(0, 50), "Solid layers on the underside."),
        SettingDef("sparse_infill_pattern", "Infill pattern", "Strength", SettingTier.ADVANCED, SettingType.Choice(listOf("Grid" to "grid", "Gyroid" to "gyroid", "Cross hatch" to "crosshatch", "Honeycomb" to "honeycomb", "Lines" to "line", "Cubic" to "cubic", "Triangles" to "triangles", "Concentric" to "concentric", "Lightning" to "lightning")), "Internal fill geometry."),
        SettingDef("top_surface_pattern", "Top surface pattern", "Strength", SettingTier.EXPERT, SettingType.Choice(listOf("Monotonic" to "monotonic", "Monotonic lines" to "monotonicline", "Rectilinear" to "zig-zag", "Concentric" to "concentric")), "Line pattern on the top surface."),

        SettingDef("outer_wall_speed", "Outer wall speed", "Speed", SettingTier.ADVANCED, SettingType.IntRange(5, 1000, "mm/s"), "Print speed of the visible outer wall. Slower is smoother."),
        SettingDef("inner_wall_speed", "Inner wall speed", "Speed", SettingTier.ADVANCED, SettingType.IntRange(5, 1000, "mm/s"), "Print speed of inner walls."),
        SettingDef("sparse_infill_speed", "Infill speed", "Speed", SettingTier.ADVANCED, SettingType.IntRange(5, 1000, "mm/s"), "Print speed of internal infill."),
        SettingDef("initial_layer_speed", "First layer speed", "Speed", SettingTier.ADVANCED, SettingType.IntRange(5, 500, "mm/s"), "Slow first layers stick better."),
        SettingDef("travel_speed", "Travel speed", "Speed", SettingTier.EXPERT, SettingType.IntRange(20, 1500, "mm/s"), "Speed of non-printing moves."),
        SettingDef("default_acceleration", "Default acceleration", "Speed", SettingTier.EXPERT, SettingType.IntRange(100, 30000, "mm/s²"), "Print acceleration. Too high causes ringing on weak frames."),
        SettingDef("outer_wall_acceleration", "Outer wall acceleration", "Speed", SettingTier.EXPERT, SettingType.IntRange(100, 30000, "mm/s²"), "Acceleration for the visible outer wall."),

        SettingDef("support_type", "Support style", "Support", SettingTier.ADVANCED, SettingType.Choice(listOf("Normal (auto)" to "normal(auto)", "Tree (auto)" to "tree(auto)", "Normal (manual)" to "normal(manual)", "Tree (manual)" to "tree(manual)")), "Normal is a grid of pillars; tree branches around the model and uses less material."),
        SettingDef("support_threshold_angle", "Support overhang angle", "Support", SettingTier.ADVANCED, SettingType.IntRange(0, 90, "°"), "Overhangs steeper than this get support. Lower means more support."),
        SettingDef("support_on_build_plate_only", "Supports only from the bed", "Support", SettingTier.ADVANCED, SettingType.Toggle, "Never builds supports that rest on the model itself."),

        SettingDef("brim_width", "Brim width", "Adhesion", SettingTier.BASIC, SettingType.Decimal(0.0, 30.0, "mm"), "Flat ring around the first layer that keeps parts from lifting."),
        SettingDef("skirt_loops", "Skirt loops", "Adhesion", SettingTier.ADVANCED, SettingType.IntRange(0, 10), "Loops printed around (not touching) the part to prime the nozzle."),

        SettingDef("enable_prime_tower", "Prime tower", "Multi-material", SettingTier.ADVANCED, SettingType.Toggle, "Purges or primes the nozzle between tool or colour changes.", multiToolOnly = true),
        SettingDef("prime_tower_width", "Prime tower width", "Multi-material", SettingTier.EXPERT, SettingType.IntRange(10, 100, "mm"), "Width of the prime tower that purges or primes the nozzle between tool changes.", multiToolOnly = true),
        SettingDef("flush_multiplier", "Flush multiplier", "Multi-material", SettingTier.EXPERT, SettingType.Decimal(0.0, 3.0), "Scales the material flushed at every change: lower wastes less but risks colour bleed, higher is cleaner.", onlyFor = MultiToolFamily.FILAMENT_SWAP),
        SettingDef("flush_into_infill", "Flush into infill", "Multi-material", SettingTier.EXPERT, SettingType.Toggle, "Purges into the model's own infill instead of the tower, saving material.", onlyFor = MultiToolFamily.FILAMENT_SWAP),
        SettingDef("flush_into_objects", "Flush into objects", "Multi-material", SettingTier.EXPERT, SettingType.Toggle, "Purges into other objects on the plate.", onlyFor = MultiToolFamily.FILAMENT_SWAP),
        SettingDef("flush_into_support", "Flush into support", "Multi-material", SettingTier.EXPERT, SettingType.Toggle, "Purges into support material.", onlyFor = MultiToolFamily.FILAMENT_SWAP),
        SettingDef("reduce_crossing_wall", "Avoid crossing walls", "Travel", SettingTier.EXPERT, SettingType.Toggle, "Detours around walls to reduce stringing marks."),
        SettingDef("print_sequence", "Print sequence", "Travel", SettingTier.EXPERT, SettingType.Choice(listOf("Layer by layer" to "by layer", "Object by object" to "by object")), "Finish each object before starting the next (objects must be short enough to clear the gantry)."),
        SettingDef("spiral_mode", "Vase mode", "Special", SettingTier.EXPERT, SettingType.Toggle, "Prints one continuous spiralling outer wall with no top - for vases."),
    )

    private val byKey = all.associateBy { it.key }
    fun get(key: String): SettingDef? = byKey[key]
    val groups: List<String> = all.map { it.group }.distinct()

    /** Everything at or below [tier]'s disclosure level (Basic < Advanced < Expert), filtered by a free-text query. */
    fun visible(tier: SettingTier, query: String = "", family: MultiToolFamily? = null): List<SettingDef> {
        val q = query.trim().lowercase()
        return all.filter { it.tier.ordinal <= tier.ordinal && (it.onlyFor == null || it.onlyFor == family || (it.onlyFor == MultiToolFamily.FILAMENT_SWAP && family?.sharesNozzle == true)) && (!it.multiToolOnly || (family != null && family != MultiToolFamily.SINGLE)) && (q.isEmpty() || listOf(it.label, it.key, it.group, it.help).any { s -> s.lowercase().contains(q) }) }
    }

    /** The exact config string for a user-entered [raw] value, or null if it is invalid for this setting. */
    fun validate(def: SettingDef, raw: String): String? {
        val text = raw.trim()
        return when (val t = def.type) {
            is SettingType.IntRange -> text.toIntOrNull()?.takeIf { it in t.min..t.max }?.toString()
            is SettingType.Decimal -> text.toDoubleOrNull()?.takeIf { it.isFinite() && it in t.min..t.max }?.let { fmt(it) }
            is SettingType.Percent -> text.removeSuffix("%").trim().toIntOrNull()?.takeIf { it in t.min..t.max }?.let { "$it%" }
            SettingType.Toggle -> when (text.lowercase()) { "1", "true", "on" -> "1"; "0", "false", "off" -> "0"; else -> null }
            is SettingType.Choice -> t.options.firstOrNull { it.second == text || it.first.equals(text, true) }?.second
        }
    }

    /** Validated overrides only: unknown keys and invalid values are dropped, never passed to the engine. */
    fun sanitize(raw: Map<String, String>): Map<String, String> =
        raw.mapNotNull { (k, v) -> get(k)?.let { d -> validate(d, v)?.let { k to it } } }.toMap()

    private fun fmt(v: Double): String { val r = Math.round(v * 1000) / 1000.0; return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString() }
}

/** A named, saved set of overrides layered over a printer's bundled profile ("inherits" the base profile). */
// basePreset: the print profile (ProcessPreset.name) the overrides were saved on top of; null for one saved before print
// profiles existed, or on the pack's default.
data class CustomProfile(val name: String, val basePrinter: String, val overrides: Map<String, String>, val basePreset: String? = null) {
    fun encode(): String = org.json.JSONObject().put("name", name).put("base", basePrinter).put("overrides", org.json.JSONObject(overrides as Map<*, *>))
        .apply { basePreset?.let { put("base_preset", it) } }.toString()
    companion object {
        fun decode(json: String): CustomProfile? = runCatching {
            val o = org.json.JSONObject(json); val ov = o.getJSONObject("overrides")
            CustomProfile(o.getString("name"), o.getString("base"), ov.keys().asSequence().associateWith { ov.getString(it) }.let(SettingsCatalog::sanitize),
                o.optString("base_preset").ifBlank { null })
        }.getOrNull()
    }
}

data class SettingDifference(val key: String, val left: String?, val right: String?)

/** Compare two override sets: every key whose value differs (a missing side means "profile default"). */
fun compareOverrides(left: Map<String, String>, right: Map<String, String>): List<SettingDifference> =
    (left.keys + right.keys).sorted().mapNotNull { k -> if (left[k] != right[k]) SettingDifference(k, left[k], right[k]) else null }
