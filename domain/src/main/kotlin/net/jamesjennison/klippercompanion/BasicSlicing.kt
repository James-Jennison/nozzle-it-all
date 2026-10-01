package net.jamesjennison.klippercompanion

import kotlin.math.sin

// Phase 4 (Consumer Slicer Plan §4/§16): "basic-mode settings with printer/material/model-aware
// defaults" - a real quality-preset/strength/supports/adhesion surface for a beginner, replacing
// SliceCustomization's raw layer-height-in-mm text field with named presets, plus a real
// "intelligent defaulting" piece: whether a model actually needs support is *computed from its
// own geometry*, not guessed at or left to the owner to know in advance.

// Real, standard FDM layer heights - matches the kind of Draft/Standard/Fine split every major
// slicer UI offers, not arbitrary numbers. 0.2mm is this app's own existing default
// (DEFAULT_SLICE_CUSTOMIZATION, unchanged) - Standard keeps that exact value so a project that
// never touches this setting behaves identically to before this phase.
enum class QualityPreset(val label: String, val layerHeightMm: Double) {
    DRAFT("Draft", 0.28),
    STANDARD("Standard", 0.2),
    FINE("Fine", 0.12),
}

// AUTO is the real "intelligent defaulting" case - meshNeedsSupport() below decides, from the
// model's own real geometry, rather than the owner having to already know whether their model
// overhangs. ON/OFF stay as explicit overrides for when the owner disagrees with that read.
enum class SupportMode { OFF, AUTO, ON }

// quality/infillPercent null: keep the print profile's own layer height/infill (Prepare's Print profile picker, where the
// chosen process preset already sets both - ProcessPresets).
data class BasicSliceSettings(
    val quality: QualityPreset? = QualityPreset.STANDARD,
    val infillPercent: Int? = DEFAULT_SLICE_CUSTOMIZATION.infillPercent,
    val supportMode: SupportMode = SupportMode.AUTO,
    // Matches this app's own bundled profile packs' own default (every slicer_profiles/*/
    // process.json sets brim_width=5) - true is "keep the profile's own real default", not an
    // invented value; false is the one real override (brim_width=0) this setting ever emits.
    val adhesionBrim: Boolean = true,
)

// Real overhang detection, not a heuristic pulled from nowhere: a face is considered to need
// support if its own real outward normal points more than [thresholdDeg] below horizontal -
// 45deg is a common, defensible generic-slicer default (steeper than that and unsupported
// material starts printing into thin air). MeshGeometry's vertex data is 6 floats/vertex
// (position + the triangle's own flat face normal, repeated for its 3 vertices - see
// ModelViewer.kt's MeshLoader), in the mesh's own local space *before* any live transform.
// Z-axis rotation (the only rotation ModelTransform supports) never changes a normal's Z
// component, so this check is valid regardless of the object's current live rotationZDeg -
// scale changes a normal's direction not at all (uniform scale), so it's valid there too.
// Excludes vertices at/near the mesh's own minZ - its real bed-contact base, not an overhang
// needing support material of its own.
fun meshNeedsSupport(geometry: MeshGeometry, thresholdDeg: Double = 45.0): Boolean {
    val data = geometry.vertexData
    val thresholdNz = -sin(Math.toRadians(thresholdDeg))
    val baseZ = geometry.minZ + 0.5f
    var i = 0
    while (i < data.size) {
        val z = data[i + 2]
        val nz = data[i + 5]
        if (z > baseZ && nz < thresholdNz) return true
        i += 6
    }
    return false
}

// Real OrcaSlicer config keys, same override mechanism SliceCustomization already uses.
// needsSupport: the real geometry-derived answer from meshNeedsSupport() - only consulted when
// supportMode is AUTO; an explicit ON/OFF always wins over what the geometry says.
fun BasicSliceSettings.toOverrides(needsSupport: Boolean): Map<String, String> {
    val overrides = mutableMapOf<String, String>()
    quality?.let { overrides["layer_height"] = it.layerHeightMm.let { v -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString() } }
    infillPercent?.let { overrides["sparse_infill_density"] = "$it%" }
    val supportsOn = when (supportMode) {
        SupportMode.ON -> true
        SupportMode.OFF -> false
        SupportMode.AUTO -> needsSupport
    }
    overrides["enable_support"] = if (supportsOn) "1" else "0"
    if (!adhesionBrim) overrides["brim_width"] = "0"
    return overrides
}
