package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class PrusaMmuTest {
    @Test fun mmu3PrintersHaveFiveSlotsAndNothingElseDoes() {
        for (m in listOf(SlicingPrinterModel.PRUSA_CORE_ONE_MMU3, SlicingPrinterModel.PRUSA_MK4S_MMU3,
                SlicingPrinterModel.PRUSA_MK3_9_MMU3, SlicingPrinterModel.PRUSA_MK3_5_MMU3))
            assertEquals(m.name, 5, PrusaMmu.filamentSlots(m))
        for (m in listOf(SlicingPrinterModel.PRUSA_CORE_ONE, SlicingPrinterModel.PRUSA_MK4S, SlicingPrinterModel.PRUSA_XL_5T, SlicingPrinterModel.BAMBU_X1_CARBON))
            assertNull(m.name, PrusaMmu.filamentSlots(m))
        // Spools through one nozzle: a filament swap.
        assertEquals(MultiToolFamily.FILAMENT_SWAP, multiToolFamily(nozzleCount = 1, slotCount = PrusaMmu.MMU3_SLOTS))
    }
}
