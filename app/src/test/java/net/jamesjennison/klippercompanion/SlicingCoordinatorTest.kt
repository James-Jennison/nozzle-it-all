package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class SlicingCoordinatorTest {
    @Test fun acceptsRealModelExtensionsCaseInsensitively() {
        assertEquals("cube.stl", sliceableModelName("cube.stl"))
        assertEquals("cube.STL", sliceableModelName("cube.STL"))
        assertEquals("model.3mf", sliceableModelName("model.3mf"))
        assertEquals("model.obj", sliceableModelName("model.obj"))
    }
    @Test fun rejectsNonModelFilesAndUnsafeNames() {
        assertEquals("", sliceableModelName("print.gcode"))
        // Real regression this test caught before it shipped: a bare .3mf suffix check alone
        // would wrongly claim an already-sliced Bambu bundle as a raw model needing slicing,
        // hijacking it away from BambuPrintPanel's own existing, working flow.
        assertEquals("", sliceableModelName("archive.gcode.3mf"))
        assertEquals("", sliceableModelName("Archive.GCODE.3MF"))
        assertEquals("", sliceableModelName(null))
        assertEquals("", sliceableModelName(""))
        assertEquals("", sliceableModelName("../evil.stl"))
        assertEquals("", sliceableModelName("a".repeat(201) + ".stl"))
    }
}
