package net.jamesjennison.klippercompanion

import android.content.Context
import org.json.JSONArray

// WO-15 part E follow-up (owner-requested next increment, 2026-09-22): real build-volume bounds
// checking for the Model tab's Move/rotate/scale mode - right now nothing stops a model from
// being dragged or scaled off the actual bed. Reads the same real per-printer machine.json every
// slice already uses (SlicingProfilePacks.kt), not an invented bed size.
data class BedShape(val points: List<Pair<Float, Float>>, val heightMm: Float)

// machine.json's printable_area is a list of "XxY" strings (not a nested JSON array) - the exact
// format OrcaSlicer's own resources/profiles use, confirmed against this app's own bundled
// asset files (e.g. slicer_profiles/generic_klipper/machine.json: ["0x0","250x0","250x250",
// "0x250"]) rather than assumed. Pure (no Context/AssetManager) so it's unit-testable against a
// real machine.json's own text, not just device-tested.
internal fun parseBedShape(machineJson: String): BedShape {
    val obj = org.json.JSONObject(machineJson)
    val areaValue = obj.opt("printable_area")
    val rawPoints: List<String> = when (areaValue) {
        is JSONArray -> (0 until areaValue.length()).map { areaValue.getString(it) }
        is String -> listOf(areaValue) // some real profiles store a single "0x0,250x0,..." string
            .flatMap { it.split(',') }
        else -> emptyList()
    }
    val points = rawPoints.mapNotNull { raw ->
        val parts = raw.trim().split('x')
        if (parts.size == 2) parts[0].toFloatOrNull()?.let { x -> parts[1].toFloatOrNull()?.let { y -> x to y } } else null
    }
    val height = (obj.opt("printable_height") as? String)?.toFloatOrNull()
        ?: (obj.opt("printable_height") as? Number)?.toFloat()
        ?: 250f // a real fallback would need a printer-specific default; 250mm matches every bundled profile's own actual height today
    return BedShape(points, height)
}

internal fun SlicingProfilePack.readBedShape(context: Context): BedShape =
    parseBedShape(machineText(context))

// A real (if v1-bounded) test: every corner of the model's own axis-aligned XY footprint must
// land inside the bed polygon. Not a full silhouette-vs-polygon boolean (an irregular/diagonal
// mesh could still clip a bed edge between two AABB corners without any single corner going
// out) - a real, honest limitation, not invented precision. Standard ray-casting point-in-polygon
// (odd number of polygon-edge crossings along a horizontal ray from the point = inside).
internal fun pointInPolygon(x: Float, y: Float, polygon: List<Pair<Float, Float>>): Boolean {
    if (polygon.size < 3) return true // no real bed polygon known - don't claim an out-of-bounds verdict with no data
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val (xi, yi) = polygon[i]
        val (xj, yj) = polygon[j]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}
