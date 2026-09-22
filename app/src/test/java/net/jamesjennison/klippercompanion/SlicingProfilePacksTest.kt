package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class SlicingProfilePacksTest {
    @Test fun everyNonCentauriModelHasExactlyOnePackRegardlessOfCosmosGeneration() {
        listOf(SlicingPrinterModel.SNAPMAKER_U1, SlicingPrinterModel.BAMBU_GENERIC, SlicingPrinterModel.PRUSA_GENERIC, SlicingPrinterModel.GENERIC_KLIPPER).forEach { model ->
            val withNull = slicingProfilePack(model, null)
            val withCurrent = slicingProfilePack(model, CosmosProfileGeneration.CURRENT)
            assertNotNull(model.name, withNull)
            assertEquals(model.name, withNull, withCurrent) // cosmosGeneration is irrelevant to non-Centauri-Carbon models
        }
    }
    @Test fun centauriCarbonOnlyHasAPackForTheCurrentCosmosGeneration() {
        assertNotNull(slicingProfilePack(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, CosmosProfileGeneration.CURRENT))
        assertNull("no legacy profile pack is bundled yet - must not silently fall back", slicingProfilePack(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, CosmosProfileGeneration.LEGACY))
        assertNull("an undeclared/unconfirmed firmware generation must not resolve to a pack", slicingProfilePack(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, null))
    }
    @Test fun everyPackPointsAtThreeDistinctAssetFiles() {
        val pack = slicingProfilePack(SlicingPrinterModel.SNAPMAKER_U1, null)!!
        val paths = setOf(pack.machinePath, pack.processPath, pack.filamentPath)
        assertEquals(3, paths.size)
        paths.forEach { assertTrue(it.startsWith("slicer_profiles/snapmaker_u1/")) }
    }
}
