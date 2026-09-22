package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test
import java.io.File

// WO-15 part E follow-up: real build-volume bounds checking. Proves the machine.json parsing
// against real bundled profile files (not a synthetic fixture) and the point-in-polygon test
// against known-inside/known-outside/edge cases.
class BedShapeTest {
    // Every bundled machine.json this app actually ships, parsed for real - if any of them
    // doesn't produce a real, usable bed polygon, bounds checking silently no-ops for that
    // printer (pointInPolygon's own "no real bed polygon known" escape hatch), which would be a
    // real, quiet regression worth catching here rather than only live on a printer.
    @Test fun everyBundledMachineJsonParsesToARealBedPolygon() {
        val root = File("src/main/assets/slicer_profiles")
        val machineFiles = root.listFiles()?.mapNotNull { dir -> File(dir, "machine.json").takeIf { it.exists() } }.orEmpty()
        assertTrue("expected to find bundled machine.json files to test against", machineFiles.isNotEmpty())
        for (file in machineFiles) {
            val bed = parseBedShape(file.readText())
            val name = file.parentFile?.name ?: file.path
            assertTrue("$name: expected a real bed polygon (>= 3 points)", bed.points.size >= 3)
            assertTrue("$name: expected a real, positive bed height", bed.heightMm > 0f)
        }
    }
    @Test fun realGenericKlipperMachineJsonParsesToItsOwnActualSquareBed() {
        val bed = parseBedShape(File("src/main/assets/slicer_profiles/generic_klipper/machine.json").readText())
        assertEquals(listOf(0f to 0f, 250f to 0f, 250f to 250f, 0f to 250f), bed.points)
        assertEquals(250f, bed.heightMm, 0.01f)
    }
    @Test fun pointInsideASquareBedIsInside() {
        val bed = listOf(0f to 0f, 250f to 0f, 250f to 250f, 0f to 250f)
        assertTrue(pointInPolygon(125f, 125f, bed))
    }
    @Test fun pointWellOutsideASquareBedIsOutside() {
        val bed = listOf(0f to 0f, 250f to 0f, 250f to 250f, 0f to 250f)
        assertFalse(pointInPolygon(300f, 125f, bed))
        assertFalse(pointInPolygon(-10f, 125f, bed))
        assertFalse(pointInPolygon(125f, 300f, bed))
    }
    @Test fun pointJustInsideEachEdgeIsInside() {
        val bed = listOf(0f to 0f, 250f to 0f, 250f to 250f, 0f to 250f)
        assertTrue(pointInPolygon(1f, 125f, bed))
        assertTrue(pointInPolygon(249f, 125f, bed))
        assertTrue(pointInPolygon(125f, 1f, bed))
        assertTrue(pointInPolygon(125f, 249f, bed))
    }
    @Test fun noRealPolygonDoesNotFalselyClaimOutOfBounds() {
        // pointInPolygon's own documented escape hatch - a printer whose bed data failed to
        // parse must not silently block every placement as "out of bounds".
        assertTrue(pointInPolygon(9999f, 9999f, emptyList()))
        assertTrue(pointInPolygon(9999f, 9999f, listOf(0f to 0f, 1f to 1f)))
    }
}
