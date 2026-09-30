package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

// Phase 2 (Consumer Slicer Plan §10): the plan's own Tests requirement - "UI tests confirming a
// capability-gated control ... is hidden for printers that lack it." Drives real CompanionScreen
// composition (not a mock), the same pattern PrinterTilesDeviceTest already uses.
class PrinterCapabilitiesDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bambuPrinterHidesEveryKlipperOnlyControlAndShowsItsOwnLimitationsCard() {
        val address = "192.168.1.50"
        compose.setContent {
            CompanionTheme {
                CompanionScreen(
                    ScreenState(address = address, connected = true, snapshot = PrinterSnapshot(true, "standby"),
                        profiles = listOf(PrinterProfile(address, "Bambu", kind = PrinterKind.BAMBU_LAB, serial = "01S00A000000000"))),
                    {}, {}, {}, { _, _ -> },
                )
            }
        }
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithTag("open-heaters").assertDoesNotExist()
        compose.onNodeWithTag("open-mesh").assertDoesNotExist()
        compose.onNodeWithTag("open-bespok3d").assertDoesNotExist()
        compose.onNodeWithTag("open-ace").assertDoesNotExist()
        compose.onNodeWithTag("emergency-stop").assertDoesNotExist()
        compose.onNodeWithText("LAN mode limitations").assertExists()
    }

    @Test fun prusaPrinterHidesKlipperExtrasButKeepsPrintControlAndShowsItsOwnLimitationsCard() {
        val address = "http://prusa.local/"
        compose.setContent {
            CompanionTheme {
                CompanionScreen(
                    ScreenState(address = address, connected = true, snapshot = PrinterSnapshot(true, "printing", filename = "a.gcode", progress = 0.2f),
                        savedPrinters = listOf(address), profiles = listOf(PrinterProfile(address, "Prusa", kind = PrinterKind.PRUSA_LINK)),
                        printerConnections = mapOf(address to PrinterConnection(true, "printing", PrinterSnapshot(true, "printing", filename = "a.gcode", progress = 0.2f)))),
                    {}, {}, {}, { _, _ -> },
                )
            }
        }
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithTag("open-heaters").assertDoesNotExist()
        compose.onNodeWithTag("open-mesh").assertDoesNotExist()
        compose.onNodeWithText("PrusaLink API limitations").assertExists()
        // Pause/Resume/Cancel live on the Home tab's printer detail view (the "Print" hero
        // card), not the Control tab - open it the same way a real tap on the printer tile does.
        compose.onNodeWithTag("nav-0").performClick()
        compose.onNodeWithTag("printer-tile:$address").performClick()
        // PrusaLinkPrinterService's command() does support pause/resume/cancel, unlike Bambu -
        // this must still be offered, not swept into the same "hidden" bucket as Klipper extras.
        compose.onNodeWithText("Cancel print").assertExists()
    }

    @Test fun stockSnapmakerU1ShowsBespok3dButNotMultiAce() {
        val address = "http://u1.local/"
        compose.setContent {
            CompanionTheme {
                CompanionScreen(
                    ScreenState(address = address, connected = true, snapshot = PrinterSnapshot(true, "standby"),
                        profiles = listOf(PrinterProfile(address, "U1", kind = PrinterKind.SNAPMAKER_U1))),
                    {}, {}, {}, { _, _ -> },
                )
            }
        }
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithTag("open-bespok3d").assertExists()
        compose.onNodeWithTag("open-ace").assertDoesNotExist()
        compose.onNodeWithTag("open-heaters").assertExists()
    }

    @Test fun snapmakerU1PaxxShowsMultiAceButNotBespok3d() {
        val address = "http://u1.local/"
        compose.setContent {
            CompanionTheme {
                CompanionScreen(
                    ScreenState(address = address, connected = true, snapshot = PrinterSnapshot(true, "standby"),
                        profiles = listOf(PrinterProfile(address, "U1", kind = PrinterKind.SNAPMAKER_U1_PAXX))),
                    {}, {}, {}, { _, _ -> },
                )
            }
        }
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithTag("open-bespok3d").assertDoesNotExist()
        compose.onNodeWithTag("open-ace").assertExists()
        compose.onNodeWithTag("open-heaters").assertExists()
    }

    @Test fun genericKlipperShowsEveryControlAndNoLimitationsCard() {
        val address = "http://klipper.local/"
        compose.setContent {
            CompanionTheme {
                CompanionScreen(
                    ScreenState(address = address, connected = true, snapshot = PrinterSnapshot(true, "standby"),
                        profiles = listOf(PrinterProfile(address, "Klipper"))),
                    {}, {}, {}, { _, _ -> },
                )
            }
        }
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithTag("open-heaters").assertExists()
        compose.onNodeWithTag("open-mesh").assertExists()
        compose.onNodeWithTag("open-bespok3d").assertDoesNotExist()
        compose.onNodeWithText("LAN mode limitations").assertDoesNotExist()
        compose.onNodeWithText("PrusaLink API limitations").assertDoesNotExist()
    }
}
