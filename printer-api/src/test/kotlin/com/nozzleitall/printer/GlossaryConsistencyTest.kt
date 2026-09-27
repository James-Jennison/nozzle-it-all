package com.nozzleitall.printer

import org.junit.Assert.*
import org.junit.Test

/** Every shared concept the model exposes has one user-facing name in design/terminology/glossary.json. */
class GlossaryConsistencyTest {
    @Test fun everyStateRouteFirmwareAndActionHasATerm() {
        val ids = PrinterState.entries.map { it.glossaryId } + ConnectionRoute.entries.map { it.glossaryId } + FirmwareFamily.entries.map { it.glossaryId } +
            listOf(PrinterAction.StartJob("a"), PrinterAction.Pause, PrinterAction.Resume, PrinterAction.Cancel, PrinterAction.SetNozzleTemperature(0, 0),
                PrinterAction.SetBedTemperature(0), PrinterAction.HomeAll, PrinterAction.Jog('X', 1.0), PrinterAction.LoadMaterial(0), PrinterAction.UnloadMaterial(0),
                PrinterAction.SetMaterialInfo(0, Material()), PrinterAction.SelectToolhead(0)).map { it.glossaryId }
        val missing = ids.filter { it !in Glossary.terms }
        assertTrue("Missing glossary terms: $missing", missing.isEmpty())
    }

    @Test fun userFacingLabelsAvoidInternalWords() {
        val offenders = Glossary.terms.values.flatMap { t -> listOf(t.label, t.description, t.confirm) }
            .filter { text -> Glossary.internalWords.any { text.contains(it, ignoreCase = true) } }
        assertTrue("Internal words in UI copy: $offenders", offenders.isEmpty())
    }

    @Test fun cancelIsMarkedDestructiveAndStatesReadNaturally() {
        assertTrue(Glossary.term("action.cancel").destructive)
        assertEquals("Needs attention", PrinterState.ERROR.label)
        assertEquals("Private network", ConnectionRoute.PRIVATE_NETWORK.label)
    }
}
