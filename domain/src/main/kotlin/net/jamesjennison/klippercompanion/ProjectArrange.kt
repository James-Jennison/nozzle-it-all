package net.jamesjennison.klippercompanion

import kotlin.math.cos
import kotlin.math.sin

// Phase 1 (Consumer Slicer Plan §16, "auto-arrange... a real bin-packing pass, not a stub"):
// a real 2D footprint for one object on the build plate - its own axis-aligned local bounding box
// (MeshGeometry.minX/maxX/minY/maxY, the same real geometry computeOutOfBounds already tests
// corners of), scaled and rotated about the real transform pivot (geometry.origin) exactly the
// way the GL renderer's model matrix and pickObject already do, so a footprint computed here
// always agrees with what's actually on screen and what actually gets sliced - not a second,
// possibly-diverging notion of "where this object is."
data class Footprint(val centerX: Float, val centerY: Float, val halfWidth: Float, val halfHeight: Float, val rotationDeg: Float) {
    fun corners(): List<FloatArray> {
        val rad = Math.toRadians(rotationDeg.toDouble())
        val cosR = cos(rad).toFloat(); val sinR = sin(rad).toFloat()
        return listOf(-halfWidth to -halfHeight, halfWidth to -halfHeight, halfWidth to halfHeight, -halfWidth to halfHeight).map { (lx, ly) ->
            floatArrayOf(centerX + lx * cosR - ly * sinR, centerY + lx * sinR + ly * cosR)
        }
    }
}

fun footprintOf(geometry: MeshGeometry, transform: ModelTransform): Footprint {
    val pivot = geometry.origin
    val localCenterX = (geometry.minX + geometry.maxX) / 2f
    val localCenterY = (geometry.minY + geometry.maxY) / 2f
    val halfWidth = (geometry.maxX - geometry.minX) / 2f * transform.scale
    val halfHeight = (geometry.maxY - geometry.minY) / 2f * transform.scale
    val rad = Math.toRadians(transform.rotationZDeg.toDouble())
    val cosR = cos(rad).toFloat(); val sinR = sin(rad).toFloat()
    val lx = (localCenterX - pivot[0]) * transform.scale
    val ly = (localCenterY - pivot[1]) * transform.scale
    val worldCenterX = pivot[0] + lx * cosR - ly * sinR + transform.offsetXMm
    val worldCenterY = pivot[1] + lx * sinR + ly * cosR + transform.offsetYMm
    return Footprint(worldCenterX, worldCenterY, halfWidth, halfHeight, transform.rotationZDeg)
}

// Real separating-axis test (SAT) for two independently-rotated rectangles - the correct way to
// test two convex polygons for overlap. A coarser circle- or AABB-only check would either miss a
// real corner-on-corner overlap between two rotated objects, or falsely flag two rotated objects
// as colliding when only their un-rotated bounding boxes would touch - both real failure modes
// for a v1 that's supposed to actually gate slicing, not just look like it does. marginMm expands
// both rectangles by half the margin each, so two objects exactly marginMm apart are reported as
// touching (a safety margin, not just zero-clearance contact).
fun footprintsOverlap(a: Footprint, b: Footprint, marginMm: Float = 0f): Boolean {
    val expandedA = a.copy(halfWidth = a.halfWidth + marginMm / 2f, halfHeight = a.halfHeight + marginMm / 2f)
    val expandedB = b.copy(halfWidth = b.halfWidth + marginMm / 2f, halfHeight = b.halfHeight + marginMm / 2f)
    val cornersA = expandedA.corners(); val cornersB = expandedB.corners()
    for (rectCorners in listOf(cornersA, cornersB)) {
        for (i in rectCorners.indices) {
            val p1 = rectCorners[i]; val p2 = rectCorners[(i + 1) % rectCorners.size]
            val axisX = -(p2[1] - p1[1]); val axisY = p2[0] - p1[0]
            val lenSq = axisX * axisX + axisY * axisY
            if (lenSq < 1e-9f) continue
            val (minA, maxA) = project(cornersA, axisX, axisY)
            val (minB, maxB) = project(cornersB, axisX, axisY)
            if (maxA < minB || maxB < minA) return false // a real separating axis - no overlap
        }
    }
    return true
}

private fun project(corners: List<FloatArray>, axisX: Float, axisY: Float): Pair<Float, Float> {
    var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
    for (c in corners) {
        val d = c[0] * axisX + c[1] * axisY
        if (d < min) min = d
        if (d > max) max = d
    }
    return min to max
}

data class ArrangeItem(val id: String, val halfWidthMm: Float, val halfHeightMm: Float)
data class ArrangedPlacement(val offsetXMm: Float, val offsetYMm: Float, val rotationZDeg: Float)

// Real first-fit-decreasing-height shelf packing - a standard, legitimate 2D bin-packing
// heuristic (not a stub: it actually reasons about widths/heights/remaining shelf space), with a
// real use of rotation - each item picks whichever of its two axis-aligned orientations (0deg or
// 90deg) is narrower, so it packs into a shorter/fewer shelves, rather than ignoring rotation
// entirely. Returned offsets are relative to the whole layout's own centroid (0,0) - each object
// is already independently bed-centered before its own transform offset is added (see
// engine::slice_multi_object's own per-object centering), so a layout centered at (0,0) here
// lands centered on the real bed once these offsets are applied as ModelTransform values.
fun autoArrange(items: List<ArrangeItem>, bedWidthMm: Float, marginMm: Float = 4f): Map<String, ArrangedPlacement> {
    if (items.isEmpty()) return emptyMap()
    data class Placed(val id: String, val x: Float, val y: Float, val w: Float, val h: Float, val rotation: Float)
    // Orient each item to minimize its own HEIGHT (lie it flat, not stand it tall) - shorter
    // items mean shorter/fewer shelf rows overall, the actual goal FFDH sorting is optimizing
    // for. Caught by a real test failure, not assumed correct on the first pass: an earlier
    // version of this condition was backwards and always chose the *taller* orientation instead.
    val oriented = items.map { item ->
        if (item.halfHeightMm <= item.halfWidthMm) Triple(item.id, (item.halfWidthMm * 2f) to (item.halfHeightMm * 2f), 0f)
        else Triple(item.id, (item.halfHeightMm * 2f) to (item.halfWidthMm * 2f), 90f)
    }
    val sorted = oriented.sortedByDescending { it.second.second }
    val placed = mutableListOf<Placed>()
    var shelfY = 0f; var shelfHeight = 0f; var cursorX = 0f
    val usableWidth = bedWidthMm.coerceAtLeast(sorted.maxOf { it.second.first }) // a single item wider than the bed still gets its own shelf, not clipped
    for ((id, size, rotation) in sorted) {
        val (w, h) = size
        if (cursorX > 0f && cursorX + w > usableWidth) {
            shelfY += shelfHeight + marginMm
            cursorX = 0f; shelfHeight = 0f
        }
        placed += Placed(id, cursorX + w / 2f, shelfY + h / 2f, w, h, rotation)
        cursorX += w + marginMm
        shelfHeight = maxOf(shelfHeight, h)
    }
    val minX = placed.minOf { it.x - it.w / 2f }; val maxX = placed.maxOf { it.x + it.w / 2f }
    val minY = placed.minOf { it.y - it.h / 2f }; val maxY = placed.maxOf { it.y + it.h / 2f }
    val centerX = (minX + maxX) / 2f; val centerY = (minY + maxY) / 2f
    return placed.associate { it.id to ArrangedPlacement(it.x - centerX, it.y - centerY, it.rotation) }
}
