package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.PlateHandles
import org.junit.Assert.*
import org.junit.Test

class PlateHandlesTest {
    private val reach = floatArrayOf(-10f, 10f, -5f, 5f)

    @Test fun aPointOverTheFootprintHitsAndOneBesideItDoesNot() {
        assertTrue(PlateHandles.footprintHit(reach, 100f, 100f, 109f, 104f))
        assertFalse(PlateHandles.footprintHit(reach, 100f, 100f, 111f, 100f))
        assertFalse(PlateHandles.footprintHit(reach, 100f, 100f, 100f, 106f))
    }

    @Test fun draggingTheTurnHandleAQuarterTurnTurnsTheObjectNinetyDegrees() {
        assertEquals(90f, PlateHandles.rotationAfterDrag(0f, 50f, 50f, 80f, 50f, 50f, 80f), 0.01f)
        assertEquals(350f, PlateHandles.rotationAfterDrag(0f, 50f, 50f, 80f, 50f, 80f, 45f), 1f)
    }

    @Test fun turningSnapsWhenAskedAndWrapsAround() {
        assertEquals(45f, PlateHandles.rotationAfterDrag(0f, 0f, 0f, 10f, 0f, 10f, 9f, snap = 15f), 0.01f)
        assertEquals(9f, PlateHandles.rotationAfterDrag(350f, 0f, 0f, 10f, 0f, 10f, 3.5f), 0.01f)
    }

    @Test fun theCornerHandleScalesWithItsDistanceFromTheCentre() {
        assertEquals(2f, PlateHandles.scaleAfterDrag(1f, 0f, 0f, 10f, 10f, 20f, 20f), 0.001f)
        assertEquals(0.5f, PlateHandles.scaleAfterDrag(1f, 0f, 0f, 10f, 10f, 5f, 5f), 0.001f)
        assertEquals(0.05f, PlateHandles.scaleAfterDrag(1f, 0f, 0f, 10f, 10f, 0f, 0f), 0.001f)
    }

    @Test fun theHandlesSitOutsideTheFootprint() {
        assertTrue(PlateHandles.ringRadius(reach) > Math.hypot(10.0, 5.0))
        assertEquals(110f to 105f, PlateHandles.scaleKnob(reach, 100f, 100f))
        val (kx, ky) = PlateHandles.rotateKnob(reach, 100f, 100f, 90f)
        assertEquals(100f, kx, 0.01f); assertTrue(ky > 105f)
    }
}
