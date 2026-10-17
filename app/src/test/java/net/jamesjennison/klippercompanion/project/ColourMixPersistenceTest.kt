package net.jamesjennison.klippercompanion.project

import com.nozzleitall.printer.ext.MixedFilamentFormat
import org.junit.Assert.*
import org.junit.Test

// The mixed-filament persistence and remap logic lives in ColourMixPersistence, a plain object with no Room/Compose
// dependency, precisely so it can be proven here rather than only inside ProjectEditorScreen's own composables (which
// nothing in this module's test setup can exercise directly).
class ColourMixPersistenceTest {
    private fun row(a: Int, b: Int, wa: Int = 1, wb: Int = 1) = MixedFilamentFormat.Row(listOf(a, b), listOf(wa, wb))

    @Test fun encodeIsNullForNoMixedFilaments() {
        assertNull(ColourMixPersistence.encode(emptyList()))
    }

    @Test fun decodeIsEmptyForNullBlankOrGarbage() {
        assertEquals(emptyList<MixedFilamentFormat.Row>(), ColourMixPersistence.decode(null))
        assertEquals(emptyList<MixedFilamentFormat.Row>(), ColourMixPersistence.decode("   "))
        assertEquals(emptyList<MixedFilamentFormat.Row>(), ColourMixPersistence.decode("not json"))
    }

    @Test fun rowsRoundTripThroughEncodeAndDecode() {
        val rows = listOf(row(1, 2, 7, 3), MixedFilamentFormat.Row(listOf(1, 3), listOf(1, 1), MixedFilamentFormat.Gradient(true, "0.9,0.1")))
        val decoded = ColourMixPersistence.decode(ColourMixPersistence.encode(rows))
        assertEquals(rows.map { it.components }, decoded.map { it.components })
        assertEquals(rows.map { it.weights }, decoded.map { it.weights })
        assertEquals(rows.map { it.gradient }, decoded.map { it.gradient })
    }

    @Test fun removingAMixSendsItsObjectsToTool1AndMovesLaterMixesDown() {
        assertEquals(1, ColourMixPersistence.applyRemoval(toolSlotIndex = 5, removedFilament = 5))
        assertEquals(5, ColourMixPersistence.applyRemoval(toolSlotIndex = 6, removedFilament = 5))
    }

    @Test fun removingAMixLeavesOtherObjectsAlone() {
        assertEquals(2, ColourMixPersistence.applyRemoval(toolSlotIndex = 2, removedFilament = 5))
        assertNull(ColourMixPersistence.applyRemoval(toolSlotIndex = null, removedFilament = 5))
    }
}
