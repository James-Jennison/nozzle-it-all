package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.PrepareAdvice
import org.junit.Assert.*
import org.junit.Test

class PrepareAdviceTest {
    private fun input(off: List<String> = emptyList(), printer: Boolean = true, open: Map<String, Int> = emptyMap(), n: Int = 1) = PrepareAdvice.Input(off, printer, open, n)

    @Test fun anEmptyPlateHasNothingToSay() = assertTrue(PrepareAdvice.compute(input(n = 0, off = listOf("x"), printer = false)).isEmpty())
    @Test fun aCleanPlateHasNothingToSay() = assertTrue(PrepareAdvice.compute(input()).isEmpty())

    @Test fun objectsOffThePlateOfferArrange() {
        val one = PrepareAdvice.compute(input(off = listOf("Possum"))).single()
        assertEquals("off-plate", one.id); assertEquals(PrepareAdvice.ARRANGE, one.action); assertTrue(one.text.startsWith("Possum hangs off the plate"))
        assertTrue(PrepareAdvice.compute(input(off = listOf("a", "b"))).single().text.startsWith("2 objects hang off"))
    }

    @Test fun anOpenMeshIsFlaggedWithTheWorstOne() {
        val a = PrepareAdvice.compute(input(open = mapOf("Loco" to 40, "Cube" to 0, "Rail" to 3))).single()
        assertEquals("open-edges", a.id); assertTrue(a.text.contains("Loco")); assertTrue(a.text.contains("40")); assertNull(a.action)
        assertTrue(PrepareAdvice.compute(input(open = mapOf("Loco" to 12))).single().text.startsWith("Loco isn't a closed shape (12 open edges)"))
        assertTrue(PrepareAdvice.compute(input(open = mapOf("Cube" to 0))).isEmpty())
    }

    @Test fun noPrinterIsNotedAndOrderIsProblemsFirst() {
        val all = PrepareAdvice.compute(input(off = listOf("x"), printer = false, open = mapOf("y" to 2)))
        assertEquals(listOf("off-plate", "open-edges", "no-printer"), all.map { it.id })
        assertEquals(PrepareAdvice.CHOOSE_PRINTER, all.last().action)
    }

    @Test fun aFirstRunWithNoPrinterSavedOffersToFindOne() {
        val a = PrepareAdvice.compute(PrepareAdvice.Input(emptyList(), false, emptyMap(), 1, anyPrinterSaved = false)).single()
        assertEquals(PrepareAdvice.FIND_PRINTER, a.action)
    }

    @Test fun withPrintersSavedItOnlyAsksToChooseOne() {
        val a = PrepareAdvice.compute(PrepareAdvice.Input(emptyList(), false, emptyMap(), 1, anyPrinterSaved = true)).single()
        assertEquals(PrepareAdvice.CHOOSE_PRINTER, a.action)
    }
}
