package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ActiveToolDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun selectedToolIsExplicitOnDashboard() {
        val snapshot=PrinterSnapshot(true,"complete",nozzle=215.0,nozzleTarget=220.0,bed=35.0,bedTarget=0.0,activeExtruder="extruder1")
        compose.setContent {CompanionTheme {CompanionScreen(ScreenState(address="http://fixture.local/",connected=true,snapshot=snapshot),{},{},{},{_,_->error("No dispatch allowed")})}}
        compose.openFixtureDashboard()
        compose.onNodeWithTag("screen-list").performScrollToNode(hasText("NOZZLE · extruder1"))
        compose.onNodeWithText("NOZZLE · extruder1").assertIsDisplayed()
        compose.onNodeWithText("NOZZLE · extruder").assertDoesNotExist()
        val image=compose.onRoot().captureToImage().asAndroidBitmap()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.openFileOutput("active-tool-fixture.png",0).use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    // Run explicitly only during owner-approved read-only printer acceptance.
    @Test fun approvedPrinterReadOnlyActiveToolSnapshot() {
        val address=InstrumentationRegistry.getArguments().getString("approvedPrinterReadOnlyAddress")
        assumeTrue("Explicit owner-approved printer address required",!address.isNullOrBlank())
        val api=Moonraker(requireNotNull(address))
        try {
            val snapshot=api.snapshot()
            assertTrue(snapshot.ready)
            assertTrue(Regex("extruder[0-9]*").matches(snapshot.activeExtruder))
            assertNotNull(snapshot.nozzle);assertNotNull(snapshot.nozzleTarget)
            assertTrue(snapshot.nozzle!!.isFinite())
        } finally {api.close()}
    }
}
