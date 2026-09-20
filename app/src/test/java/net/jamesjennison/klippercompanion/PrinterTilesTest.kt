package net.jamesjennison.klippercompanion

import org.junit.Test
import org.junit.Assert.*

class PrinterTilesTest {
    private val first = "http://first.local/"
    private val second = "http://second.local/"
    @Test fun combinesConnectedPrintersWithoutBorrowingSelectedData() {
        val a=PrinterSnapshot(true,"printing",filename="a.gcode",progress=.25f)
        val b=PrinterSnapshot(true,"paused",filename="b.gcode",progress=.8f)
        val cam=Camera("Second camera","/snapshot","/stream","mjpegstreamer","camera-b")
        val state=ScreenState(address=first,connected=true,snapshot=a,savedPrinters=listOf(first,second,"http://offline.local/"),
            profiles=listOf(PrinterProfile(first,"First"),PrinterProfile(second,"Second",cameraId="camera-b")),
            printerConnections=mapOf(second to PrinterConnection(true,"paused",b,listOf(cam)),"http://offline.local/" to PrinterConnection()))
        val tiles=state.connectedPrinterTiles()
        assertEquals(listOf(first,second),tiles.map { it.address })
        assertEquals(listOf("a.gcode","b.gcode"),tiles.map {it.snapshot?.filename})
        assertNull(tiles[0].camera);assertEquals(cam,tiles[1].camera)
    }
    @Test fun disconnectedPrinterAndMissingSelectedCameraNeverUseStaleFallback() {
        val cam=Camera("Other","/snapshot",id="other")
        val state=ScreenState(address=first,connected=false,snapshot=PrinterSnapshot(true,"printing"),
            savedPrinters=listOf(first,second),profiles=listOf(PrinterProfile(second,cameraId="missing")),
            printerConnections=mapOf(second to PrinterConnection(true,"standby",cameras=listOf(cam))))
        val tiles=state.connectedPrinterTiles()
        assertEquals(listOf(second),tiles.map {it.address});assertNull(tiles.single().camera)
    }
    @Test fun tileCarriesPrinterKindForTheUnverifiedHardwareIndicator() {
        val state=ScreenState(address=first,connected=true,snapshot=PrinterSnapshot(true,"printing"),
            savedPrinters=listOf(first),profiles=listOf(PrinterProfile(first,kind=PrinterKind.BAMBU_LAB)))
        assertEquals(PrinterKind.BAMBU_LAB,state.connectedPrinterTiles().single().kind)
    }
    @Test fun onlyBambuAndPrusaAreFlaggedUnverifiedOnRealHardware() {
        assertTrue(PrinterKind.BAMBU_LAB.unverifiedOnRealHardware)
        assertTrue(PrinterKind.PRUSA_LINK.unverifiedOnRealHardware)
        assertFalse(PrinterKind.GENERIC_KLIPPER.unverifiedOnRealHardware)
        assertFalse(PrinterKind.SNAPMAKER_U1_PAXX.unverifiedOnRealHardware)
    }
}
