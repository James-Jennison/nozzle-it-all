package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class ConfigFileTest {
    @Test fun splitsUserSectionFromSaveConfigMarker() {
        val raw = "[printer]\nkinematics: corexy\n\n#*# <---------------------- SAVE_CONFIG ---------------------->\n#*# DO NOT EDIT THIS BLOCK OR BELOW. The contents are auto-generated.\n#*#\n#*# [bed_mesh default]\n#*# version = 1\n"
        val result = ConfigFile.split("printer.cfg", raw)
        assertEquals("[printer]\nkinematics: corexy", result.userSection)
        assertTrue(result.autoSection.startsWith("#*# <----------------------"))
        assertTrue(result.hasAutoSection)
    }
    @Test fun noMarkerMeansNoAutoSection() {
        val result = ConfigFile.split("printer.cfg", "[printer]\nkinematics: corexy\n")
        assertEquals("[printer]\nkinematics: corexy\n", result.userSection)
        assertFalse(result.hasAutoSection)
        assertEquals("", result.autoSection)
    }
    @Test fun oversizedFileIsRejected() {
        val raw = "a".repeat(ConfigFile.MAX_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { ConfigFile.split("printer.cfg", raw) }
    }
}
