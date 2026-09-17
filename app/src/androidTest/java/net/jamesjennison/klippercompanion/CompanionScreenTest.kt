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
    @Test fun offlineDisablesPrintControls() {
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address="http://fixture.local/", connected=false, snapshot=PrinterSnapshot(true,"printing")), {}, {}, {}, { _,_->error("Must not dispatch") }) } }
        compose.openFixtureDashboard(connected=false)
        compose.onNodeWithText("Pause").performScrollTo().assertIsNotEnabled()
    }
    @Test fun savedProfileConnectAndForgetUseTheirExactAddresses() {
        val first = "http://first.local/"; val second = "http://second.local/"
        var connected = ""; var forgotten = ""
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address=first, savedPrinters=listOf(first,second)), { connected = it }, {}, {}, { _,_->error("Must not dispatch") }, { forgotten = it }) } }
        compose.onNodeWithTag("nav-3").performClick()
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
        compose.onNodeWithTag("nav-3").assertIsDisplayed().performClick()
        compose.onNodeWithTag("screen-list").performScrollToNode(hasTestTag("connect-printer"))
        compose.onNodeWithTag("connect-printer").assertIsDisplayed()
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
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address=address,connected=true,savedPrinters=listOf(address),profiles=listOf(profile),catalog=Catalog(emptyList(),emptyList(),cameras,emptyList())),{},{},{},{_,_->},updateProfile={old,_,name,_->assertEquals(address,old);edited=name;null},selectCamera={camera=it},tileCamera={}) } }
        compose.openFixtureDashboard()
        compose.onNodeWithTag("camera:side").performScrollTo().performClick();assertEquals("side",camera)
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("edit-profile:$address").performScrollTo().performClick()
        compose.onNodeWithText("Printer name").performTextReplacement("Garage")
        compose.onNodeWithText("Save").performClick();assertEquals("Garage",edited)
    }
    @Test fun profileEditorSendsApiKeyToSave() {
        val address="http://fixture.local/";var savedKey=""
        val profile=PrinterProfile(address,"Workshop",apiKey="old-key")
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address=address,connected=true,savedPrinters=listOf(address),profiles=listOf(profile)),{},{},{},{_,_->},updateProfile={_,_,_,key->savedKey=key;null},tileCamera={}) } }
        compose.openFixtureDashboard()
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("edit-profile:$address").performScrollTo().performClick()
        compose.onNodeWithText("API key (optional)").performTextReplacement("new-key")
        compose.onNodeWithText("Save").performClick();assertEquals("new-key",savedKey)
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

    @Test fun invalidProfileStaysOpenWithInlineError() {
        val model=PrinterModel("http://fixture.local/",initialProfiles=listOf(PrinterProfile("http://fixture.local/")))
        var closed=false
        compose.setContent { CompanionTheme {ProfileEditor(PrinterProfile("http://fixture.local/"),{closed=true},model::updateProfile)} }
        compose.onNodeWithText("Printer address").performTextReplacement("not a url")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Enter a valid local printer address.").assertIsDisplayed()
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

}
