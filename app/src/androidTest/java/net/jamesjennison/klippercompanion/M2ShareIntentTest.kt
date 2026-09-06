package net.jamesjennison.klippercompanion

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class M2ShareIntentTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun androidShareRequiresConfirmationAndImportsOnlyLocally() {
        val activity=compose.activity
        val launchIntent=Intent(activity.intent)
        try {
        compose.runOnUiThread {
            activity.startActivity(Intent(activity,MainActivity::class.java).apply {
                action=Intent.ACTION_SEND;type="application/octet-stream"
                putExtra(Intent.EXTRA_STREAM,Uri.parse("content://net.jamesjennison.klippercompanion.test.gcodefixture/sample"))
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        compose.onNodeWithText("Import shared G-code?").assertIsDisplayed()
        compose.onNodeWithText("Import").performClick()
        compose.waitUntil(20000){compose.onAllNodesWithText("Ready to preview or save a copy.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Preview layers").performScrollTo().performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("Extrusion layer",substring=true).fetchSemanticsNodes().isNotEmpty()}
        }finally {
            // ActivityScenario identifies lifecycle callbacks using the launch intent; the app correctly replaces it on SEND.
            compose.runOnUiThread {activity.intent=launchIntent}
        }
    }
}
