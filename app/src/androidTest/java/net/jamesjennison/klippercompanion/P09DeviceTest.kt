package net.jamesjennison.klippercompanion
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
class P09DeviceTest {
 @get:Rule val compose=createComposeRule()
 private fun launch(){val p=GcodePreview.parse("G90\nM83\nG0 X0 Y0 Z0.2\nG1 X20 E1\nG0 Y20\nG1 X0 E1\nG0 Z0.4\nG1 Y0 E1\n".byteInputStream());compose.setContent{CompanionTheme{Column(Modifier.verticalScroll(rememberScrollState())){LayerPreview(p)}}}}
 @Test fun navigationZoomResetAndTravelToggle() {
  launch();compose.onNodeWithText("Previous layer").assertIsNotEnabled()
  compose.onNodeWithText("Next layer").performClick()
  compose.onNodeWithText("Extrusion layer 2 / 2",substring=true).assertExists()
  compose.onNodeWithText("Next layer").assertIsNotEnabled()
  compose.onNodeWithText("Previous layer").performClick()
  compose.onNodeWithText("Zoom in").performClick()
  compose.onNodeWithText("Zoom 1.5×",substring=true).assertExists()
  compose.onNodeWithTag("preview-travel").performClick().assertIsSelected()
  compose.onNodeWithTag("preview-canvas").assertContentDescriptionEquals("Layer 1: 2 displayed extrusion paths, 1 travel paths")
  compose.onNodeWithText("Fit model").performClick()
  compose.onNodeWithText("Zoom out").assertIsNotEnabled()
 }
 @Test fun selectedPathResetsWhenChangingLayerAndDragIsLocal() {
  launch();compose.onNodeWithTag("preview-position").performScrollTo().performTouchInput{swipeLeft()}
  compose.onNodeWithTag("preview-canvas").performScrollTo().performTouchInput{swipeRight()}
  compose.onNodeWithText("Next layer").performScrollTo().performClick()
  compose.onNodeWithText("Selected displayed path 1 / 1").assertExists()
  compose.onNodeWithTag("preview-position").assertDoesNotExist()
  compose.onNodeWithText("Approximate preview only",substring=true).assertExists()
 }
}
