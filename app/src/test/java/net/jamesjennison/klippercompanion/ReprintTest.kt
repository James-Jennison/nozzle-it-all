package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class ReprintTest {
    @Test fun anExactMatchWins() = assertEquals("a/b.gcode", Reprint.resolve("a/b.gcode", listOf("b.gcode", "a/b.gcode")))
    @Test fun aJobWithoutItsFolderMatchesTheOnlyFileOfThatName() = assertEquals("prints/cube.gcode", Reprint.resolve("cube.gcode", listOf("prints/cube.gcode", "other.gcode")))
    @Test fun anAmbiguousNameIsNotGuessed() = assertNull(Reprint.resolve("cube.gcode", listOf("a/cube.gcode", "b/cube.gcode")))
    @Test fun aDeletedFileHasNoReprint() = assertNull(Reprint.resolve("gone.gcode", listOf("x.gcode")))
    @Test fun aBlankNameHasNoReprint() = assertNull(Reprint.resolve("", listOf("x.gcode")))
}
