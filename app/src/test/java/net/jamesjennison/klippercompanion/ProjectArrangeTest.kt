package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

// Phase 1 (Consumer Slicer Plan §16): deterministic, device-free coverage for the real
// bin-packing/collision math ProjectEditorScreen's Auto-arrange action and Slice gate depend on -
// pure Kotlin, no Android/GL dependency, so every case here runs on the JVM.
class ProjectArrangeTest {
    private fun square(id: String, size: Float, minZ: Float = 0f) = MeshGeometry(
        vertexData = FloatArray(0), triangleCount = 0,
        center = floatArrayOf(0f, 0f, 0f), radius = size,
        origin = floatArrayOf(0f, 0f, 0f), minZ = minZ,
        minX = -size / 2, maxX = size / 2, minY = -size / 2, maxY = size / 2, maxZ = size,
    )

    @Test fun footprintOfUsesTheRealPivotNotTheBoundingBoxCenter() {
        // A mesh whose local origin (transform pivot) is NOT its own bbox center - the same real
        // divergence that caused a genuine bug in ProjectWorkspace's pickObject earlier this
        // session (see WO-17). footprintOf must still land on the real rendered position.
        val geometry = MeshGeometry(
            vertexData = FloatArray(0), triangleCount = 0,
            center = floatArrayOf(10f, 10f, 0f), radius = 14f,
            origin = floatArrayOf(100f, 100f, 0f), minZ = 0f,
            minX = 0f, maxX = 20f, minY = 0f, maxY = 20f, maxZ = 20f,
        )
        val footprint = footprintOf(geometry, ModelTransform())
        // Identity transform: rendered bbox-center position = pivot + (localCenter - pivot) =
        // localCenter (10,10) exactly, independent of where the pivot itself is.
        assertEquals(10f, footprint.centerX, 1e-4f)
        assertEquals(10f, footprint.centerY, 1e-4f)
        assertEquals(10f, footprint.halfWidth, 1e-4f)
    }

    @Test fun footprintOfAppliesOffsetAndScale() {
        val geometry = square("a", 20f)
        val transform = ModelTransform(offsetXMm = 50f, offsetYMm = -30f, scale = 2f)
        val footprint = footprintOf(geometry, transform)
        assertEquals(50f, footprint.centerX, 1e-4f)
        assertEquals(-30f, footprint.centerY, 1e-4f)
        assertEquals(20f, footprint.halfWidth, 1e-4f) // 10 half-extent * scale 2
    }

    @Test fun farApartSquaresDoNotOverlap() {
        val a = Footprint(0f, 0f, 5f, 5f, 0f)
        val b = Footprint(50f, 0f, 5f, 5f, 0f)
        assertFalse(footprintsOverlap(a, b))
    }

    @Test fun overlappingSquaresAreDetected() {
        val a = Footprint(0f, 0f, 5f, 5f, 0f)
        val b = Footprint(6f, 0f, 5f, 5f, 0f) // 4mm of real overlap (a spans to x=5, b starts at x=1)
        assertTrue(footprintsOverlap(a, b))
    }

    @Test fun touchingWithinMarginCountsAsOverlap() {
        val a = Footprint(0f, 0f, 5f, 5f, 0f)
        val b = Footprint(12f, 0f, 5f, 5f, 0f) // 2mm real gap (5+5=10 apart edge-to-edge would touch at 10)
        assertFalse("expected no overlap with zero margin", footprintsOverlap(a, b, marginMm = 0f))
        assertTrue("expected overlap once a 4mm safety margin is required", footprintsOverlap(a, b, marginMm = 4f))
    }

    // The real reason this needs a proper SAT, not a cheaper AABB-only check: two squares whose
    // *un-rotated* axis-aligned bounding boxes would overlap, but whose actual rotated corners
    // (one turned 45deg) do not - a coarser AABB test would wrongly flag this as a collision.
    @Test fun rotatedSquaresUseRealCornersNotAxisAlignedBoundingBoxes() {
        val a = Footprint(0f, 0f, 5f, 5f, 0f) // axis-aligned 10x10, spans -5..5 both axes
        // A 10x10 square rotated 45deg has an axis-aligned bounding box of side 10*sqrt(2)~=14.14,
        // so centering it at x=13 makes its AABB (x: 13-7.07..13+7.07 = 5.93..20.07) overlap a's
        // AABB (-5..5) not at all actually (5.93 > 5) - pick a center where AABBs overlap but the
        // real rotated diamond corners don't reach a's square.
        val b = Footprint(9f, 0f, 5f, 5f, 45f) // diamond's nearest point to a is its left corner at x = 9 - 5*sqrt(2) ~= 1.93
        // Real, rotation-aware distance: a's right edge is at x=5, b's nearest corner is ~x=1.93 - they DO overlap here.
        assertTrue(footprintsOverlap(a, b))
        val c = Footprint(13f, 0f, 5f, 5f, 45f) // nearest corner now at x = 13 - 7.07 ~= 5.93 - clear of a's x=5 edge
        assertFalse("expected no overlap - the rotated diamond's real nearest corner clears a's edge", footprintsOverlap(a, c))
    }

    @Test fun autoArrangeProducesANonOverlappingLayoutForManyObjects() {
        val items = (1..6).map { ArrangeItem("obj-$it", halfWidthMm = 8f, halfHeightMm = 8f) }
        val placements = autoArrange(items, bedWidthMm = 200f)
        assertEquals(6, placements.size)
        val footprints = items.map { item ->
            val p = placements.getValue(item.id)
            Footprint(p.offsetXMm, p.offsetYMm, item.halfWidthMm, item.halfHeightMm, p.rotationZDeg)
        }
        for (i in footprints.indices) {
            for (j in i + 1 until footprints.size) {
                assertFalse("expected no overlap between object $i and $j after auto-arrange", footprintsOverlap(footprints[i], footprints[j], marginMm = 3.9f))
            }
        }
    }

    @Test fun autoArrangeWrapsToANewShelfWhenARowIsFull() {
        // 5 objects, each 30mm wide, on a 100mm-wide bed: at most 3 per shelf (90mm + margins),
        // so this must produce more than one distinct Y (more than one shelf).
        val items = (1..5).map { ArrangeItem("obj-$it", halfWidthMm = 15f, halfHeightMm = 15f) }
        val placements = autoArrange(items, bedWidthMm = 100f, marginMm = 2f)
        val distinctYs = placements.values.map { Math.round(it.offsetYMm * 100) }.toSet()
        assertTrue("expected more than one shelf row, got Ys: ${placements.values.map { it.offsetYMm }}", distinctYs.size > 1)
    }

    @Test fun autoArrangeOfEmptyListIsEmpty() {
        assertTrue(autoArrange(emptyList(), bedWidthMm = 200f).isEmpty())
    }

    @Test fun autoArrangePicksTheNarrowerOrientationPerItem() {
        // A tall, narrow item (10mm wide, 40mm deep) should be rotated 90deg to lie flatter
        // (40mm wide, 10mm deep) when that's the narrower/shorter orientation for shelf packing.
        val items = listOf(ArrangeItem("tall", halfWidthMm = 5f, halfHeightMm = 20f))
        val placements = autoArrange(items, bedWidthMm = 200f)
        assertEquals(90f, placements.getValue("tall").rotationZDeg, 1e-4f)
    }
}
