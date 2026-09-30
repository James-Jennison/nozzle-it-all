package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.FilamentSync
import com.nozzleitall.project.ProjectManifest.MaterialSlot
import com.nozzleitall.project.SourceFilament
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Snapmaker Orca's filament assignment (P-0006 in docs/upstream/PROVENANCE.md). */
class FilamentSyncTest {
    private val u1 = listOf(MaterialSlot(1, "PLA", colorHex = "#A78BFA"), MaterialSlot(2, "PLA", colorHex = "#F2754E"),
        MaterialSlot(3, "PLA", colorHex = "#EEF2F4"), MaterialSlot(4, "TPU", colorHex = "#1E2429"))

    @Test fun openingUsesFilamentNumbersNotColours() {
        // A PrusaSlicer file whose five filaments are all the same orange: each keeps its own number, wrapping round.
        assertEquals(listOf(1, 2, 3, 4, 1), FilamentSync.byNumber(5, u1))
    }

    @Test fun matchPrefersTheSameMaterialThenTheNearestColour() {
        val pangolin = listOf(SourceFilament(1, "#F7E6DE", "PLA", null), SourceFilament(2, "#9D432C", "PLA", null), SourceFilament(3, "#000000", "PLA", null))
        // Cream -> white, rust -> orange; black PLA has no black PLA loaded, so the nearest PLA (not the black TPU).
        val m = FilamentSync.byColour(3, pangolin, u1)
        assertEquals(3, m[0]); assertEquals(2, m[1])
        assertTrue("black PLA stays with a PLA slot, not the black TPU", m[2] in 1..3)
        // With no PLA-typed match it falls back to any loaded colour.
        assertEquals(listOf(4), FilamentSync.byColour(1, listOf(SourceFilament(1, "#101010", "PETG", null)), u1))
    }

    @Test fun ciede2000MatchesThePublishedReference() {
        // Sharma, Wu & Dalal (2005) test pair 1.
        assertEquals(2.0425f, FilamentSync.deltaE00(floatArrayOf(50f, 2.6772f, -79.7751f), floatArrayOf(50f, 0f, -82.7485f)), 1e-3f)
        assertEquals(0f, FilamentSync.deltaE00(FilamentSync.lab("#336699"), FilamentSync.lab("#336699")), 1e-4f)
    }
}
