package net.jamesjennison.klippercompanion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import org.json.JSONObject

/** Opt-in GET-only observation of the owner's existing print. No print control methods exist here. */
class LivePreviewHardwareTest {
    @get:Rule val compose=createComposeRule()
    @Test fun followsExistingElegooPrintThroughActualPanel(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("approved_live_preview")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val proof=File(context.filesDir,"live-preview-hardware-proof.json")
        val report=JSONObject().put("status","STARTED");proof.writeText(report.toString())
        val connected=mutableStateOf(true)
        compose.setContent{CompanionTheme{LivePrintPreviewPanel(ScreenState(address="http://192.168.1.114/",connected=connected.value),{})}}
        fun status()=compose.onNodeWithTag("live-preview-status").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString{it.text}
        compose.waitUntil(180000){compose.onAllNodesWithTag("preview-live-marker").fetchSemanticsNodes().isNotEmpty()&&status().contains("reported byte")}
        val first=status();report.put("first",first);proof.writeText(report.toString())
        compose.onNodeWithTag("preview-canvas").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("preview-live-marker").performScrollTo().assertTextContains("Following buffered file progress",substring=true)
        compose.waitUntil(60000){status().contains("reported byte")&&status()!=first}
        report.put("second",status()).put("filename",compose.onNodeWithTag("live-preview-file").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString{it.text})
        compose.runOnIdle{connected.value=false}
        compose.waitUntil(10000){compose.onAllNodesWithTag("preview-live-marker").fetchSemanticsNodes().isEmpty()}
        assertTrue(context.cacheDir.listFiles()!!.none{it.name.startsWith("live-preview-")})
        report.put("disconnect_clears_marker",true).put("temporary_download_removed",true).put("status","PASS");proof.writeText(report.toString())
    }
}
