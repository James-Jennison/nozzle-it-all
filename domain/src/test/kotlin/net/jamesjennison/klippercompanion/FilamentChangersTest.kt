package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class FilamentChangersTest {
    @Test fun changerPrintersHaveFourSlotsAndOthersNone() {
        assertEquals(FilamentChangers.Changer.CREALITY_CFS, FilamentChangers.changerFor(SlicingPrinterModel.CREALITY_K2_PLUS))
        assertEquals(FilamentChangers.Changer.ANYCUBIC_ACE, FilamentChangers.changerFor(SlicingPrinterModel.ANYCUBIC_KOBRA_S1))
        assertEquals(FilamentChangers.Changer.FLASHFORGE_IFS, FilamentChangers.changerFor(SlicingPrinterModel.FLASHFORGE_AD5X))
        for (m in listOf(SlicingPrinterModel.CREALITY_K1_CFS_C, SlicingPrinterModel.ANYCUBIC_KOBRA_3, SlicingPrinterModel.FLASHFORGE_AD5X))
            assertEquals(m.name, 4, FilamentChangers.filamentSlots(m))
        // A plain K1 (no CFS-C), the Qidi Box printers (not yet: their tower) and Sovol's "SV06 ACE" (a model name) get none.
        for (m in listOf(SlicingPrinterModel.CREALITY_K1, SlicingPrinterModel.QIDI_Q2, SlicingPrinterModel.QIDI_X_PLUS_4, SlicingPrinterModel.SOVOL_SV06_ACE))
            assertNull(m.name, FilamentChangers.filamentSlots(m))
    }
}
