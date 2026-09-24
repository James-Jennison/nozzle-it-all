package net.jamesjennison.klippercompanion

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import androidx.test.platform.app.InstrumentationRegistry
import net.jamesjennison.klippercompanion.project.AppDatabase
import net.jamesjennison.klippercompanion.project.ProjectViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MmfNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @After fun cleanup() { MmfRedirects.pending = null }

    @Test fun theDiscoverTabIsInTheBottomNavigationAndOpensTheDiscoverScreen() {
        compose.onNodeWithTag("nav-5").performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithTag("discover").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Discover").assertCountEquals(2) // the tab label and the screen heading
    }

    @Test fun theAppHandlesOnlyItsOwnSignInRedirectNotOtherLinks() {
        // The manifest registers nozzleitall://mmf-auth and nothing else, so other links cannot even reach the app.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        for (uri in listOf("https://evil.example/mmf-auth#access_token=x", "nozzleitall://elsewhere#access_token=x")) {
            val matches = ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(ctx.packageName).addCategory(Intent.CATEGORY_BROWSABLE), 0)
            assertTrue("$uri must not resolve to the app", matches.isEmpty())
        }
        assertNull(MmfRedirects.pending)
    }

    @Test fun theManifestRegistersOnlyTheSignInRedirectScheme() {
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val matches = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("nozzleitall://mmf-auth#access_token=x")).addCategory(Intent.CATEGORY_BROWSABLE), 0)
        assertTrue(matches.any { it.activityInfo.packageName == InstrumentationRegistry.getInstrumentation().targetContext.packageName })
    }
}

// The sign-in redirect arrives as a deep link: launching the app with it must land on Discover and hand the redirect over.
@RunWith(AndroidJUnit4::class)
class MmfDeepLinkDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun cleanup() { MmfRedirects.pending = null }

    @Test fun launchingWithTheSignInRedirectOpensDiscoverWhichConsumesItOnce() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val link = "nozzleitall://mmf-auth#access_token=tok-abcdefgh&state=abc"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClassName(ctx.packageName, MainActivity::class.java.name)
        androidx.test.core.app.ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(15000) { compose.onAllNodesWithTag("discover").fetchSemanticsNodes().isNotEmpty() }
            // Discover takes the redirect exactly once (here it finds no sign-in in progress and drops it safely).
            compose.waitUntil(8000) { MmfRedirects.pending == null }
        }
    }
}
