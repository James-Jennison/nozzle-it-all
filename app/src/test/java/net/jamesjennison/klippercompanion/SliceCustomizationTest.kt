package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class SliceCustomizationTest {
    @Test fun toOverridesProducesRealOrcaSlicerConfigKeys() {
        val overrides = SliceCustomization(0.2, 15, false).toOverrides()
        assertEquals("0.2", overrides["layer_height"])
        assertEquals("15%", overrides["sparse_infill_density"])
        assertEquals("0", overrides["enable_support"])
    }
    @Test fun supportsEnabledMapsToOneNotTrue() {
        assertEquals("1", SliceCustomization(0.2, 15, true).toOverrides()["enable_support"])
    }
    @Test fun wholeNumberLayerHeightHasNoTrailingZero() {
        assertEquals("1", SliceCustomization(1.0, 15, false).toOverrides()["layer_height"])
    }
    @Test fun fractionalLayerHeightRoundsToTwoDecimals() {
        assertEquals("0.28", SliceCustomization(0.28, 15, false).toOverrides()["layer_height"])
        assertEquals("0.12", SliceCustomization(0.123456, 15, false).toOverrides()["layer_height"])
    }
    @Test fun defaultMatchesEveryBundledPacksOwnDefault() {
        // Verified against every profile pack's process.json (PROVENANCE.md) - not a guess.
        assertEquals(SliceCustomization(0.2, 15, false), DEFAULT_SLICE_CUSTOMIZATION)
    }
    @Test fun layerHeightValidationRejectsOutOfRangeAndGarbage() {
        assertEquals(0.2, validateLayerHeight("0.2"))
        assertEquals(0.04, validateLayerHeight("0.04"))
        assertEquals(0.6, validateLayerHeight("0.6"))
        assertNull(validateLayerHeight("0.03"))
        assertNull(validateLayerHeight("0.61"))
        assertNull(validateLayerHeight("not a number"))
        assertNull(validateLayerHeight(""))
    }
    @Test fun infillValidationRejectsOutOfRangeAndGarbage() {
        assertEquals(0, validateInfillPercent("0"))
        assertEquals(100, validateInfillPercent("100"))
        assertNull(validateInfillPercent("-1"))
        assertNull(validateInfillPercent("101"))
        assertNull(validateInfillPercent("15%")) // the % suffix is added by toOverrides, not typed by the user
    }
}
