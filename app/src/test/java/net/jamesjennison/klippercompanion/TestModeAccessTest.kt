package net.jamesjennison.klippercompanion

import net.jamesjennison.klippercompanion.testgrid.TestModeAccess
import org.junit.Assert.*
import org.junit.Test

class TestModeAccessTest {
    @Test fun sevenQuickTapsToggleSlowTapsDoNot() {
        var t = 0L
        val c = TestModeAccess.TapCounter(clock = { t })
        repeat(6) { assertFalse(c.tap()); t += 300 }
        assertTrue("the seventh quick tap toggles", c.tap())
        // Slow taps never add up.
        repeat(20) { t += 1_000; assertFalse(c.tap()) }
        // A pause longer than the window starts the count again.
        t += 10_000; repeat(3) { c.tap(); t += 100 }; t += 5_000
        repeat(6) { assertFalse(c.tap()); t += 100 }
        assertTrue(c.tap())
    }
}
