package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class FirmwareIdentityTest {
    @Test fun classifiesRealCosmosAppString() {
        // Exact string confirmed live against a real Elegoo Centauri Carbon, 2026-09-22.
        assertEquals(CentauriCarbonFirmware.COSMOS, classifyCentauriCarbonFirmware(FirmwareIdentity("OpenCentauri Cosmos", "Release - 26.08.0")))
    }
    @Test fun doesNotClassifyAU1OrBlankApp() {
        assertNull(classifyCentauriCarbonFirmware(FirmwareIdentity("", "1.6.0.267_20260815150420")))
        assertNull(classifyCentauriCarbonFirmware(FirmwareIdentity("Klipper", "v0.12.0")))
    }
    @Test fun parsesRealCosmosVersionString() {
        assertEquals(Triple(26, 8, 0), parseCosmosVersion("Release - 26.08.0"))
        assertEquals(Triple(26, 7, 0), parseCosmosVersion("26.7.0"))
        assertNull(parseCosmosVersion("not a version"))
        assertNull(parseCosmosVersion(""))
    }
    @Test fun currentProfileFloorIsInclusiveAt26_07_0() {
        assertEquals(true, cosmosRequiresCurrentProfile("26.07.0"))
        assertEquals(true, cosmosRequiresCurrentProfile("Release - 26.08.0")) // the real device's exact string
        assertEquals(true, cosmosRequiresCurrentProfile("27.0.0"))
        assertEquals(false, cosmosRequiresCurrentProfile("26.06.9"))
        assertEquals(false, cosmosRequiresCurrentProfile("25.12.0"))
        assertNull(cosmosRequiresCurrentProfile("garbage"))
    }
    @Test fun nonCentauriCarbonProfileNeedsNoCheck() {
        assertEquals(FirmwareMatchResult.Match, checkCentauriCarbonFirmwareMatch(null, null))
        assertEquals(FirmwareMatchResult.Match, checkCentauriCarbonFirmwareMatch(FirmwareIdentity("Klipper", "v0.12.0"), null))
    }
    @Test fun unreadableFirmwareBlocksACentauriCarbonProfile() {
        val result = checkCentauriCarbonFirmwareMatch(null, CosmosProfileGeneration.CURRENT)
        assertTrue(result is FirmwareMatchResult.Unknown)
    }
    @Test fun nonCosmosFirmwareBlocksACentauriCarbonProfile() {
        val result = checkCentauriCarbonFirmwareMatch(FirmwareIdentity("", "1.6.0.267_20260815150420"), CosmosProfileGeneration.CURRENT)
        assertTrue(result is FirmwareMatchResult.Unknown)
    }
    @Test fun unparseableCosmosVersionBlocksRatherThanAssumesSafe() {
        val result = checkCentauriCarbonFirmwareMatch(FirmwareIdentity("OpenCentauri Cosmos", "garbage"), CosmosProfileGeneration.CURRENT)
        assertTrue(result is FirmwareMatchResult.Unknown)
    }
    @Test fun realDeviceStateMatchesCurrentProfileOnly() {
        // The exact live reading from 192.168.1.114, 2026-09-22: current profile required.
        val live = FirmwareIdentity("OpenCentauri Cosmos", "Release - 26.08.0")
        assertEquals(FirmwareMatchResult.Match, checkCentauriCarbonFirmwareMatch(live, CosmosProfileGeneration.CURRENT))
        assertTrue(checkCentauriCarbonFirmwareMatch(live, CosmosProfileGeneration.LEGACY) is FirmwareMatchResult.Mismatch)
    }
    @Test fun legacyCosmosMatchesLegacyProfileOnly() {
        val live = FirmwareIdentity("OpenCentauri Cosmos", "26.05.0")
        assertEquals(FirmwareMatchResult.Match, checkCentauriCarbonFirmwareMatch(live, CosmosProfileGeneration.LEGACY))
        assertTrue(checkCentauriCarbonFirmwareMatch(live, CosmosProfileGeneration.CURRENT) is FirmwareMatchResult.Mismatch)
    }
}
