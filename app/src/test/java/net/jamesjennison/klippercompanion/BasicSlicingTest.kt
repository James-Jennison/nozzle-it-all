package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

// Phase 4 (Consumer Slicer Plan §4/§16): deterministic, device-free coverage for the real
// overhang-detection geometry math and the basic-tier override mapping. A real device test
// (BasicSlicingDeviceTest) separately confirms this actually changes the sliced G-code for a
// real overhanging mesh.
class BasicSlicingTest {
    private fun geometry(minZ: Float, vararg triangles: List<FloatArray>): MeshGeometry {
        val data = mutableListOf<Float>()
        for (tri in triangles) for (vertex in tri) data.addAll(vertex.toList())
        return MeshGeometry(
            vertexData = data.toFloatArray(), triangleCount = triangles.size,
            center = floatArrayOf(0f, 0f, 0f), radius = 10f, origin = floatArrayOf(0f, 0f, 0f), minZ = minZ,
            minX = -10f, maxX = 10f, minY = -10f, maxY = 10f, maxZ = 20f,
        )
    }
    // vertex = (x, y, z, nx, ny, nz)
    private fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) = floatArrayOf(x, y, z, nx, ny, nz)

    @Test fun flatTopFaceIsNotAnOverhang() {
        val upNormalTriangle = listOf(
            vertex(0f, 0f, 10f, 0f, 0f, 1f), vertex(1f, 0f, 10f, 0f, 0f, 1f), vertex(0f, 1f, 10f, 0f, 0f, 1f),
        )
        assertFalse(meshNeedsSupport(geometry(0f, upNormalTriangle)))
    }

    @Test fun verticalWallIsNotAnOverhang() {
        val verticalTriangle = listOf(
            vertex(0f, 0f, 10f, 1f, 0f, 0f), vertex(0f, 1f, 10f, 1f, 0f, 0f), vertex(0f, 0f, 15f, 1f, 0f, 0f),
        )
        assertFalse(meshNeedsSupport(geometry(0f, verticalTriangle)))
    }

    @Test fun aRealDownwardFacingOverhangIsDetected() {
        // A face well above the base, normal pointing mostly straight down - a real bridge/
        // ceiling underside, exactly the case support material exists for.
        val overhangTriangle = listOf(
            vertex(0f, 0f, 10f, 0f, 0f, -0.95f), vertex(1f, 0f, 10f, 0f, 0f, -0.95f), vertex(0f, 1f, 10f, 0f, 0f, -0.95f),
        )
        assertTrue(meshNeedsSupport(geometry(0f, overhangTriangle)))
    }

    // The mesh's own real bed-contact base (minZ) - a downward normal there is the object
    // resting on the print bed, not a real overhang needing its own support material.
    @Test fun theObjectsOwnBaseAtMinZIsExcludedNotFlaggedAsAnOverhang() {
        val baseTriangle = listOf(
            vertex(0f, 0f, 0f, 0f, 0f, -1f), vertex(1f, 0f, 0f, 0f, 0f, -1f), vertex(0f, 1f, 0f, 0f, 0f, -1f),
        )
        assertFalse(meshNeedsSupport(geometry(0f, baseTriangle)))
    }

    @Test fun aShallowOverhangUnderTheDefaultThresholdIsNotFlagged() {
        // ~30deg below horizontal (nz = -sin(30) = -0.5) - shallower than the real 45deg default
        // threshold, printable without support on most real printers.
        val shallowTriangle = listOf(
            vertex(0f, 0f, 10f, 0.866f, 0f, -0.5f), vertex(1f, 0f, 10f, 0.866f, 0f, -0.5f), vertex(0f, 1f, 10f, 0.866f, 0f, -0.5f),
        )
        assertFalse(meshNeedsSupport(geometry(0f, shallowTriangle)))
        // The same triangle IS flagged against a stricter (lower) threshold.
        assertTrue(meshNeedsSupport(geometry(0f, shallowTriangle), thresholdDeg = 20.0))
    }

    @Test fun toOverridesUsesTheRealQualityPresetLayerHeights() {
        assertEquals("0.28", BasicSliceSettings(quality = QualityPreset.DRAFT).toOverrides(false)["layer_height"])
        assertEquals("0.2", BasicSliceSettings(quality = QualityPreset.STANDARD).toOverrides(false)["layer_height"])
        assertEquals("0.12", BasicSliceSettings(quality = QualityPreset.FINE).toOverrides(false)["layer_height"])
    }

    @Test fun autoSupportModeDefersToTheRealGeometryAnswer() {
        val settings = BasicSliceSettings(supportMode = SupportMode.AUTO)
        assertEquals("1", settings.toOverrides(needsSupport = true)["enable_support"])
        assertEquals("0", settings.toOverrides(needsSupport = false)["enable_support"])
    }

    @Test fun explicitSupportModeAlwaysWinsOverTheGeometryAnswer() {
        assertEquals("0", BasicSliceSettings(supportMode = SupportMode.OFF).toOverrides(needsSupport = true)["enable_support"])
        assertEquals("1", BasicSliceSettings(supportMode = SupportMode.ON).toOverrides(needsSupport = false)["enable_support"])
    }

    @Test fun adhesionBrimOnlyOverridesWhenTurnedOff() {
        // On (the default, matching every bundled profile's own real brim_width=5) emits no
        // override at all - the profile's own curated default stays in effect, not a re-typed
        // copy of it that could drift.
        assertNull(BasicSliceSettings(adhesionBrim = true).toOverrides(false)["brim_width"])
        assertEquals("0", BasicSliceSettings(adhesionBrim = false).toOverrides(false)["brim_width"])
    }
}
