package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LivePrinterReadOnlyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun connectAndReadActualPrinterWithoutSendingCommands() {
        val endpoint = InstrumentationRegistry.getArguments().getString("printerUrl")
        assumeTrue("Explicit owner-approved printerUrl is required", !endpoint.isNullOrBlank())
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithText("Moonraker or frontend address").performScrollTo().performTextReplacement(endpoint!!)
        compose.onNodeWithTag("connect-printer").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("CONNECTED").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("CONNECTED").assertExists()
        compose.onNodeWithTag("nav-1").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Run").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-2").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Start print").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Details")[0].performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Slicer estimate:",substring=true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("screen-list").performScrollToNode(hasTestTag("show-history"))
        compose.onNodeWithTag("show-history").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Records ",substring=true).fetchSemanticsNodes().isNotEmpty() }
        compose.openFixtureDashboard(endpoint!!)
        compose.onNodeWithTag("screen-list").performScrollToNode(hasText("Camera"))
        compose.waitUntil(30000) { compose.onAllNodesWithText("fps", substring=true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("fps", substring=true).assertExists()
        val latch = java.util.concurrent.CountDownLatch(1)
        var metrics = ""
        compose.runOnUiThread {
            fun find(view: android.view.View): android.webkit.WebView? {
                if(view is android.webkit.WebView) return view
                if(view is android.view.ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            val web = find(compose.activity.window.decorView) ?: error("No live player")
            web.evaluateJavascript("""JSON.stringify((()=>{const v=document.getElementById('video'),r=v.getBoundingClientRect(),c=document.createElement('canvas');c.width=16;c.height=16;const x=c.getContext('2d');x.drawImage(v,0,0,16,16);const d=x.getImageData(0,0,16,16).data;let sum=0;for(let i=0;i<d.length;i+=4)sum+=d[i]+d[i+1]+d[i+2];return {width:v.videoWidth,height:v.videoHeight,elementWidth:r.width,elementHeight:r.height,meanPixel:sum/768,frames:window.cameraStats.frames};})())""") { metrics=it;latch.countDown() }
        }
        org.junit.Assert.assertTrue(latch.await(5,java.util.concurrent.TimeUnit.SECONDS))
        val decoded=org.json.JSONObject(org.json.JSONTokener(metrics).nextValue() as String)
        org.junit.Assert.assertTrue("Video must occupy visible area", decoded.getDouble("elementHeight") > 50)
        org.junit.Assert.assertTrue("Video must occupy visible area", decoded.getDouble("elementWidth") > 50)
        val result=android.os.Bundle().apply { putString("stream", "CAMERA_METRICS " + metrics + "\n") }
        InstrumentationRegistry.getInstrumentation().sendStatus(0,result)
        org.junit.Assert.assertTrue("Decoded video required", decoded.getInt("width") > 0 && decoded.getInt("height") > 0)
        org.junit.Assert.assertTrue("Advancing decoded frames required", decoded.getInt("frames") > 0)
        compose.onNodeWithTag("expand-camera").performScrollTo().performClick()
        compose.onNodeWithTag("close-camera").assertIsDisplayed()
        compose.onNodeWithTag("nav-0").assertDoesNotExist()
        compose.waitUntil(30000) { compose.onAllNodesWithText("fps", substring=true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("close-camera").performClick()
        compose.onNodeWithTag("nav-0").assertIsDisplayed()
        compose.waitUntil(30000) { compose.onAllNodesWithText("fps", substring=true).fetchSemanticsNodes().isNotEmpty() }
        // No print controls or macro buttons are invoked by this test.
    }
}
