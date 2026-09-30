package net.jamesjennison.klippercompanion.project

import com.nozzleitall.printer.ext.PrusaColorMixFormat
import org.junit.Assert.*
import org.junit.Test

// Colour mixing (0.2.0, requirement 5c): the remap/persistence logic lives in ColourMixPersistence, a plain
// object with no Room/Compose dependency, precisely so it can be proven here rather than only inside
// ProjectEditorScreen's own composables (which nothing in this module's test setup can exercise directly).
class ColourMixPersistenceTest {
    private fun virtual(id: Int) = PrusaColorMixFormat.Virtual(
        id = id, kind = "blend",
        components = listOf(PrusaColorMixFormat.Component(1, 0.5), PrusaColorMixFormat.Component(2, 0.5)),
    )

    @Test fun encodeColorMixIsNullForNoVirtualExtruders() {
        assertNull(ColourMixPersistence.encodeColorMix(emptyList()))
    }

    @Test fun decodeColorMixIsEmptyForNullOrBlank() {
        assertEquals(emptyList<PrusaColorMixFormat.Virtual>(), ColourMixPersistence.decodeColorMix(null))
        assertEquals(emptyList<PrusaColorMixFormat.Virtual>(), ColourMixPersistence.decodeColorMix(""))
        assertEquals(emptyList<PrusaColorMixFormat.Virtual>(), ColourMixPersistence.decodeColorMix("   "))
    }

    @Test fun decodeColorMixIsEmptyForGarbageJson() {
        assertEquals(emptyList<PrusaColorMixFormat.Virtual>(), ColourMixPersistence.decodeColorMix("not json"))
    }

    @Test fun colorMixRoundTripsThroughEncodeAndDecode() {
        val virtual = listOf(virtual(5), virtual(6))
        val encoded = ColourMixPersistence.encodeColorMix(virtual)
        assertNotNull(encoded)
        // The stored shape is PrusaColorMixFormat's own slice-request JSON - the same bytes a slice reads - not a
        // second, invented schema.
        assertEquals(PrusaColorMixFormat.sliceRequestJson(virtual), encoded)
        val decoded = ColourMixPersistence.decodeColorMix(encoded)
        assertEquals(virtual.map { it.id to it.summary }, decoded.map { it.id to it.summary })
    }

    @Test fun fullSpectrumRemapMovesAnObjectToItsNewSlot() {
        assertEquals(3, ColourMixPersistence.applyFullSpectrumRemap(toolSlotIndex = 2, remap = mapOf(2 to 3)))
    }

    @Test fun fullSpectrumRemapOfADeletedMixFallsBackToTool1() {
        assertEquals(1, ColourMixPersistence.applyFullSpectrumRemap(toolSlotIndex = 5, remap = mapOf(5 to 0)))
    }

    @Test fun fullSpectrumRemapLeavesAnUnaffectedSlotAlone() {
        assertEquals(4, ColourMixPersistence.applyFullSpectrumRemap(toolSlotIndex = 4, remap = mapOf(5 to 0)))
    }

    @Test fun fullSpectrumRemapLeavesANullSlotAlone() {
        assertNull(ColourMixPersistence.applyFullSpectrumRemap(toolSlotIndex = null, remap = mapOf(5 to 0)))
    }

    @Test fun colorMixRemovalResetsTheAffectedObjectToTool1() {
        assertEquals(1, ColourMixPersistence.applyColorMixRemoval(toolSlotIndex = 6, removedId = 6))
    }

    @Test fun colorMixRemovalLeavesOtherObjectsAlone() {
        assertEquals(2, ColourMixPersistence.applyColorMixRemoval(toolSlotIndex = 2, removedId = 6))
        assertNull(ColourMixPersistence.applyColorMixRemoval(toolSlotIndex = null, removedId = 6))
    }
}
