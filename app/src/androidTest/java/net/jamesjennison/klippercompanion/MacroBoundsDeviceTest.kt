package net.jamesjennison.klippercompanion

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Local form fixtures only: callbacks capture commands and never dispatch them. */
class MacroBoundsDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun exactUpperBoundRejectsRoundedOverflowThenAcceptsBoundary() {
        val prepared=mutableListOf<PrinterCommand>()
        compose.setContent {CompanionTheme {MacroForm("TEST",MacroOptions(parameters="TEMP=0,300,200"),{}, {prepared.add(it)})}}
        compose.onNodeWithText("TEMP").performTextReplacement("300.00000000000000000000000001")
        compose.onNodeWithText("Review command").performClick()
        compose.onNodeWithText("TEMP: use 0 to 300.").assertIsDisplayed()
        assertTrue(prepared.isEmpty())
        val file=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"macro-bounds-fixture.png")
        file.outputStream().use {compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithText("TEMP").performTextReplacement("300")
        compose.onNodeWithText("Review command").performClick()
        assertEquals("TEST TEMP=300",prepared.single().arguments["script"])
    }
    @Test fun smallScientificDefaultDisplaysPlainAndPreparesExactly() {
        val prepared=mutableListOf<PrinterCommand>()
        compose.setContent {CompanionTheme {MacroForm("TEST",MacroOptions(parameters="A=0,1e2,1e-7"),{}, {prepared.add(it)})}}
        compose.onNode(hasSetTextAction()).assertTextContains("0.0000001")
        compose.onNodeWithText("0 to 100").assertIsDisplayed()
        compose.onNodeWithText("Review command").performClick()
        assertEquals("TEST A=0.0000001",prepared.single().arguments["script"])
    }
}
