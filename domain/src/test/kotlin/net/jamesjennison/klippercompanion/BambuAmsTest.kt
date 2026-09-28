package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class BambuAmsTest {
    @Test fun singleExtruderBambuPrintersGetOneAmsOfFourSlots() {
        for (m in listOf(SlicingPrinterModel.BAMBU_X1_CARBON, SlicingPrinterModel.BAMBU_P1S, SlicingPrinterModel.BAMBU_GENERIC, SlicingPrinterModel.BAMBU_A1_MINI))
            assertEquals(m.name, 4, BambuAms.filamentSlots(m))
    }

    @Test fun twoExtruderBambuAndOtherPrintersKeepMachineJsonsCount() {
        for (m in listOf(SlicingPrinterModel.BAMBU_H2D, SlicingPrinterModel.BAMBU_X2D, SlicingPrinterModel.BAMBU_H2C,
                         SlicingPrinterModel.SNAPMAKER_U1, SlicingPrinterModel.PRUSA_XL_5T, SlicingPrinterModel.GENERIC_KLIPPER))
            assertNull(m.name, BambuAms.filamentSlots(m))
    }

    @Test fun anAmsPrinterIsAFilamentSwapMachine() {
        assertEquals(MultiToolFamily.FILAMENT_SWAP, multiToolFamily(SlicingPrinterModel.BAMBU_X1_CARBON, BambuAms.SLOTS_PER_UNIT))
    }
}
