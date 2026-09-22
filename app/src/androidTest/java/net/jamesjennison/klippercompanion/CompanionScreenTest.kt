package net.jamesjennison.klippercompanion

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CompanionScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun preferencePairPersistsTogetherAndMalformedTypesDoNotCrash() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("profile-test-${java.util.UUID.randomUUID()}", 0)
        try {
            PrinterPreferences.save(prefs, "http://first.local/", listOf("http://first.local/", "http://second.local/"))
            assertEquals("http://first.local/", PrinterPreferences.address(prefs))
            assertEquals(2, PrinterPreferences.printers(prefs).size)
            PrinterPreferences.save(prefs, "", listOf("http://second.local/"))
            assertEquals("", PrinterPreferences.address(prefs))
            assertEquals(listOf("http://second.local/"), PrinterPreferences.printers(prefs))
            prefs.edit().putInt("address", 1).putInt("savedPrinters", 2).commit()
            assertEquals("", PrinterPreferences.address(prefs))
            assertEquals(emptyList<String>(), PrinterPreferences.printers(prefs))
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun pauseRequiresExplicitConfirmationAndGoBackDoesNotDispatch() {
        var sent = 0
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address="http://fixture.local/", connected=true, snapshot=PrinterSnapshot(true,"printing")), {}, {}, {}, { _,_->sent++ }) } }
        compose.openFixtureDashboard()
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        assertEquals(0,sent)
        compose.onNodeWithText("Go back").performClick()
        assertEquals(0,sent)
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        compose.onNodeWithText("Confirm").performClick()
        assertEquals(1,sent)
    }
    @Test fun noSavedPrintersOpensTheAddPrinterWizardAutomatically() {
        // A fresh install (or every printer forgotten) used to land on a bare dashboard - "No
        // printers connected" plus a button that only jumped to the Settings tab, where "Add
        // printer" still had to be found. Confirmed live on the Razr after a real data wipe
        // during this session's own testing: the wizard's own "Step 1 of 4" content now appears
        // with no tap at all.
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(profiles = emptyList()), {}, {}, {}, { _,_-> }) } }
        compose.onNodeWithTag("wizard-address").assertExists()
    }
    @Test fun offlineDisablesPrintControls() {
        val address = "http://fixture.local/"
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address=address, connected=false, savedPrinters=listOf(address), snapshot=PrinterSnapshot(true,"printing")), {}, {}, {}, { _,_->error("Must not dispatch") }) } }
        compose.openFixtureDashboard(connected=false)
        compose.onNodeWithText("Pause").performScrollTo().assertIsNotEnabled()
    }
    @Test fun savedProfileConnectAndForgetUseTheirExactAddresses() {
        val first = "http://first.local/"; val second = "http://second.local/"
        var connected = ""; var forgotten = ""
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address=first, savedPrinters=listOf(first,second)), { connected = it }, {}, {}, { _,_->error("Must not dispatch") }, { forgotten = it }) } }
        compose.onNodeWithTag("nav-4").performClick()
        compose.onNodeWithTag("saved-forget:$first").performScrollTo().performClick()
        assertEquals(first, forgotten)
        compose.onNodeWithTag("saved-connect:$second").performScrollTo().performClick()
        assertEquals(second, connected)
    }
    @Test fun compactLargeTextKeepsConfirmationAndNavigationReachable() {
        var sent = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                CompanionTheme { Box(Modifier.width(320.dp)) {
                    CompanionScreen(ScreenState(address="http://fixture.local/", connected=true,
                        snapshot=PrinterSnapshot(true,"printing", filename="A very long model filename with multiple words.gcode", progress=.47f)), {}, {}, {}, { _,_->sent++ })
                } }
            }
        }
        compose.openFixtureDashboard()
        compose.onNodeWithText("Pause").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Go back").assertIsDisplayed().performClick()
        assertEquals(0, sent)
        // The old inline "connect-printer" field this used to check is gone (AddPrinterWizard
        // replaced it entirely) - "Add printer" is its direct successor and always present on
        // the Settings tab, so it still proves Settings-tab content stays reachable at this
        // compact width and text scale.
        compose.onNodeWithTag("nav-4").assertIsDisplayed().performClick()
        compose.onNodeWithTag("screen-list").performScrollToNode(hasTestTag("open-add-printer-wizard"))
        compose.onNodeWithTag("open-add-printer-wizard").assertIsDisplayed()
    }

    @Test fun rejectedConfirmationFeedbackStaysVisibleWhileConnected() {
        val message = "Printer state changed. Refresh before sending a command."
        compose.setContent { CompanionTheme {
            var state by remember { mutableStateOf(ScreenState(address="http://fixture.local/", connected=true, snapshot=PrinterSnapshot(true,"printing"))) }
            CompanionScreen(state, {}, {}, {}, { _,_-> state = state.copy(commandNotice=message) })
        } }
        compose.openFixtureDashboard()
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    @Test fun profileEditorAndCameraPickerDispatchExactSelections() {
        val address="http://fixture.local/";var edited="";var camera=""
        val profile=PrinterProfile(address,"Workshop")
        val cameras=listOf(Camera("Front","/snapshot1",id="front"),Camera("Side","/snapshot2",id="side"))
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address=address,connected=true,savedPrinters=listOf(address),profiles=listOf(profile),catalog=Catalog(emptyList(),emptyList(),cameras,emptyList())),{},{},{},{_,_->},updateProfile={old,_,name,_,_,_,_->assertEquals(address,old);edited=name;null},selectCamera={camera=it},tileCamera={}) } }
        compose.openFixtureDashboard()
        compose.onNodeWithTag("camera:side").performScrollTo().performClick();assertEquals("side",camera)
        compose.onNodeWithTag("nav-4").performClick()
        compose.onNodeWithTag("edit-profile:$address").performScrollTo().performClick()
        compose.onNodeWithText("Printer name").performTextReplacement("Garage")
        compose.onNodeWithText("Save").performClick();assertEquals("Garage",edited)
    }
    @Test fun profileEditorSendsApiKeyToSave() {
        val address="http://fixture.local/";var savedKey=""
        val profile=PrinterProfile(address,"Workshop",apiKey="old-key")
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address=address,connected=true,savedPrinters=listOf(address),profiles=listOf(profile)),{},{},{},{_,_->},updateProfile={_,_,_,key,_,_,_->savedKey=key;null},tileCamera={}) } }
        compose.openFixtureDashboard()
        compose.onNodeWithTag("nav-4").performClick()
        compose.onNodeWithTag("edit-profile:$address").performScrollTo().performClick()
        compose.onNodeWithText("API key (optional)").performTextReplacement("new-key")
        compose.onNodeWithText("Save").performClick();assertEquals("new-key",savedKey)
    }
    @Test fun backgroundAlertsToggleDispatchesRequestedState() {
        var requested: Boolean? = null
        // A truly-empty ScreenState() (no address, no profiles) now auto-opens AddPrinterWizard
        // (see MainActivity's autoOpenedWizard effect) - not what this test is exercising, so it
        // sets a fixture address like every other test here to keep that from firing. This test
        // only needs the Settings tab, not a connected printer's own dashboard, so it skips
        // openFixtureDashboard entirely.
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address="http://fixture.local/"),{},{},{},{_,_->},
            backgroundAlertsEnabled=false, setBackgroundAlertsEnabled={requested=it},tileCamera={}) } }
        compose.onNodeWithTag("nav-4").performClick()
        compose.onNodeWithTag("background-alerts-toggle").performScrollTo().performClick()
        assertEquals(true, requested)
    }
    @Test fun structuredProfilesSurvivePreferenceReload() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("m1-test-${java.util.UUID.randomUUID()}",0)
        val secrets=context.getSharedPreferences("m1-secrets-test-${java.util.UUID.randomUUID()}",0)
        try {
            val profiles=listOf(PrinterProfile("http://b.local/","B",true,"side","key-b"),PrinterProfile("http://a.local/","A"))
            PrinterPreferences.saveProfiles(prefs,secrets,profiles.first().address,profiles)
            assertEquals(profiles,PrinterPreferences.profiles(prefs,secrets))
            assertEquals(profiles.first().address,PrinterPreferences.address(prefs))
            assertEquals(profiles.map { it.address }.sorted(),PrinterPreferences.printers(prefs))
        } finally {prefs.edit().clear().commit();secrets.edit().clear().commit()}
    }

    @Test fun remoteAccessHelpOpensAndClosesWithoutDiscardingEdits() {
        val model=PrinterModel("http://fixture.local/",initialProfiles=listOf(PrinterProfile("http://fixture.local/")))
        compose.setContent { CompanionTheme {ProfileEditor(PrinterProfile("http://fixture.local/"),{},model::updateProfile)} }
        compose.onNodeWithText("Printer name").performTextReplacement("Garage")
        compose.onNodeWithTag("open-remote-access-help").performClick()
        compose.onNodeWithText("Connecting away from home").assertIsDisplayed()
        compose.onNodeWithText("Tailscale (recommended)").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Printer name").assertTextContains("Garage")
    }
    @Test fun invalidProfileStaysOpenWithInlineError() {
        val model=PrinterModel("http://fixture.local/",initialProfiles=listOf(PrinterProfile("http://fixture.local/")))
        var closed=false
        compose.setContent { CompanionTheme {ProfileEditor(PrinterProfile("http://fixture.local/"),{closed=true},model::updateProfile)} }
        compose.onNodeWithText("Printer address").performTextReplacement("not a url")
        compose.onNodeWithText("Save").performClick()
        // ProfileEditor's content Column gained a scroll modifier for the slicing-profile/
        // firmware section (WO-13) - the inline error can land outside the current scroll
        // position now that the form is taller, same reason every other post-interaction
        // assertion in this file already scrolls first.
        compose.onNodeWithText("Enter a valid local printer address.").performScrollTo().assertIsDisplayed()
        assertEquals(false,closed)
        compose.onNodeWithText("Printer address").performTextReplacement("http://fixed.local/")
        compose.onNodeWithText("Save").performClick()
        assertEquals(true,closed)
        assertEquals("http://fixed.local/",model.state.value.profiles.single().address)
    }

    @Test fun missingSavedCameraShowsUnavailableAndAllowsExplicitReplacement() {
        val address="http://fixture.local/";var selected=""
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address=address,connected=true,
            profiles=listOf(PrinterProfile(address,cameraId="removed")),
            catalog=Catalog(emptyList(),emptyList(),listOf(Camera("Available","",id="available")),emptyList())),
            {},{},{},{_,_->},selectCamera={selected=it},tileCamera={}) } }
        compose.openFixtureDashboard()
        compose.onNodeWithText("Selected camera unavailable. Choose an available camera.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("camera:available").performScrollTo().performClick()
        assertEquals("available",selected)
    }

    @Test fun largeHistoryPagesAndLongFilenamesStayNavigable() {
        val jobs=(0 until 150).map { PrintJob("$it","folder/A long model filename with spaces and descriptive words number $it.gcode","completed",null,120.0,1500.0) }
        var offset=0
        compose.setContent { CompanionTheme {
            var state by remember {mutableStateOf(ScreenState(address="http://fixture.local/",connected=true))}
            CompanionScreen(state,{},{},{},{_,_->},loadHistory={start->
                offset=start;state=state.copy(history=jobs.drop(start).take(50),historyOffset=start,historyPageSize=jobs.drop(start).take(50).size)
            })
        } }
        compose.onNodeWithTag("nav-2").performClick()
        compose.onNodeWithTag("show-history").performClick()
        compose.onNodeWithText(jobs.first().filename).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Next").performScrollTo().performClick()
        assertEquals(50,offset)
        compose.onNodeWithText("Records 51–100 (this page)").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Records 101–150 (this page)").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Previous").performClick()
        assertEquals(100,offset)
    }

    @Test fun onlyFavoritedMacrosShowOnControlTabRestLiveInTheAdvancedBrowser() {
        // Owner request, 2026-09-22: the Control tab should show dedicated everyday controls,
        // not a raw dump of the printer's own arbitrary macro inventory - only macros explicitly
        // favorited in MacrosBrowserPanel (Advanced macros) should appear there, and the section
        // is omitted entirely when nothing is favorited yet.
        val address = "http://macro-scope-fixture.local/"
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("macro-options", 0)
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).joinToString("") {"%02x".format(it)}
        try {
            prefs.edit().remove(key).apply()
            compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address=address, connected=true,
                catalog=Catalog(emptyList(), listOf("BED_MESH_CALIBRATE","CANCEL_PRINT"), emptyList(), emptyList())), {}, {}, {}, {_,_->}) } }
            compose.onNodeWithTag("nav-1").performClick()
            compose.onNodeWithText("Favorite macros").assertDoesNotExist()
            compose.onNodeWithTag("screen-list").performScrollToNode(hasTestTag("open-macros"))
            compose.onNodeWithTag("open-macros").performClick()
            compose.onNodeWithText("BED_MESH_CALIBRATE").assertExists()
            compose.onNodeWithText("CANCEL_PRINT").assertExists()
            compose.onAllNodesWithText("Favorite")[0].performScrollTo().performClick()
            compose.onNodeWithText("Close").performClick()
            // The Control tab body is a LazyColumn (unlike the browser dialog's eager
            // verticalScroll Column) - the newly-favorited section won't be in the semantics
            // tree at all until scrolled into view.
            compose.onNodeWithTag("screen-list").performScrollToNode(hasText("Favorite macros"))
            compose.onNodeWithText("Favorite macros").assertExists()
            compose.onNodeWithText("BED_MESH_CALIBRATE").assertExists()
            compose.onNodeWithText("CANCEL_PRINT").assertDoesNotExist()
        } finally { prefs.edit().remove(key).apply() }
    }
}
